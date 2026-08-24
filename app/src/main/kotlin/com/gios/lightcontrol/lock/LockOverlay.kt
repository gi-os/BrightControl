package com.gios.lightcontrol.lock

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 * Touches, so a **swipe up** can get out of the way when you want the keypad. That is all it does:
 * hide this window, revealing the real lock screen already behind it. It never asks for the bouncer
 * and never dismisses anything. A tap is deliberately inert — this covers the whole panel, and a
 * phone in a pocket presses the whole panel.
 */
class LockOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** LightOS's own type scale and grid. Never a hardcoded sp or dp on this screen. */
    private val type = LightType(context)

    /** How far up counts as meaning it. Four grid units — a flick, not a graze. */
    private val swipeThreshold: Int get() = type.gridPx(4f)

    private var root: FrameLayout? = null
    private var face: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var bars: SignalBars? = null
    private var batteryIcon: BatteryIcon? = null
    private var alarm: TextView? = null
    private var notes: LinearLayout? = null
    private var enterHint: TextView? = null
    private var progressLine: View? = null

    /** Set true on unlock; a press-and-hold then goes in. Reset every lock cycle. */
    private var enterArmed = false
    private var holdAnimator: android.animation.ValueAnimator? = null

    /**
     * Told when a completed hold means "go in", set by the service.
     *
     * The face has no idea where to resume to -- that is the service's list and snapshot -- so the
     * hold gesture only reports that it happened and the service decides where it lands.
     */
    var onEnter: (() -> Unit)? = null

    /** Hidden by a tap, and left hidden until the next sleep. */
    private var dismissedByTouch = false

    /**
     * The decoded picture, kept between lock cycles.
     *
     * The face is rebuilt every time the phone sleeps, and decoding a photograph every time is a
     * few megabytes of allocation several dozen times a day for a picture that has not changed.
     * Keyed on the URI so choosing a new one still takes effect.
     */
    private var cached: android.graphics.Bitmap? = null
    private var cachedStamp: Long = -1L

    val showing: Boolean get() = root != null

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            runCatching { refresh() }
        }
    }
    private var tickerOn = false

    /**
     * The panel just lit. Hold the black, then fade the face in.
     *
     * The half second of nothing is the entire point, and it is there for the fastest unlock rather
     * than for the look of it: pressing the power button with the thumb already on the sensor opens
     * the phone in a couple of hundred milliseconds, and for that whole gesture the correct thing
     * to show is what a phone that is *about to be open* shows — nothing. Painting a lock screen
     * and taking it away again a moment later is a flicker the user reads as a fault, even though
     * everything worked.
     *
     * So the content is only worth drawing for someone who did *not* unlock immediately, and this
     * wait is how it finds out which happened. A thumb that landed first takes the whole window
     * down before the fade begins, and nothing was ever seen.
     */
    fun wake() {
        val content = face ?: return
        content.animate().cancel()
        content.alpha = 0f
        handler.removeCallbacks(fadeIn)
        handler.postDelayed(fadeIn, HOLD_DARK_MS)
    }

    private val fadeIn = Runnable {
        face?.animate()?.alpha(1f)?.setDuration(FADE_MS)?.start()
    }

    private val holdEnter = Runnable {
        // Fires a full second in, while the finger is still down. The service launches and takes
        // the window down; nothing here needs to.
        runCatching { onEnter?.invoke() }
    }

    /**
     * The phone is open -- now wait for a deliberate hold rather than launching on the unlock.
     *
     * This reverses the old contract. Before this, the poll that saw the keyguard unlock also
     * opened the resume app in the same instant, so the notifications on this face were never read.
     * Now the face stays up and readable, says how to go in, and enters only when [holdEnter]
     * completes. Called by the service the moment it sees the phone unlock.
     */
    fun armEnter() {
        // Idempotent: the unlock arrives on three signals at once (poll, keyguard listener,
        // USER_PRESENT), and re-running this mid-hold would snap the progress line back to zero.
        if (enterArmed) return
        enterArmed = true
        handler.post {
            enterHint?.visibility = View.VISIBLE
            resetProgress()
        }
    }

    private fun startHold() {
        handler.removeCallbacks(holdEnter)
        handler.postDelayed(holdEnter, HOLD_ENTER_MS)
        val line = progressLine ?: return
        holdAnimator?.cancel()
        val full = type.gridPx(10f)
        holdAnimator = android.animation.ValueAnimator.ofInt(0, full).apply {
            duration = HOLD_ENTER_MS
            addUpdateListener { anim ->
                line.layoutParams = line.layoutParams.apply { width = anim.animatedValue as Int }
                line.requestLayout()
            }
            start()
        }
    }

    private fun cancelHold() {
        handler.removeCallbacks(holdEnter)
        resetProgress()
    }

    private fun resetProgress() {
        holdAnimator?.cancel()
        holdAnimator = null
        progressLine?.let {
            it.layoutParams = it.layoutParams.apply { width = 0 }
            it.requestLayout()
        }
    }

    /** Back to a fresh, un-armed face. Called at the start of every lock cycle and on teardown. */
    private fun resetEnter() {
        enterArmed = false
        handler.removeCallbacks(holdEnter)
        enterHint?.visibility = View.GONE
        resetProgress()
    }

    /**
     * Put the face up.
     *
     * Called as the screen goes off, so all of the work below — including decoding a photograph —
     * happens against a panel nobody is looking at.
     */
    fun show(prefs: Prefs) {
        dismissedByTouch = false
        if (root != null) {
            // Back to black. The phone is asleep at this point, so nothing is lost, and it means a
            // window that survived a lock cycle wakes the same way a fresh one does.
            handler.removeCallbacks(fadeIn)
            face?.animate()?.cancel()
            face?.alpha = 0f
            resetEnter()
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
                runCatching { refresh() }
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
        handler.removeCallbacks(fadeIn)
        handler.removeCallbacks(holdEnter)
        holdAnimator?.cancel()
        holdAnimator = null
        enterArmed = false
        face?.animate()?.cancel()
        val view = root ?: return true
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val gone = runCatching { wm.removeView(view); true }
            .getOrElse { runCatching { wm.removeViewImmediate(view); true }.getOrDefault(false) }
        if (!gone) return false
        root = null
        face = null
        enterHint = null
        progressLine = null
        clock = null
        date = null
        bars = null
        alarm = null
        notes = null
        return true
    }

    /** True once a swipe has put it away, so a re-show on screen-on does not fight the user. */
    fun dismissed(): Boolean = dismissedByTouch

    /**
     * Get out of the way for something the user deliberately opened, and stay out.
     *
     * The camera button is the case this exists for. Pressing it while the phone is locked starts
     * the camera *behind* this window — layer 31 is above everything, including an app that has
     * come to the front — so the shutter worked, the photos were taken, and the viewfinder was
     * never visible. A face that covers the app you just asked for is worse than no face.
     *
     * Sticky like a swipe, and for the same reason: coming back on the next screen-on would put it
     * over the viewfinder a second later.
     */
    fun dismiss() {
        dismissedByTouch = true
        hide()
    }

    // ------------------------------------------------------------------------ the view

    private fun build(prefs: Prefs): FrameLayout? = runCatching {
        val frame = FrameLayout(context).apply {
            background = ColorDrawable(Color.BLACK)
        }

        // Everything except the black sits in one layer so it can be faded as a unit. The window
        // itself stays opaque black throughout — what fades in is the picture and the clock, over
        // a panel that was already dark.
        val content = FrameLayout(context).apply {
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        frame.addView(content)

        wallpaper(prefs)?.let { content.addView(it) }

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
        // Same box as the signal bars: two status glyphs of different heights read as one
        // being more important than the other, which is not true.
        val battery = BatteryIcon(context).apply {
            layoutParams = LinearLayout.LayoutParams(type.gridPx(2.2f), type.gridPx(1.1f)).apply {
                marginStart = type.gridPx(0.7f)
            }
        }
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
            text = "swipe up for the keypad"
        }
        if (prefs.lockPrompt) {
            column.addView(hint)
            column.addView(hintSub)
        }

        // Shown only after the phone unlocks (armEnter). Tells the user the face is now theirs to
        // read, and that going in takes a deliberate hold -- not the pocket-proof swipe, a hold.
        val enter = TextView(context).apply {
            typeface = type.medium
            setTextColor(Color.WHITE)
            textSize = type.detail
            letterSpacing = type.buttonTracking
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, type.gridPx(0.6f), 0, type.gridPx(0.4f))
            text = "HOLD TO ENTER  ·  SWIPE UP FOR KEYPAD"
        }
        // The hold's progress, drawn as a line that fills over the second. Width 0 at rest; the
        // hold animator grows it, a lift or a swipe snaps it back. Feedback the sensor never gave.
        val progress = View(context).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, maxOf(2, type.gridPx(0.12f))).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        column.addView(enter)
        column.addView(progress)

        content.addView(column)

        // **A tap does nothing.** This window covers the whole panel, and a phone in a pocket
        // presses its whole panel — a face that got out of the way on any touch is a face that
        // spends the day out of the way, and then the picture is a thing you see only when you
        // meant to see something else. So the gesture has to be one nothing does by accident:
        // a deliberate upward drag, which is also the gesture the lock screen underneath already
        // answers to.
        var downY = 0f
        var downX = 0f
        frame.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downX = event.rawX
                    // Only after the phone is unlocked does a hold mean anything. Before that the
                    // keyguard behind us is what a press has to reach.
                    if (enterArmed) startHold()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (enterArmed) {
                        val moved = abs(event.rawY - downY) + abs(event.rawX - downX)
                        // This is turning into a swipe, not a hold -- let the swipe win.
                        if (moved > swipeThreshold) cancelHold()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cancelHold()
                    val up = downY - event.rawY
                    val sideways = abs(event.rawX - downX)
                    // Up, far enough to be meant, and more up than across.
                    if (up > swipeThreshold && up > sideways) {
                        // Remembered, so screen-on does not raise it again. Swiping to reach the
                        // keypad and having the face come straight back would make the keypad
                        // unreachable, which is the one bug this feature must never have.
                        dismissedByTouch = true
                        hide()
                    }
                }
                MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            true
        }

        face = content
        enterHint = enter
        progressLine = progress
        clock = time
        date = day
        bars = signal
        alarm = alarmView
        notes = noteList
        batteryIcon = battery
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
     * The finished background, rendered through the editor's filter stack.
     *
     * No color matrix and no fixed alpha any more. v2.5 desaturated the photo and dropped it to
     * 55% because that is the one setting that works for every picture and is right for none;
     * what replaced it is [LockBackground], where the recipe is the user's — dither it to halftone,
     * fade the corners into the black, or leave it alone.
     *
     * Rendered once and kept, keyed on [Prefs.lockBackgroundStamp]. Three passes over a million
     * pixels on every sleep for an image nobody has touched is real work; doing it never would mean
     * an edit that does not appear until reboot.
     */
    private fun wallpaper(prefs: Prefs): ImageView? {
        val stamp = prefs.lockBackgroundStamp
        cached?.takeIf { cachedStamp == stamp && !it.isRecycled }?.let { return imageView(it) }

        val metrics = context.resources.displayMetrics
        val aspect = metrics.widthPixels.toFloat() / metrics.heightPixels
        val bitmap = LockBackground.render(context, prefs, aspect, metrics.widthPixels)
            ?: return null
        cached = bitmap
        cachedStamp = stamp
        return imageView(bitmap)
    }

    private fun imageView(bitmap: android.graphics.Bitmap): ImageView =
        ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

    // ------------------------------------------------------------------------ contents

    private fun startTicking() {
        if (tickerOn) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            // Both actions are protected system broadcasts, so the export flag is not strictly
            // required — passed anyway, because "not exported" is the true answer and the default
            // has changed once already.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(ticker, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(ticker, filter)
            }
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
        clock?.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(now))
        date?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now))
        bars?.level = signalLevel()
        alarm?.text = nextAlarm()?.let { "ALARM $it" }.orEmpty()
        batteryIcon?.let { icon ->
            icon.level = batteryLevel()
            icon.charging = batteryCharging()
        }
        fillNotes()
    }

    private fun fillNotes() {
        val list = notes ?: return
        // Wrapped by every caller, but named here too: this runs on a broadcast, on the main
        // thread, behind the lock screen. A throw here is the phone appearing to freeze.
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

    /** 0..100, or -1 when the platform will not say -- which the icon draws as an empty shell. */
    private fun batteryLevel(): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: -1
    }.getOrDefault(-1)

    private fun batteryCharging(): Boolean = runCatching {
        context.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    private fun nextAlarm(): String? = runCatching {
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
            ?.let { SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(it.triggerTime)) }
    }.getOrNull()

    private companion object {
        /** The SDK's `contentSecondary`. Three colors in LightOS, and this is the third. */
        val DIM = Color.rgb(0xBB, 0xBB, 0xBB)
        /**
         * How long the panel stays black before the face appears. See [wake].
         *
         * Long enough to cover a thumb already on the sensor when the button went down, short
         * enough that someone who meant to *look* at the phone is not left wondering whether it
         * woke. Half a second is both.
         */
        const val HOLD_DARK_MS = 500L

        /** The fade itself. Slow enough to read as arriving, not as a repaint. */
        const val FADE_MS = 320L

        const val MAX_NOTES = 4

        /** How long a press-and-hold on the unlocked face must last to go in. */
        const val HOLD_ENTER_MS = 1000L

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
