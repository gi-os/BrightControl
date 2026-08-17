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
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
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

    /** LightOS's own type scale and grid. Never a hardcoded sp or dp on this screen. */
    private val type = LightType(context)

    private var root: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var bars: SignalBars? = null
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

    /**
     * Take it down — on unlock, or when the service goes away.
     *
     * The reference is dropped **after** the removal succeeds, not before. Nulling first and then
     * failing to remove leaves a full-screen window on the phone with nothing left holding a
     * handle to it, which is a lock screen you cannot get rid of without a reboot. `removeView`
     * is asynchronous and can be refused; `removeViewImmediate` is the one that cannot be put off,
     * so it is what the retry uses.
     */
    fun hide(): Boolean {
        stopTicking()
        val view = root ?: return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val gone = runCatching { wm.removeView(view); true }
            .getOrElse { runCatching { wm.removeViewImmediate(view); true }.getOrDefault(false) }
        if (!gone) return false
        root = null
        clock = null
        date = null
        bars = null
        alarm = null
        notes = null
        return true
    }

    /** True once a tap has put it away, so a re-show on screen-on does not fight the user. */
    fun dismissed(): Boolean = dismissedByTouch

    // ------------------------------------------------------------------------ the view

    private fun build(prefs: Prefs): FrameLayout? = runCatching {
        val frame = FrameLayout(context).apply {
            background = ColorDrawable(Color.BLACK)
        }

        wallpaper(prefs)?.let { frame.addView(it) }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // One grid unit in from each side, three units of top bar, four of bottom — the SDK's
            // own figures, so this screen lines up with every Light tool on the phone.
            setPadding(type.gridPx(1f), type.gridPx(1.5f), type.gridPx(1f), type.gridPx(3f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Top bar: signal bars on the left, next alarm and battery on the right. The carrier name
        // used to sit where the bars are and was the wrong answer to the question a status bar is
        // asked — "T-MOBILE" never changes and does not tell you whether anything will arrive.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val signal = SignalBars(context).apply {
            layoutParams = LinearLayout.LayoutParams(type.gridPx(2.2f), type.gridPx(1.1f))
        }
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val alarmView = barLabel()
        val battery = barLabel().apply { setPadding(type.gridPx(0.7f), 0, 0, 0) }
        bar.addView(signal)
        bar.addView(spacer)
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
            typeface = type.light
            setTextColor(Color.WHITE)
            textSize = type.title
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        // Sentence case and written the way a person says it — "Sunday, August 16" rather than
        // SUNDAY 16 AUGUST. The tracked all-caps label is right for a status bar and wrong for a
        // date, which is the one thing on this screen that is read as words.
        val day = TextView(context).apply {
            typeface = type.regular
            setTextColor(DIM)
            textSize = type.paragraph
            gravity = Gravity.CENTER
        }
        val noteList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, type.gridPx(2f), 0, 0)
        }
        middle.addView(time)
        middle.addView(day)
        middle.addView(noteList)
        column.addView(middle)

        // `detail`, not `button`. This is a caption telling you the sensor is live, not a control
        // to press — sized like one it shouted over the clock.
        val hint = TextView(context).apply {
            typeface = type.medium
            setTextColor(DIM)
            textSize = type.detail
            letterSpacing = type.buttonTracking
            gravity = Gravity.CENTER
            // The true instruction on this phone, and the reason the whole thing was rebuilt as a
            // window: the sensor really is live behind this.
            text = "PRESS THE POWER BUTTON"
        }
        val hintSub = TextView(context).apply {
            typeface = type.regular
            setTextColor(DIM)
            textSize = type.superfine
            gravity = Gravity.CENTER
            setPadding(0, type.gridPx(0.35f), 0, 0)
            text = "or tap for the keypad"
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
                    if (travelled < type.gridPx(2f) || event.rawY < downY) {
                        dismissedByTouch = true
                        hide()
                    }
                }
            }
            true
        }

        clock = time
        date = day
        bars = signal
        alarm = alarmView
        notes = noteList
        battery.tag = BATTERY_TAG
        frame
    }.getOrNull()

    /** `superfine` — the top bar is glanced at, not read. */
    private fun barLabel(pad: Int = 0) = TextView(context).apply {
        typeface = type.medium
        setTextColor(DIM)
        textSize = type.superfine
        letterSpacing = type.subheadingTracking
        maxLines = 1
        setPadding(0, pad, 0, 0)
    }

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
        date?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now))
        bars?.level = signalLevel()
        alarm?.text = nextAlarm()?.let { "ALARM $it" }.orEmpty()
        frame.findViewWithTag<TextView>(BATTERY_TAG)?.text = battery()
        fillNotes()
    }

    private fun fillNotes() {
        val list = notes ?: return
        list.removeAllViews()
        val current = LockNotes.notes.value
        val pad = type.gridPx(0.55f)
        // The SDK's list row is `copy` over `detail`; the app name above it is the small tracked
        // label the rest of the phone uses for a section.
        current.take(MAX_NOTES).forEach { note ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
            }
            row.addView(
                TextView(context).apply {
                    typeface = type.medium
                    setTextColor(DIM)
                    textSize = type.superfine
                    letterSpacing = type.buttonTracking
                    maxLines = 1
                    text = note.app.uppercase()
                },
            )
            val headline = note.title.ifBlank { note.text }
            if (headline.isNotBlank()) {
                row.addView(
                    TextView(context).apply {
                        typeface = type.regular
                        setTextColor(Color.WHITE)
                        textSize = type.copy
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
                        typeface = type.regular
                        setTextColor(DIM)
                        textSize = type.detail
                        maxLines = 2
                        text = note.text
                    },
                )
            }
            list.addView(row)
        }
        if (current.size > MAX_NOTES) {
            list.addView(barLabel(pad).apply { text = "+${current.size - MAX_NOTES} MORE" })
        }
        list.visibility = if (current.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * How many bars, 0 to 4, or -1 for "no idea".
     *
     * Cellular strength needs `READ_PHONE_STATE`, which is a runtime permission LightOS has no
     * screen to grant, so it is granted once over adb and its absence is *drawn* rather than
     * guessed at: four empty outlines say "not known" and cannot be misread as "no signal", which
     * is what silently returning zero would have done.
     *
     * Wi-Fi is read off the network's own RSSI, which needs nothing. When even that is unavailable
     * the bars are filled, because a phone with a working Wi-Fi connection has, for every purpose
     * this bar serves, signal.
     */
    private fun signalLevel(): Int = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return 0

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val rssi = caps.signalStrength
            if (rssi > MIN_SANE_RSSI && rssi < 0) {
                val wifi = context.getSystemService(WifiManager::class.java)
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi != null) {
                    val max = wifi.maxSignalLevel.coerceAtLeast(1)
                    (wifi.calculateSignalLevel(rssi) * SignalBars.BARS / max)
                        .coerceIn(1, SignalBars.BARS)
                } else {
                    SignalBars.BARS
                }
            }
            return SignalBars.BARS
        }

        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return 0

        val granted = context.checkSelfPermission(
            android.Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return -1

        val tm = context.getSystemService(TelephonyManager::class.java) ?: return -1
        // `level` is already 0..4 on every Android since 29 — the same scale the system's own
        // status bar draws, so the bars agree with the ones above the notification shade.
        tm.signalStrength?.level ?: -1
    }.getOrDefault(-1)

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
        /** The SDK's `contentSecondary`. Three colours in LightOS, and this is the third. */
        val DIM = Color.rgb(0xBB, 0xBB, 0xBB)
        const val MAX_NOTES = 4
        const val BATTERY_TAG = "lock_battery"

        /**
         * Below this, a reported RSSI is not a reading.
         *
         * `NetworkCapabilities.signalStrength` answers `Integer.MIN_VALUE` when it has nothing,
         * and a real Wi-Fi RSSI never goes below about -100 dBm. Anything under this is the
         * absence of a number rather than a very weak signal.
         */
        const val MIN_SANE_RSSI = -127
    }
}
