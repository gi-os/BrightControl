package com.gios.lightcontrol.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.CaptivePortal
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.LightKey
import com.gios.lightcontrol.keys.LightKeys
import com.gios.lightcontrol.report.Failure
import com.gios.lightcontrol.report.ReportContext
import com.gios.lightcontrol.report.Reports
import com.gios.lightcontrol.report.Symptom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * The captive-portal sign-in page LightOS has no browser for.
 *
 * A hotel or café network answers every request with its own login page until you submit it —
 * and on a phone with no browser there is nothing to submit it *with*, so the network connects
 * and then never validates. This activity is that missing piece: a WebView pinned to the captive
 * network, opened either from the Wi-Fi login settings screen or by the system's own
 * "sign in to network" flow ([ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN]).
 *
 * Two details carry the whole thing:
 *
 *  - **The process is bound to the captive network** ([ConnectivityManager.bindProcessToNetwork]).
 *    An unvalidated Wi-Fi network is exactly what Android routes *around* — left unbound, the
 *    WebView's requests would ride cellular data and the portal would never see them.
 *
 *  - **Success is probed, not inferred.** Every few seconds a request goes to a known
 *    204-endpoint over the bound network; the day it answers 204 instead of the portal's
 *    redirect, the login worked. Portals end their flows a dozen different ways (a success page,
 *    a redirect loop, a blank tab) and none of them is a reliable signal — the probe is.
 *
 * If the system handed us a [CaptivePortal] extra, success is also reported back through it so
 * LightOS marks the network usable instead of eventually giving up on it.
 *
 * **And since v4.12 it keeps a log and reports its own failures.** The screen does not work on the
 * phone, and nobody could say why: the only evidence was one status line, and the failure modes
 * (no WebView on this ROM, detection switched off by LightOS, the process bound to the wrong
 * network, a portal answering with a certificate the WebView refuses, a page that never comes)
 * all look identical from the outside — a page that never draws. So everything the screen learns
 * goes into a [PortalLog], the LOG button shows it on the phone, SEND LOG files it as a report by
 * hand, and the failures the screen can recognise by itself file one automatically. See [fail].
 */
class PortalActivity : ComponentActivity() {

    private var captivePortal: CaptivePortal? = null
    private var network: Network? = null
    private var webView: WebView? = null
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var loadAnyway: TextView
    private lateinit var vpnSettings: TextView
    private lateinit var systemSignIn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val log = PortalLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { Prefs(this) }

    /** One automatic report per opening of this screen. The LOG button is not counted. */
    private var autoReported = false

    /** Set the first time the WebView finishes *any* page. Its absence is the classic failure. */
    private var pageFinished = false
    private var openedAt = 0L
    private var probes = 0
    private var probeFailures = 0

    /**
     * How many of [probeFailures] were a name not resolving.
     *
     * Counted apart because the two have different answers. A network that refuses to route
     * anything is a network nothing on this screen can fix; a network whose resolver will not
     * answer for outside names is a network whose login page is still sitting there at an address.
     */
    private var dnsFailures = 0

    /**
     * The bind was refused because of a VPN. Probes cannot be made from this process then (EPERM),
     * so [probe] reads the system's verdict instead — the capability bits need no socket — and,
     * when the shell can be reached, asks the system to re-evaluate the network right now.
     */
    private var vpnBlocked = false

    /** A portal page the WebView refused on its certificate, held until the user says otherwise. */
    private var heldSsl: SslErrorHandler? = null

    /**
     * Whether a probe has ever come back *closed*.
     *
     * This is the difference between "you signed in" and "there was nothing to sign in to", and
     * getting it wrong is what made this screen useless. The old rule was that any 204 meant
     * success — so opening it on an ordinary network, one already validated, probed 204 within a
     * moment of the WebView appearing, announced "You're online", and closed itself before the
     * page had drawn. Tapping a login screen and having it vanish is indistinguishable from a
     * crash, and on a portal that answers an authenticated device with its *sign-out* page, what
     * flashes past on the way out is a sign-out page.
     *
     * A login flow is a gate that was shut and is now open. Without having seen it shut, this has
     * not watched anybody through it and does not get to say so.
     */
    /**
     * The URL the system probed, out of the intent that launched this screen.
     *
     * The platform's own login app starts at this and lets the portal redirect it; so does this
     * one now. See [PortalRoute.EXTRA_PORTAL_URL] for why reading it by name is fair game.
     */
    private var systemUrl: String? = null

    /**
     * Whether the login page has already been retried at the network's own address.
     *
     * Once, and only for a name that would not resolve. A second attempt at a page that loaded and
     * then failed on its own terms is a loop, and the gateway is a guess -- a good one, and still
     * a guess.
     */
    private var triedGateway = false

