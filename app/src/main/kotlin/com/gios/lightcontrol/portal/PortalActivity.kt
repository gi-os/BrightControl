package com.gios.lightcontrol.portal

import android.annotation.SuppressLint
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
    private var sawClosedGate = false

    /** The probe loop only runs while this is on screen. See [onStart]. */
    private var watching = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        ReportContext.screen = "wifi-login/portal"

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) {
            captivePortal =
                intent.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL, CaptivePortal::class.java)
            network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        } else {
            @Suppress("DEPRECATION")
            captivePortal = intent.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL)
            @Suppress("DEPRECATION")
            network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK)
        }
        log.add("opened via ${intent.action ?: "explicit intent"}; system CaptivePortal extra: " +
            "${captivePortal != null}; network extra: ${network ?: "none"}")

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
            fail(
                what = "bind the app to the Wi-Fi network (bindProcessToNetwork returned false)",
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
        log.add("loadUrl $PROBE_URL")
        web.loadUrl(PROBE_URL)

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

    override fun onStart() {
        super.onStart()
        val net = network ?: return
        val cm = getSystemService(ConnectivityManager::class.java)
        val bound = cm.bindProcessToNetwork(net)
        log.add("onStart: rebound → $bound")
        if (done) return
        watching = true
        handler.postDelayed(probeLoop, PROBE_EVERY_MS)
    }

    override fun onStop() {
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
                log.add("probe #$n ($why): ${t::class.java.simpleName}: ${t.message} after ${SystemClock.elapsedRealtime() - started}ms")
                false
            }
            handler.post {
                if (done) return@post
                if (!online) {
                    sawClosedGate = true
                    status.text = "sign in above — checking the connection as you go"
                    // Every probe throwing, rather than being redirected, means the network is not
                    // answering at all: no portal is hijacking anything, and nothing the user does
                    // on this screen can change that. Say so once.
                    if (probeFailures >= PROBE_FAILURES_TO_REPORT && probeFailures == probes) {
                        fail(
                            what = "reach anything over the Wi-Fi network ($probeFailures probes in a row threw, none answered)",
                            line = "Nothing answers over this Wi-Fi — not even the login page. " +
                                "This has been reported; the LOG button shows what happened.",
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
            if (main && !pageFinished) {
                fail(
                    what = "load the Wi-Fi login page (WebView error ${error?.errorCode}: ${error?.description})",
                    line = "The login page failed to load (${error?.description}). This has been " +
                        "reported; the LOG button shows what happened.",
                )
            }
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
    private fun fail(what: String, line: String) {
        status.text = line
        log.add("FAIL: could not $what")
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
        private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
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
