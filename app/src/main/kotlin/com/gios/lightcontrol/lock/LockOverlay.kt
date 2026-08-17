package com.gios.lightcontrol.lock

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gios.lightcontrol.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The Light face, painted over the lock screen.
 *
 * ### Why this is a window and not an activity
 *
 * v2.5 and v2.6 drew this as an activity marked `showWhenLocked`, and on this phone the thumb did
 * not work — because that flag makes the keyguard **occluded**, and AOSP's
 * `KeyguardUpdateMonitor.shouldListenForFingerprint` arms the sensor while occluded only for an
 * under-display reader, a dreaming device, or a bouncer already showing. The LPIII's reader is in
 * the power button, so the listener switched off the moment our screen appeared.
 *
 * A window is not an activity. Nothing here sets `FLAG_SHOW_WHEN_LOCKED`, nothing calls
 * `setShowWhenLocked`, and no task ever comes to the front — so **the keyguard is never occluded**.
 * From SystemUI's point of view the lock screen is showing and visible exactly as it always is, its
 * fingerprint listener is armed exactly as it always is, and the press on the power button unlocks
 * the phone exactly as it always did. We are only painting over the top of it.
 *
 * Painting over the top is possible because of the window layer, read off AOSP's
 * `WindowManagerPolicy.getWindowLayerFromTypeLw` (android-14.0.0_r1):
 *
 * | window | layer |
 * |---|---|
 * | `TYPE_APPLICATION_OVERLAY` — what the brightness readout and volume HUD use | 11 |
 * | `TYPE_NOTIFICATION_SHADE` — **the keyguard itself** | 17 |
 * | `TYPE_KEYGUARD_DIALOG` — the bouncer | 19 |
 * | **`TYPE_ACCESSIBILITY_OVERLAY`** | **31** |
 *
 * Which is also why the readout has never appeared over the lock screen: at 11 it is underneath it.
 * Only an accessibility service may add a window at 31, and this app has been one since v1.0.1.
 *
 * Three other problems fall out for free:
 *
 *  - **No flash.** A window at layer 31 is above LightOS's lock screen whenever that arrives, so
 *    there is nothing to race and no 900 ms delay to tune.
 *  - **No appop.** No activity means no background activity start, so `SYSTEM_ALERT_WINDOW` is
 *    not involved at all.
 *  - **Nothing to trap the user in.** This holds no key focus, owns no task, and has no back
 *    stack. `FLAG_NOT_FOCUSABLE` means every key — power, wheel, camera button — goes where it
 *    always went. If this code throws, the lock screen is right there underneath, untouched.
 *
 * ### The one thing it takes
 *
 * Touches, so a tap can get out of the way when you want the keypad. That is all a tap does: hide
 * this window, revealing the real lock screen already behind it. It never asks for the bouncer and
 * never dismisses anything.
 */
class LockOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var status: TextView? = null
    private var alarm: TextView? = null
    private var notes: LinearLayout? = null

    /** Hidden by a tap, and left hidden until the next sleep. */
    private var dismissedByTouch = false

    val showing: Boolean get() = root != null

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            runCatching { refresh() }
        }
    }
    private var tickerOn = false

    /**
     * Put the face up.
     *
     * Called as the screen goes off, so all of the work below — including decoding a photograph —
     * happens against a panel nobody is looking at.
     */
    fun show(prefs: Prefs) {
        dismissedByTouch = false
        if (root != null) {
            refresh()
            return
        }
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val view = build(prefs) ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // The whole design, in one constant. See the class comment.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE is load-bearing, not tidiness: a focusable window here would take key
            // focus from the keyguard, and the keyguard is what the power button, the wheel and
            // every other key have to keep reaching. Touches still arrive without it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { wm.addView(view, params) }
            .onSuccess {
                root = view
                startTicking()
                refresh()
            }
    }

    /** Take it down — on unlock, or when the service goes away. */
    fun hide() {
        stopTicking()
        val view = root ?: return
        root = null
        clock = null
        date = null
        status = null
        alarm = null
        notes = null
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
    }

    /** True once a tap has put it away, so a re-show on screen-on does not fight the user. */
    fun dismissed(): Boolean = dismissedByTouch

    // ------------------------------------------------------------------------ the view

    private fun build(prefs: Prefs): FrameLayout? = runCatching {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val face = typeface()

        val frame = FrameLayout(context).apply {
            background = ColorDrawable(Color.BLACK)
        }

        wallpaper(prefs)?.let { frame.addView(it) }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Top bar: network on the left, next alarm and battery on the right.
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val net = label(face, dp(0)).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val alarmView = label(face, dp(0))
        val battery = label(face, dp(0)).apply { setPadding(dp(12), 0, 0, 0) }
        bar.addView(net)
        bar.addView(alarmView)
        bar.addView(battery)
        column.addView(bar)

        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val time = TextView(context).apply {
            typeface = face
            setTextColor(Color.WHITE)
            textSize = 68f
            gravity = Gravity.CENTER
        }
        val day = label(face, dp(0)).apply { gravity = Gravity.CENTER }
        val noteList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(26), 0, 0)
        }
        middle.addView(time)
        middle.addView(day)
        middle.addView(noteList)
        column.addView(middle)

        val hint = TextView(context).apply {
            typeface = face
            setTextColor(Color.WHITE)
            textSize = 15f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            // The true instruction on this phone, and the reason the whole thing was rebuilt as a
            // window: the sensor really is live behind this.
            text = "PRESS THE POWER BUTTON"
        }
        val hintSub = label(face, 0).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            text = "or tap to reach the keypad"
        }
        column.addView(hint)
        column.addView(hintSub)

        frame.addView(column)

        // One tap, one job: get out of the way. Nothing here dismisses the keyguard or asks it for
        // anything — the real lock screen is already behind this window, so hiding is enough.
        var downY = 0f
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = event.rawY
                MotionEvent.ACTION_UP -> {
                    val travelled = abs(event.rawY - downY)
                    if (travelled < dp(24) || event.rawY < downY) {
                        dismissedByTouch = true
                        hide()
                    }
                }
            }
            true
        }

        clock = time
        date = day
        status = net
        alarm = alarmView
        notes = noteList
        battery.tag = BATTERY_TAG
        frame
    }.getOrNull()

    private fun label(face: Typeface?, pad: Int) = TextView(context).apply {
        typeface = face
        setTextColor(DIM)
        textSize = 11f
        letterSpacing = 0.12f
        maxLines = 1
        setPadding(0, pad, 0, 0)
    }

    /** LightOS ships Akkurat; matching it is what stops this looking like a different phone. */
    private fun typeface(): Typeface? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
        android.graphics.fonts.SystemFonts.getAvailableFonts()
            .firstOrNull { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
            ?.file
            ?.let { Typeface.createFromFile(it) }
    }.getOrNull()

    /**
     * The chosen picture, desaturated and dimmed.
     *
     * Downsampled in two passes: a phone photo is 12 megapixels against a panel under one, and
     * decoding at full size to draw it scaled is tens of megabytes for pixels nobody sees. An
     * OutOfMemory on the lock screen is the worst possible place for one.
     */
    private fun wallpaper(prefs: Prefs): ImageView? {
        val raw = prefs.lockImage?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val metrics = context.resources.displayMetrics
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > metrics.widthPixels * 2) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            } ?: return null

            ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                // The panel is greyscale and matte, so colour arrives as mid-greys whatever we do.
                // Converting deliberately means choosing which greys; the alpha keeps a bright
                // photograph from swallowing the clock.
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                imageAlpha = 110
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------------ contents

    private fun startTicking() {
        if (tickerOn) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            context.registerReceiver(ticker, filter)
            tickerOn = true
        }
    }

    private fun stopTicking() {
        if (!tickerOn) return
        tickerOn = false
        runCatching { context.unregisterReceiver(ticker) }
    }

    private fun refresh() {
        val frame = root ?: return
        val now = System.currentTimeMillis()
        clock?.text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(now))
        date?.text = SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
            .format(Date(now)).uppercase()
        status?.text = network()
        alarm?.text = nextAlarm()?.let { "ALARM $it" }.orEmpty()
        frame.findViewWithTag<TextView>(BATTERY_TAG)?.text = battery()
        fillNotes()
    }

    private fun fillNotes() {
        val list = notes ?: return
        list.removeAllViews()
        val face = typeface()
        val d = context.resources.displayMetrics.density
        val current = LockNotes.notes.value
        current.take(MAX_NOTES).forEach { note ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (7 * d).toInt(), 0, (7 * d).toInt())
            }
            row.addView(label(face, 0).apply { text = note.app.uppercase() })
            val headline = note.title.ifBlank { note.text }
            if (headline.isNotBlank()) {
                row.addView(
                    TextView(context).apply {
                        typeface = face
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        maxLines = 1
                        text = headline
                    },
                )
            }
            // The body only when there is a title above it, so a one-line notification is not
            // printed twice.
            if (note.title.isNotBlank() && note.text.isNotBlank()) {
                row.addView(
                    TextView(context).apply {
                        typeface = face
                        setTextColor(DIM)
                        textSize = 13f
                        maxLines = 2
                        text = note.text
                    },
                )
            }
            list.addView(row)
        }
        if (current.size > MAX_NOTES) {
            list.addView(label(face, (8 * d).toInt()).apply {
                text = "+${current.size - MAX_NOTES} MORE"
            })
        }
        list.visibility = if (current.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Deliberately not signal bars. Those need `READ_PHONE_STATE`, a runtime permission LightOS
     * has no screen to grant, and a bar count that silently reads empty on a phone with full
     * signal is worse than no bars at all. This asks the question that matters — is anything
     * going to arrive — and needs nothing.
     */
    private fun network(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "NO SERVICE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                context.getSystemService(TelephonyManager::class.java)
                    ?.networkOperatorName.orEmpty().trim().ifEmpty { "CELLULAR" }.uppercase()
            else -> "NO SERVICE"
        }
    }.getOrDefault("NO SERVICE")

    private fun battery(): String = runCatching {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        if (level in 0..100) {
            if (charging) "CHARGING · $level%" else "$level%"
        } else {
            ""
        }
    }.getOrDefault("")

    private fun nextAlarm(): String? = runCatching {
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            ?.let { SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(it.triggerTime)) }
    }.getOrNull()

    private companion object {
        val DIM = Color.rgb(0x9A, 0x9A, 0x9A)
        const val MAX_NOTES = 4
        const val BATTERY_TAG = "lock_battery"
    }
}
