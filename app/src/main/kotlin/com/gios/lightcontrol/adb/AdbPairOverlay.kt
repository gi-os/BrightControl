package com.gios.lightcontrol.adb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A floating panel that pairs ADB **without leaving the Settings pairing dialog**.
 *
 * The whole problem this solves: Android's "Pair device with pairing code" dialog only keeps the
 * pairing session alive while it is on screen, and the code it shows is not readable by any app —
 * so you cannot switch to BrightControl to type it without killing the session, and BrightControl
 * cannot read it for you. An overlay window sidesteps this: it is drawn *on top of* Settings in
 * our own window token, which does not pause the Settings activity, so the pairing dialog stays
 * up and advertising while this panel floats above it. You read the six digits from the dialog
 * behind the panel and type them into the panel — same screen, session intact.
 *
 * The window is focusable (the field needs the keyboard) but **not touch-modal**, so taps outside
 * the panel still reach Settings. It sits at the top so the centered pairing dialog stays visible
 * beneath it. Pairing, connecting and granting all run on a background thread; the panel only
 * collects the code and reports progress.
 *
 * Plain Views rather than Compose on purpose: an interactive overlay needs its own lifecycle and
 * recomposer to host Compose, which is a great deal of fragile plumbing for four widgets.
 */
object AdbPairOverlay {

    private var wm: WindowManager? = null
    private var root: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    fun allowed(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun showing(): Boolean = root != null

    fun show(context: Context) {
        if (root != null) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        lateinit var status: TextView
        lateinit var codeField: EditText
        lateinit var pairButton: Button
        lateinit var grantButton: Button

        fun setStatus(text: String) = main.post { status.text = text }

        val title = TextView(app).apply {
            text = "ADB pairing"
            setTextColor(Color.WHITE)
            textSize = 16f
            letterSpacing = 0.08f
        }
        val hintView = TextView(app).apply {
            text = "Keep this on top. In Settings, open Wireless debugging → Pair device with " +
                "pairing code. Type the six digits it shows here, then Pair — the dialog stays " +
                "open behind this panel."
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 12f
        }
        codeField = EditText(app).apply {
            hint = "6-digit code"
            setHintTextColor(Color.parseColor("#5E5E5E"))
            setTextColor(Color.WHITE)
            textSize = 20f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.parseColor("#262626"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        status = TextView(app).apply {
            text = "not connected"
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 12f
        }

        fun bordered(label: String, onClick: () -> Unit) = Button(app).apply {
            text = label
            isAllCaps = true
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

        pairButton = bordered("Pair & connect") {
            val code = codeField.text?.toString()?.trim().orEmpty()
            if (code.length != 6) { setStatus("enter the six-digit code first"); return@bordered }
            pairButton.isEnabled = false
            setStatus("pairing… keep the dialog open")
            worker.execute {
                val ok = runCatching {
                    val adb = AdbManager.getInstance(app)
                    val paired = adb.pairViaMdns(app, code, 60_000L)
                    if (!paired) return@runCatching "no pairing service found, or the code was wrong — reopen the dialog for a fresh code and try again"
                    val connected = runCatching { adb.connectAuto(app, 15_000L) }.getOrDefault(false)
                    if (connected) "CONNECTED — tap Grant all" else "paired, but connect failed — reopen BrightControl and use Connect on port"
                }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
                main.post {
                    status.text = ok
                    pairButton.isEnabled = true
                    grantButton.visibility =
                        if (ok.startsWith("CONNECTED")) View.VISIBLE else View.GONE
                }
            }
        }

        grantButton = bordered("Grant all") {
            grantButton.isEnabled = false
            setStatus("granting…")
            worker.execute {
                val summary = runCatching {
                    val adb = AdbManager.getInstance(app)
                    SelfGrant.steps.forEach { runCatching { adb.runCommand(it.command) } }
                    "done — close this and reopen BrightControl so the grants are read"
                }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
                main.post { status.text = summary; grantButton.isEnabled = true }
            }
        }.apply { visibility = View.GONE }

        val closeButton = bordered("Close") { hide() }

        val panel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.BLACK)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            fun gap(h: Int) = addView(View(app), LinearLayout.LayoutParams(1, dp(h)))
            addView(title); gap(6)
            addView(hintView); gap(12)
            addView(codeField); gap(10)
            addView(pairButton); gap(6)
            addView(grantButton); gap(6)
            addView(status); gap(10)
            addView(closeButton)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable so the field gets the keyboard, but NOT touch-modal so taps outside the
            // panel still land on the Settings pairing dialog behind it. FLAG_NOT_FOCUSABLE is
            // deliberately absent — that is the flag the readout overlays use, and it is exactly
            // what we must not set here.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // Let the panel resize for the keyboard instead of being covered by it.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        runCatching { wm.addView(panel, params) }
            .onSuccess { root = panel; this.wm = wm }
    }

    fun hide() {
        val panel = root ?: return
        root = null
        runCatching { wm?.removeView(panel) }
        wm = null
    }
}