    /**
     * Whether this screen is in the background.
     *
     * The one fact that tells a handoff that worked from a handoff that vanished: if Android's own
     * sign-in page came up, this activity was stopped to make room for it.
     */
    private var stopped = false

    /** Whether a re-launch has already arrived, so the watchdog below has nothing to do. */
    private var handoffReturned = false

    /** So a handoff cannot arm two watchdogs, and a watchdog cannot arm another handoff's. */
    private var watchArmed = false

    private var sawClosedGate = false

    /** The probe loop only runs while this is on screen. See [onStart]. */
    private var watching = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        ReportContext.screen = "wifi-login/portal"

        val handedExtras = readSystemExtras(intent)
        captivePortal = handedExtras.first
        network = handedExtras.second
        systemUrl = handedExtras.third
        log.add("opened via ${intent.action ?: "explicit intent"}; system CaptivePortal extra: " +
            "${captivePortal != null}; network extra: ${network ?: "none"}; " +
            "portal url extra: ${systemUrl ?: "none"}")
        // Our own handoff, arriving back. See [SystemSignIn]: the notification we fire resolves to
        // whichever activity Android picks for `android.net.conn.CAPTIVE_PORTAL`, and half the
        // time that is this one. It is not a fault and must not file a second report about it --
        // light-reports #329 and #330 are one round trip, filed twice.
        if (captivePortal != null &&
            System.currentTimeMillis() - prefs.portalHandedOffAt < HANDOFF_WINDOW_MS
        ) {
            log.add("this is this app's own handoff coming back, with the binder attached")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "Wi-Fi login"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(button("LOG") { toggleLog() })
        bar.addView(button("SEND LOG") { sendLogByHand() })
        bar.addView(button("CHECK") { probe("check button") })
        bar.addView(TextView(this).apply {
            text = "  ×  "
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener {
                log.add("closed with ×")
                finish()
            }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(8))
            text = "finding the network…"
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Only ever shown when a portal's page came back with a certificate the WebView would not
        // accept. See onReceivedSslError.
        loadAnyway = TextView(this).apply {
            text = "LOAD IT ANYWAY"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener {
                val h = heldSsl
                heldSsl = null
                visibility = View.GONE
                if (h != null) {
                    log.add("user chose to load the page despite its certificate")
                    status.text = "loading the page despite its certificate…"
                    h.proceed()
                }
            }
        }
        root.addView(loadAnyway, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        // Only ever shown when a VPN is what stopped the bind. LightOS may not have this Settings
        // screen at all; the tap says so if not.
        vpnSettings = TextView(this).apply {
            text = "OPEN VPN SETTINGS"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener {
                log.add("user opened VPN settings")
                runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
                    .onFailure {
                        log.add("no VPN settings screen: ${it::class.java.simpleName}")
                        status.text = "This phone has no VPN settings screen. Turn the VPN off in its own app, then reopen this."
                    }
            }
        }
        root.addView(vpnSettings, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        systemSignIn = TextView(this).apply {
            text = "OPEN ANDROID'S SIGN-IN PAGE AGAIN"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener {
                // A deliberate press is a fresh attempt, so the watchdog gets to run again: this
                // button exists precisely because the last one produced nothing.
                handoffReturned = false
                watchArmed = false
                handOff("Asked again")
            }
        }
        root.addView(systemSignIn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // The log, over the WebView, toggled by the LOG button. Monospace so the probe codes line
        // up; small because there is a lot of it and the screen is 1080 wide.
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        logScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(logView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(root)

        // Everything this phone knows that decides whether the rest can work. Logged before the
        // first attempt so that a report of a failure carries the environment it failed in.
        log.add("build ${Build.DISPLAY}; SDK ${Build.VERSION.SDK_INT}")
        log.add("WebView provider: ${PortalDiagnostics.webView()}")
        PortalDiagnostics.captiveSettings(contentResolver).forEach { (k, v) ->
            log.add("Settings.Global.$k = ${v ?: "<unset, platform default>"}")
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        PortalDiagnostics.networks(cm).forEach { log.add("network: $it") }

        // A WebView is an installable system component, and nothing guarantees LightOS kept one.
        // Absent, the screen says so instead of crashing — the settings screen's fallback advice
        // (log the phone in from a computer) is then the way through.
        val web = try {
            WebView(this).also { log.add("WebView constructed; user agent: ${it.settings.userAgentString}") }
        } catch (t: Throwable) {
            log.add("WebView construction threw ${t::class.java.name}: ${t.message}")
            null
        }
        if (web == null) {
            fail(
                what = "draw the Wi-Fi login page — this phone has no working WebView",
                line = "This phone has no WebView, so the login page cannot be drawn here. " +
                    "See the Wi-Fi login screen for the workaround.",
            )
            return
        }
        webView = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webViewClient = PortalClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                log.add("js console ${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                log.add("page title: ${title ?: "<none>"}")
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(logScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        if (network == null) network = findWifi()
        val net = network
        if (net == null) {
            fail(
                what = "find a Wi-Fi network to sign in to",
                line = "No Wi-Fi network found — connect to the network in Settings first, " +
                    "then come back here.",
            )
            return
        }
        log.add("bound to: ${PortalDiagnostics.describe(cm, net, net == cm.activeNetwork)}")

        status.text = "loading the network's login page…"
        // Bound here as well as in onStart, because the first load happens before onStart runs and
        // an unbound WebView would fetch the probe URL over whatever the system prefers -- which
        // is the *other* network, the one that works, so the portal never sees the request and
        // never gets a chance to redirect.
        val bound = cm.bindProcessToNetwork(net)
        log.add("bindProcessToNetwork → $bound; process's bound network now ${cm.boundNetworkForProcess}")
        if (!bound) {
            // **This is the VPN.** light-reports #242–#244: the phone was on the portal network,
            // flagged CAPTIVE_PORTAL, WebView present, detection on — and the bind returned false,
            // then every probe died with `EPERM`. A VPN (tun0) was the default network. netd's rule
            // is that a UID whose traffic goes through a VPN may not explicitly select any other
            // network, unless the VPN app allows bypass; the system's own CaptivePortalLogin is
            // exempt by privilege, and this app is not. Nothing here can lift that. What it can do
            // is say so, name the VPN when the phone will, and not spend 25 s pretending to load.
            val vpn = PortalDiagnostics.vpn(cm)
            if (vpn != null) {
                val app = PortalDiagnostics.alwaysOnVpnApp(contentResolver)
                val appLabel = app?.let { pkg ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg)
                }
                log.add("VPN up: ${PortalDiagnostics.describe(cm, vpn, vpn == cm.activeNetwork)}; always-on app: ${app ?: "not set"}")
                // Android's own sign-in app is allowed past the VPN. Hand it over, and watch
                // whether anything came of it -- see [handOff].
                vpnBlocked = true
                handOff(
                    "A VPN is on" + (appLabel?.let { " ($it)" } ?: "") + ", so this page cannot load here",
                )
                return
            }
            fail(
                what = "bind the app to the Wi-Fi network (bindProcessToNetwork returned false, no VPN up)",
                line = "Android refused to route this app over the Wi-Fi network, so the login " +
                    "page cannot be reached. This has been reported.",
            )
            return
        }
        // Unbound again in onStop: bindProcessToNetwork routes the *whole app process*, and an
        // unvalidated portal network is one with no internet on the far side of it. Leaving this
        // screen with Home does not destroy the activity, so binding once here left every other
        // thing this app does -- shake-to-report, the ADB screen's own traffic -- pointed at a
        // network that goes nowhere, for as long as the activity stayed in the back stack.
        val start = PortalRoute.startUrl(systemUrl)
        log.add("loadUrl $start" + if (start != PROBE_URL) " (the system's own portal url)" else "")
        web.loadUrl(start)

        // The classic failure is a page that never comes, and a page that never comes raises no
        // callback. A watchdog is the only thing that can notice nothing happening.
        handler.postDelayed({
            if (!done && !pageFinished) {
                fail(
                    what = "load the Wi-Fi login page within ${PAGE_TIMEOUT_MS / 1000}s",
                    line = "The login page has not loaded after ${PAGE_TIMEOUT_MS / 1000}s. " +
                        "This has been reported; the LOG button shows what happened.",
                )
            }
        }, PAGE_TIMEOUT_MS)
    }

    /**
     * The round trip, arriving.
     *
     * **This is what was broken in v4.28.** The activity is `singleTask`, so when the system's
     * *"Sign in to network"* notification resolves back to this app -- which is half the time, see
     * [SystemSignIn] -- Android does not create it again. It calls this, with the intent that
     * carries the [CaptivePortal] binder, the network and the portal URL. Nothing was listening,
     * so the one thing the round trip was worth was dropped on the floor and the screen sat there
     * with `fired the system's sign-in notification → true` as its last line.
     *
     * Now the binder is picked up and spent immediately: under a VPN by handing it to the app
     * that is allowed past one, otherwise by loading the page this screen came here to load.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // So every later reader -- [handOff]'s extras forwarding above all -- sees the intent that
        // actually carries something rather than the one this screen was opened with.
        setIntent(intent)
        handoffReturned = true
        handler.removeCallbacks(handoffWatch)
        val fresh = readSystemExtras(intent)
        log.add(
            "re-launched via ${intent.action ?: "explicit intent"}; binder: ${fresh.first != null}; " +
                "network: ${fresh.second ?: "none"}; portal url: ${fresh.third ?: "none"}",
        )
        if (done) return
        fresh.first?.let { captivePortal = it }
        fresh.second?.let { network = it }
        fresh.third?.let { systemUrl = it }
        when {
            // A VPN is still a VPN. What changed is that there is now a binder to hand over, so
            // the system's own app can tell the system it got through instead of the user having
            // to come back here and press CHECK.
            vpnBlocked -> {
                if (captivePortal == null) {
                    log.add("the round trip brought no binder; handing over anyway")
                }
                handOff("handing the sign-in on, with what the round trip brought")
            }
            // Not blocked, and the page never loaded: this is the launch that finally carried a
            // URL. Load it.
            !pageFinished -> {
                val url = PortalRoute.startUrl(systemUrl)
                log.add("loading $url after the re-launch")
                status.text = "loading the network's login page…"
                webView?.loadUrl(url)
            }
            else -> log.add("the page is already up; the re-launch changes nothing")
        }
    }

    /**
     * Hand the sign-in to the platform's own app, and watch whether anything came of it.
     *
     * The notification route cannot be told from a no-op at the moment it is fired -- the
     * PendingIntent reports that it was sent, not what opened -- so the answer is measured
     * instead: if Android's page came up, this activity is stopped, and if the round trip landed
     * back here, [onNewIntent] ran. Neither, after [HANDOFF_WATCH_MS], and the notification went
     * nowhere; the direct launch is then the only route left and it is taken without asking.
     */
    private fun handOff(line: String) {
        prefs.portalHandedOffAt = System.currentTimeMillis()
        systemSignIn.visibility = View.VISIBLE
        when (val opened = SystemSignIn.open(this, network, intent) { log.add(it) }) {
            SystemSignIn.Opened.ViaNotification -> {
                // Not a failure and not reported as one: a notification that resolves back to this
                // app is the expected shape of this, and reporting it is how light-reports #329
                // and #330 became two issues about one round trip.
                status.text = "$line — Android's own sign-in page is opening (it is allowed past " +
                    "the VPN). Sign in there; CHECK here asks whether it worked."
                if (!watchArmed) {
                    watchArmed = true
                    handler.postDelayed(handoffWatch, HANDOFF_WATCH_MS)
                }
            }
            is SystemSignIn.Opened.ViaIntent -> {
                status.text = "$line — Android's own sign-in page (${opened.pkg}) is opening. " +
                    "Sign in there, then CHECK here: it asks the system to look at the network again."
            }
            is SystemSignIn.Opened.Failed -> {
                log.add("system sign-in unavailable: ${opened.why}")
                vpnSettings.visibility = View.VISIBLE
                fail(
                    what = "hand the sign-in to Android's own page (${opened.why}) from under a VPN " +
                        "(bindProcessToNetwork returned false)",
                    line = "A VPN is on, and Android does not let an app under a VPN talk to any " +
                        "other network — so the login page cannot load here, and this phone has no " +
                        "system sign-in page to hand it to (${opened.why}). Turn the VPN off, sign " +
                        "in here, then turn it back on.",
                )
            }
        }
    }

    private val handoffWatch = Runnable {
        when {
            done || handoffReturned -> Unit
            // Stopped means something else has the screen, and the only thing this app just asked
            // for is the sign-in page. That is the handoff working.
            stopped -> log.add("the sign-in page has the screen; nothing more for this one to do")
            else -> {
                log.add("nothing came of the notification in ${HANDOFF_WATCH_MS}ms; launching the system's app directly")
                // Marked first: the direct launch must not re-arm this.
                handoffReturned = true
                when (val opened = SystemSignIn.direct(this, network, intent) { log.add(it) }) {
                    is SystemSignIn.Opened.ViaIntent -> {
                        status.text = "Android's own sign-in page (${opened.pkg}) is opening. Sign " +
                            "in there, then CHECK here."
                    }
                    else -> {
                        vpnSettings.visibility = View.VISIBLE
                        fail(
                            what = "open any sign-in page under a VPN — the system's notification " +
                                "fired and nothing opened, and the direct launch did not either",
                            line = "A VPN is on and neither Android's sign-in notification nor its " +
                                "sign-in app opened anything. Turn the VPN off, sign in here, then " +
                                "turn it back on. This has been reported.",
                        )
                    }
                }
            }
        }
    }

    /** The three things the system puts in the intent, read the same way from wherever it comes. */
    private fun readSystemExtras(from: Intent): Triple<CaptivePortal?, Network?, String?> {
        val portal: CaptivePortal?
        val net: Network?
        if (Build.VERSION.SDK_INT >= 33) {
            portal = from.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL, CaptivePortal::class.java)
            net = from.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            @Suppress("DEPRECATION")
            portal = from.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL)
            @Suppress("DEPRECATION")
            net = from.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }
        val url = runCatching { from.getStringExtra(PortalRoute.EXTRA_PORTAL_URL) }.getOrNull()
        return Triple(portal, net, url)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        val net = network ?: return
        val cm = getSystemService(ConnectivityManager::class.java)
        val bound = cm.bindProcessToNetwork(net)
        log.add("onStart: rebound → $bound")
        if (done || autoReported && !bound) return
        watching = true
        handler.postDelayed(probeLoop, PROBE_EVERY_MS)
    }

    override fun onStop() {
        stopped = true
        watching = false
        handler.removeCallbacks(probeLoop)
        getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        log.add("onStop: unbound")
        super.onStop()
    }

    /**
     * The captive Wi-Fi, when opened by hand rather than by the system's sign-in flow.
     *
     * Ranked rather than "the first Wi-Fi in the list". `allNetworks` has no meaningful order and
     * holds networks on their way down as well as up, so the first Wi-Fi entry can easily be one
     * that is being torn down — and the process then binds to it, which is a portal page that
     * never loads and a probe that never answers, every time, with nothing on screen to say why.
     *
     * The one worth binding to is the one with a gate in front of it: a network the system has
     * flagged as captive first, then one that is connected but not validated, then any Wi-Fi at
     * all. That is also the order of how likely the user is to be looking at the problem this
     * screen exists for.
     */
    private fun findWifi(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifis = cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        fun has(n: Network, cap: Int) =
            cm.getNetworkCapabilities(n)?.hasCapability(cap) == true
        val captive = wifis.firstOrNull { has(it, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) }
        val unvalidated = wifis.firstOrNull { !has(it, NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val pick = captive ?: unvalidated ?: wifis.firstOrNull()
        log.add("findWifi: ${wifis.size} wifi network(s); captive=${captive ?: "-"} unvalidated=${unvalidated ?: "-"} → ${pick ?: "none"}")
        return pick
    }

    private val probeLoop = object : Runnable {
        override fun run() {
            if (done || !watching) return
            probe("timer")
            handler.postDelayed(this, PROBE_EVERY_MS)
        }
    }

    /** One request to a 204-endpoint over the bound network. 204 back means the gate is open. */
    private fun probe(why: String) {
        val net = network ?: return
        if (done) return
        if (vpnBlocked) { verdictWithoutSocket(net, why); return }
        val n = ++probes
        Thread {
            val started = SystemClock.elapsedRealtime()
            val online = try {
                val c = net.openConnection(URL(PROBE_URL)) as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.useCaches = false
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                val server = c.getHeaderField("Server")
                c.disconnect()
                log.add("probe #$n ($why): HTTP $code in ${SystemClock.elapsedRealtime() - started}ms" +
                    (location?.let { " → $it" } ?: "") + (server?.let { " [$it]" } ?: ""))
                code == 204
            } catch (t: Throwable) {
                probeFailures++
                if (PortalRoute.isDnsThrow(t::class.java.simpleName, t.message)) dnsFailures++
                log.add("probe #$n ($why): ${t::class.java.simpleName}: ${t.message} after ${SystemClock.elapsedRealtime() - started}ms")
                false
            }
            // The system's own verdict, read rather than measured, and the one success signal on
            // this screen that needs no DNS and no socket: when a portal is passed, the platform
            // re-probes and the network turns VALIDATED. On a network whose resolver answers
            // nothing (light-reports #286) that is the only way through this screen can see.
            val caps = if (online) null else runCatching {
                getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(net)
            }.getOrNull()
            val systemSaysOnline = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            handler.post {
                if (done) return@post
                if (!online && systemSaysOnline) {
                    done = true
                    log.add("through: the system says this network is validated and no longer captive")
                    status.text = "You're online — the system says this network lets you through."
                    handler.postDelayed({ finish() }, 1500)
                    return@post
                }
                if (!online) {
                    sawClosedGate = true
                    status.text = "sign in above — checking the connection as you go"
                    // A resolver that answers nothing is the failure that looks like every other
                    // one, and it has its own way out: the address the lease came from. Tried
                    // before anything is reported, because a report about a page that then loads
                    // is a report about nothing.
                    if (dnsFailures >= 2 && dnsFailures == probeFailures &&
                        retryAtGateway("$dnsFailures probes could not resolve a name")
                    ) {
                        return@post
                    }
                    // Every probe throwing, rather than being redirected, means the network is not
                    // answering at all: no portal is hijacking anything, and nothing the user does
                    // on this screen can change that. Say so once.
                    if (probeFailures >= PROBE_FAILURES_TO_REPORT && probeFailures == probes) {
                        val dnsOnly = dnsFailures == probeFailures
                        fail(
                            what = if (dnsOnly) {
                                "resolve any name over the Wi-Fi network ($dnsFailures probes in a row, " +
                                    "all of them DNS; the network's own address was tried too)"
                            } else {
                                "reach anything over the Wi-Fi network ($probeFailures probes in a row threw, none answered)"
                            },
                            line = if (dnsOnly) {
                                "This network's DNS does not answer for anything outside it, so " +
                                    "no page can be found by name. This has been reported; the " +
                                    "LOG button shows what happened."
                            } else {
                                "Nothing answers over this Wi-Fi — not even the login page. " +
                                    "This has been reported; the LOG button shows what happened."
                            },
                        )
                    }
                    return@post
                }
                // The system asked us to resolve this network, and it is resolved. Tell it either
                // way: it is the answer to a question it asked, not a claim about what the user
                // did in here.
                runCatching { captivePortal?.reportCaptivePortalDismissed() }
                    .onFailure { log.add("reportCaptivePortalDismissed threw ${it::class.java.simpleName}: ${it.message}") }
                if (sawClosedGate) {
                    done = true
                    log.add("through: a closed gate is open")
                    status.text = "You're online — this network let you through."
                    handler.postDelayed({ finish() }, 1500)
                } else {
                    // Open on arrival, so nothing was signed and nothing is being closed. Saying
                    // so and staying put is the whole fix: a portal's page for an already-admitted
                    // device is usually its sign-out page, and closing the screen the instant it
                    // opened is how the feature read as broken.
                    done = true
                    log.add("already online on arrival; nothing to sign")
                    status.text = "This network is already online — there was nothing to sign " +
                        "in to. The page below is the network's own, if you want it."
                }
            }
        }.start()
    }

    /**
     * Under a VPN this process may not open a socket on the Wi-Fi, but it may still *ask* about
     * it: VALIDATED appearing on the network is the system's own probe saying the gate is open.
     * The system re-probes a captive network on its own schedule; over the shell — reachable, since
     * the phone is on Wi-Fi — `cmd connectivity reevaluate <netId>` makes it look now.
     */
    private fun verdictWithoutSocket(net: Network, why: String) {
        val n = ++probes
        Thread {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (com.gios.lightcontrol.adb.AdbManager.hasPairing(this) &&
                com.gios.lightcontrol.adb.AdbManager.ensureAlive(this, 4_000)
            ) {
                val out = com.gios.lightcontrol.adb.AdbManager.runVia(this, "cmd connectivity reevaluate $net", 6_000)
                log.add("asked the system to reevaluate $net → ${out.trim().take(120).ifBlank { "ok" }}")
                Thread.sleep(2_500)
            }
            val caps = cm.getNetworkCapabilities(net)
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
            log.add("verdict #$n ($why): ${if (caps == null) "network gone" else "validated=$validated captive=$captive"}")
            handler.post {
                if (done) return@post
                when {
                    caps == null -> status.text = "The Wi-Fi network is gone."
                    validated && !captive -> {
                        done = true
                        status.text = "You're online — the system says this network lets you through."
                        handler.postDelayed({ finish() }, 1500)
                    }
                    else -> status.text = "Not through yet (system still sees a login page). Sign in on Android's page, then CHECK."
                }
            }
        }.start()
    }

    /** Everything the WebView tells us, written down. */
    private inner class PortalClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            log.add("page started: $url")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            pageFinished = true
            log.add("page finished: $url")
            // Many portals end on a success page rather than closing anything; a page
            // settling is the cheapest moment to ask whether the gate is open yet.
            probe("page finished")
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            log.add("navigate: ${request?.url}" + if (request?.isRedirect == true) " (redirect)" else "")
            return false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val main = request?.isForMainFrame == true
            log.add("${if (main) "MAIN FRAME" else "subresource"} error ${error?.errorCode} " +
                "${error?.description} for ${request?.url}")
            if (!main || pageFinished) return
            // A name that will not resolve is not a network that is not there. See
            // [PortalRoute.gatewayUrl] and light-reports #286/#287: the portal was reachable the
            // whole time, at an address, while every hostname on the phone was dead.
            if (error?.errorCode == PortalRoute.ERROR_HOST_LOOKUP && retryAtGateway("DNS did not resolve it")) return
            fail(
                what = "load the Wi-Fi login page (WebView error ${error?.errorCode}: ${error?.description})",
                line = "The login page failed to load (${error?.description}). This has been " +
                    "reported; the LOG button shows what happened.",
            )
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                log.add("MAIN FRAME http ${response?.statusCode} ${response?.reasonPhrase} for ${request.url}")
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
            // A portal that redirects its hijacked http request to an https page with a
            // certificate for some other name is common — the WebView's default is to cancel it
            // silently, which on this screen was a blank page with no explanation. Held, not
            // cancelled: the page is only the Wi-Fi's own login form, and the user can choose.
            log.add("SSL error ${error.primaryError} for ${error.url}: ${sslWhy(error)}")
            heldSsl?.cancel()
            heldSsl = handler
            loadAnyway.visibility = View.VISIBLE
            status.text = "The login page's security certificate isn't trusted (${sslWhy(error)}). " +
                "Only load it anyway if this is the Wi-Fi's own sign-in page."
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            log.add("renderer gone; didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}")
            fail(
                what = "keep the Wi-Fi login page drawn (the WebView renderer died)",
                line = "The page's renderer died. This has been reported.",
            )
            // True means "handled": the framework would otherwise kill the app.
            webView?.let { (it.parent as? FrameLayout)?.removeView(it); it.destroy() }
            webView = null
            return true
        }
    }

    /**
     * Load the login page at the network's own address instead of by name.
     *
     * Returns whether it did, so the caller can leave its failure alone: a retry that happens is
     * not a failure yet, and one that cannot happen is exactly the failure it was about to report.
     * The page timeout is re-armed from here, because the watchdog that fires 25s after opening
     * would otherwise land in the middle of this attempt and report a page that is loading.
     */
    private fun retryAtGateway(why: String): Boolean {
        if (triedGateway || done) return false
        val net = network ?: return false
        val url = gatewayUrl(net) ?: run {
            log.add("$why, and this network gave out no address worth trying")
            return false
        }
        triedGateway = true
        log.add("$why; trying the network's own address: $url")
        status.text = "This network's DNS is not answering — trying the login page at $url"
        pageFinished = false
        webView?.loadUrl(url)
        handler.postDelayed({
            if (!done && !pageFinished) {
                fail(
                    what = "load the Wi-Fi login page by name or at the network's own address ($url)",
                    line = "Neither the login page's name nor this network's own address " +
                        "($url) answered. This has been reported; the LOG button shows what happened.",
                )
            }
        }, PAGE_TIMEOUT_MS)
        return true
    }

    /**
     * The three addresses the phone already knows for the network it is on.
     *
     * `getDhcpServerAddress` is API 30 and this app's floor is 29, so on the older end the
     * gateway and the resolvers do the work. Everything is passed to [PortalRoute] as text -- the
     * decision belongs somewhere it can be unit-tested.
     */
    private fun gatewayUrl(net: Network): String? = runCatching {
        val lp = getSystemService(ConnectivityManager::class.java).getLinkProperties(net)
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null
        val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        log.add("addresses on this network: dhcp=${dhcp ?: "-"} gateway=${gateway ?: "-"} dns=${dns.joinToString().ifBlank { "-" }}")
        PortalRoute.gatewayUrl(dhcp, gateway, dns)
    }.getOrNull()

    private fun sslWhy(e: SslError): String = when (e.primaryError) {
        SslError.SSL_EXPIRED -> "expired"
        SslError.SSL_IDMISMATCH -> "for a different host"
        SslError.SSL_UNTRUSTED -> "untrusted issuer"
        SslError.SSL_DATE_INVALID -> "invalid date"
        SslError.SSL_NOTYETVALID -> "not yet valid"
        SslError.SSL_INVALID -> "invalid"
        else -> "error ${e.primaryError}"
    }

    /**
     * A failure this screen recognised on its own: say it, and file the log.
     *
     * Once per opening of the screen, and never more than one every [AUTO_REPORT_GAP_MS] across
     * openings (kept in prefs, so a relaunch is not a fresh start — see light-reports #217 and
     * friends for what happens without that). The report is what Gio asked for: the screen fails
     * on the phone and nobody can say why, so the log has to leave the phone by itself. A report
     * the user has to agree to is a report that gets dismissed while they are inside the failure.
     */
    private fun fail(what: String, line: String, report: Boolean = true) {
        status.text = line
        log.add("FAIL: could not $what")
        if (!report) {
            log.add("not reported: this failure is already on an open issue for this phone")
            return
        }
        if (autoReported) return
        autoReported = true
        val now = System.currentTimeMillis()
        if (now - prefs.portalLastAutoReport < AUTO_REPORT_GAP_MS) {
            log.add("not auto-reported: another Wi-Fi login report left less than ${AUTO_REPORT_GAP_MS / 60_000} min ago")
            status.text = "$line (Not re-reported — one left recently. SEND LOG files it by hand.)"
            return
        }
        prefs.portalLastAutoReport = now
        send(what, byHand = false)
    }

    /** The SEND LOG button. Always files, whatever the throttles say — it is the user asking. */
    private fun sendLogByHand() {
        log.add("SEND LOG tapped")
        val what = when {
            done -> "report anything wrong — the user sent the Wi-Fi login log after getting through"
            else -> "get through the Wi-Fi login page (log sent by hand, page open ${(SystemClock.elapsedRealtime() - openedAt) / 1000}s)"
        }
        send(what, byHand = true)
    }

    private fun send(what: String, byHand: Boolean) {
        val report = Reports.compose(
            context = this,
            symptom = Symptom.Other,
            note = "Wi-Fi login: could not $what",
            screen = ReportContext.screen,
            crash = null,
            failure = Failure(what, log.dump()),
        )
        val cm = getSystemService(ConnectivityManager::class.java)
        // The process is bound to the very network that does not work. Reports.submit queues to
        // disk before it posts, so nothing is lost either way, but posting from behind the portal
        // would just add one more failed request to the queue's day. Unbind for the send, rebind
        // after if the screen is still live.
        cm.bindProcessToNetwork(null)
        scope.launch {
            runCatching { Reports.submit(this@PortalActivity, report) }
            handler.post {
                if (watching && !done) network?.let { cm.bindProcessToNetwork(it) }
                val where = if (Reports.canSend()) "Sent" else "Saved to send later"
                val tail = if (byHand) "$where: the Wi-Fi login log." else "$where: a report with the log."
                status.text = "${status.text} — $tail"
                log.add("report ${if (Reports.canSend()) "posted or queued" else "queued (no token in this build)"}")
            }
        }
    }

    private fun toggleLog() {
        val showing = logScroll.visibility == View.VISIBLE
        if (showing) {
            logScroll.visibility = View.GONE
            log.onLine = null
        } else {
            logView.text = log.dump()
            logScroll.visibility = View.VISIBLE
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            log.onLine = { line ->
                handler.post {
                    logView.append("\n$line")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { onClick() }
    }

    /** Portal pages scroll; the wheel is how this phone scrolls. The log too, when it is up. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target: View? = if (logScroll.visibility == View.VISIBLE) logScroll else webView
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) target?.scrollBy(0, -dp(160))
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) target?.scrollBy(0, dp(160))
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // Leaving without getting through, after the page had a real chance: that is the report
        // Gio actually files by hand ("it's not working"), so file it — but through the
        // once-per-install ledger, because backing out of a login page is also just a thing
        // people do, and a screen that reports every retreat is a screen that gets its
        // reporting switched off.
        if (isFinishing && !done && !autoReported && sawClosedGate &&
            SystemClock.elapsedRealtime() - openedAt >= ABANDON_AFTER_MS
        ) {
            val family = "get through the Wi-Fi login page before the user gave up"
            if (!prefs.failureAutoReported(family)) {
                prefs.noteFailureAutoReported(family)
                autoReported = true
                log.add("left without getting through after ${(SystemClock.elapsedRealtime() - openedAt) / 1000}s; reporting once")
                send(family, byHand = false)
            } else {
                log.add("left without getting through; already reported once on this phone")
            }
        }
        done = true
        heldSsl?.let { runCatching { it.cancel() } }
        heldSsl = null
        handler.removeCallbacksAndMessages(null)
        getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Google's connectivity probe, the same endpoint the platform itself checks. Plain http
         * on purpose: a portal can only hijack what it can read, and an https probe would surface
         * as a certificate error instead of a login page.
         */
        private const val PROBE_URL = PortalRoute.PROBE_URL

        /**
         * How long after handing off to the system a re-launch is our own doing rather than a new
         * problem. Wall-clock, because the handoff is stored across activity deaths.
         */
        private const val HANDOFF_WINDOW_MS = 3L * 60L * 1_000L

        /**
         * How long a fired notification gets to produce something.
         *
         * Long enough for an activity to be started and this one to be stopped, short enough that
         * a phone on a hotel network is not left looking at a screen that has given up without
         * saying so. Everything this waits for is local; nothing on a network is involved.
         */
        private const val HANDOFF_WATCH_MS = 2_500L
        private const val PROBE_EVERY_MS = 4000L

        /** How long a login page gets to finish before its absence is the failure. */
        private const val PAGE_TIMEOUT_MS = 25_000L

        /** Consecutive probes throwing (not redirecting) before "nothing answers" is reported. */
        private const val PROBE_FAILURES_TO_REPORT = 5

        /** Leaving sooner than this is a mis-tap, not an attempt. */
        private const val ABANDON_AFTER_MS = 15_000L

        /** Between two automatic Wi-Fi login reports, across relaunches. */
        private const val AUTO_REPORT_GAP_MS = 10L * 60L * 1_000L
    }
}
