package com.gios.lightcontrol.portal

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.CaptivePortal
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gios.lightcontrol.keys.LightKey
import com.gios.lightcontrol.keys.LightKeys
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
 */
class PortalActivity : ComponentActivity() {

    private var captivePortal: CaptivePortal? = null
    private var network: Network? = null
    private var webView: WebView? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        bar.addView(TextView(this).apply {
            text = "Wi-Fi login"
            setTextColor(Color.WHITE)
            textSize = 16f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "CHECK"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { probe() }
        })
        bar.addView(TextView(this).apply {
            text = "  ×  "
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(8))
            text = "finding the network…"
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        // A WebView is an installable system component, and nothing guarantees LightOS kept one.
        // Absent, the screen says so instead of crashing — the settings screen's fallback advice
        // (log the phone in from a computer) is then the way through.
        val web = try {
            WebView(this)
        } catch (t: Throwable) {
            null
        }
        if (web == null) {
            status.text = "This phone has no WebView, so the login page cannot be drawn here. " +
                "See the Wi-Fi login screen for the workaround."
            return
        }
        webView = web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Many portals end on a success page rather than closing anything; a page
                // settling is the cheapest moment to ask whether the gate is open yet.
                probe()
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        if (network == null) network = findWifi()
        val net = network
        if (net == null) {
            status.text = "No Wi-Fi network found — connect to the network in Settings first, " +
                "then come back here."
            return
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        cm.bindProcessToNetwork(net)
        status.text = "loading the network's login page…"
        web.loadUrl(PROBE_URL)
        handler.postDelayed(probeLoop, PROBE_EVERY_MS)
    }

    /** The captive Wi-Fi, when opened by hand rather than by the system's sign-in flow. */
    private fun findWifi(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private val probeLoop = object : Runnable {
        override fun run() {
            if (done) return
            probe()
            handler.postDelayed(this, PROBE_EVERY_MS)
        }
    }

    /** One request to a 204-endpoint over the bound network. 204 back means the gate is open. */
    private fun probe() {
        val net = network ?: return
        if (done) return
        Thread {
            val online = try {
                val c = net.openConnection(URL(PROBE_URL)) as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.useCaches = false
                val code = c.responseCode
                c.disconnect()
                code == 204
            } catch (t: Throwable) {
                false
            }
            handler.post {
                if (online && !done) {
                    done = true
                    status.text = "You're online — this network let you through."
                    captivePortal?.reportCaptivePortalDismissed()
                    handler.postDelayed({ finish() }, 1500)
                } else if (!done) {
                    status.text = "sign in above — checking the connection as you go"
                }
            }
        }.start()
    }

    /** Portal pages scroll; the wheel is how this phone scrolls. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) webView?.scrollBy(0, -dp(160))
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) webView?.scrollBy(0, dp(160))
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        done = true
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
    }
}
