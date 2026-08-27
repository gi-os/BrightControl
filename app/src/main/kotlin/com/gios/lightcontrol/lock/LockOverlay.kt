package com.gios.lightcontrol.lock

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

    /**
     * How far left a row has to be pushed before letting go dismisses it.
     *
     * Longer than a flick and shorter than the panel: five grid units is far enough that the
     * gesture cannot be a wobble on the way to the power button, and near enough that a thumb
     * reaches it without a second grab.
     */
    private val swipeAwayThreshold: Int get() = type.gridPx(5f)

    private var root: FrameLayout? = null
    private var face: FrameLayout? = null
    private var clock: TextView? = null
    private var date: TextView? = null
    private var bars: SignalBars? = null
    private var batteryIcon: BatteryIcon? = null
    private var alarm: TextView? = null
    private var notes: LockNoteList? = null
    private var enterHint: TextView? = null
    private var progressLine: View? = null

    // ---- now playing. See [LockMedia] for why the player cannot draw this itself.
    private val media = LockMedia(context)
    private var mediaRow: LinearLayout? = null
    private var mediaArt: ImageView? = null
    private var mediaTitle: TextView? = null
    private var mediaArtist: TextView? = null
    private var mediaPlay: MediaGlyph? = null
    private var mediaLeft: MediaGlyph? = null
    private var mediaRight: MediaGlyph? = null

    // ---- the call card. See [LockCall] for why the face has to draw this itself.
    private var callRow: LinearLayout? = null
    private var callLabel: TextView? = null
    private var callWho: TextView? = null
    private var callSub: TextView? = null
    private var callAnswer: TextView? = null
    private var callDecline: TextView? = null
    private var callState: LockCallState? = null

    /**
     * The two buttons on the call card, reported rather than acted on.
     *
     * Same seam as everything else here: the window draws, the service does. Answering a call from
     * a service that already holds the notification listener is one call; answering it from a
     * window that may be torn down by the same event is a race.
     */
    var onAnswerCall: (() -> Unit)? = null
    var onDeclineCall: (() -> Unit)? = null

    /**
     * Asked to open the player, with its package.
     *
     * The face never starts an activity. Same seam as [onEnter] and for the same reason: every
     * launch in this app goes through the service's one throttle, its one log line and its cover
     * handling, and a window that started its own would sit outside all three.
     */
    var onOpenPlayer: ((String) -> Unit)? = null

    /**
     * A notification was swiped off the face, with its key.
     *
     * Same seam as every other verb here: the window draws and reports, the service acts. The
     * cancel itself is one call to the bound listener ([LockNotes.dismiss]) and it goes through
     * the service so it lands in the same log as everything else the face does.
     */
    var onDismissNote: ((String) -> Unit)? = null

    init {
        // The row is driven by the session, not by the minute ticker -- a track changes when it
        // changes, and repainting the clock is no reason to redraw a cover.
        media.onChange = { track -> runCatching { renderMedia(track) } }
    }

    /** Set true on unlock; a press-and-hold then goes in. Reset every lock cycle. */
    private var enterArmed = false

    /** True once unlocked and holding open for a read — the window a home press means "go in". */
    val armed: Boolean get() = enterArmed
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
     * Rows swiped away in the last second, filtered out until the listener catches up.
     *
     * `cancelNotification` is a request, not a removal: the row goes when the platform tells the
     * listener it went, which is a round trip through another process. Without this the row
     * sprang back under the finger for a frame or two and the swipe read as having failed. Cleared
     * as each key stops appearing in the real list, so nothing is ever hidden on the strength of
     * a cancel that did not happen.
     */
    private val dismissed = mutableSetOf<String>()

    /**
     * The track whose row was swiped away, if any.
     *
     * Swiping the player off the face is not a transport command — the music keeps playing, this
     * is the card being put away. It stays away for that track and comes back the moment the
     * session has something new to say: a different track, or play pressed again in the app, which
     * is what asking for the player back looks like from here.
     */
    private var mediaHiddenKey: String? = null

    /** Last known play state, for spotting the press that un-hides the row. */
    private var mediaWasPlaying = false

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

    /**
     * Show the content now, without the half-second of black [wake] holds.
     *
     * That delay exists for a phone being picked up: a thumb already on the power button unlocks
     * inside it, and the face is taken down before anything is drawn. A ringing phone is the
     * opposite case — nobody is unlocking it, they are looking at it to see who it is — so the
     * card fades straight in.
     */
    fun reveal() {
        val content = face ?: return
        handler.removeCallbacks(fadeIn)
        content.animate().cancel()
        content.animate().alpha(1f).setDuration(FADE_MS).start()
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
        // No text furniture. The clock, the notifications and nothing else -- the hold's feedback
        // is the progress line filling, not a caption. enterHint is kept GONE.
        handler.post { resetProgress() }
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
        // A new lock cycle re-asks the platform what is in the shade, so last cycle's optimism is
        // worth nothing and holding it would hide a notification that had come back.
        dismissed.clear()
        // The face repaints when the shade changes, not a minute later: a message arriving at
        // 10:00:05 used to be on screen at 10:01, and a row swiped away sat there until the same
        // tick. Registered with the face and dropped in [hide], so nothing posts to a main thread
        // on behalf of a window that is not up. Set before the early return below, because a
        // window that survived the last cycle is up and still needs telling.
        LockNotes.onChange = { handler.post { runCatching { fillNotes() } } }
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
                // Only when the row was actually built. Started here rather than in the
                // constructor because the listener it registers outlives the window otherwise,
                // and a session callback firing all day for a face that is not up is exactly the
                // battery bug the screen-on poll already had once.
                if (mediaRow != null) media.start()
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
        media.stop()
        LockNotes.onChange = null
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
        mediaRow = null
        mediaArt = null
        mediaTitle = null
        mediaArtist = null
        mediaPlay = null
        callRow = null
        callLabel = null
        callWho = null
        callSub = null
        callAnswer = null
        callDecline = null
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
        val frame = LockFrame(context).apply {
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
        // Owned by the list rather than added and removed with the rows, because the list is
        // what knows how many notifications did not make it onto the screen. See [LockNoteList].
        val more = barLabel(type.gridPx(0.55f))
        val noteList = LockNoteList(context, more).apply {
            setPadding(0, type.gridPx(2f), 0, 0)
        }
        middle.addView(time)
        middle.addView(day)
        // Above the shade and under the clock. A ringing phone is the most important thing this
        // screen can be saying, and it is the one thing on it with buttons.
        middle.addView(buildCall())
        middle.addView(noteList)
        column.addView(middle)

        // Under the clock and the shade, above the hints. The foot of the screen is where a phone
        // puts what is playing, and it is also the only place a control can go without the notes
        // shifting position every time a song starts.
        if (prefs.lockMedia) column.addView(buildMedia())

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
        // meant to see something else. So every gesture here has to be one nothing does by
        // accident: a deliberate drag, up for the keypad or left to dismiss a row. See
        // [LockFrame], which is where all of it is read.

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

    // ------------------------------------------------------------------------ the gestures

    /**
     * Every gesture the face answers to, read in one place.
     *
     * Three of them, and each one has to be impossible to perform by accident, because this window
     * covers the whole panel and a phone in a pocket presses the whole panel:
     *
     *  - **Up** — put the face away and show the keypad underneath.
     *  - **Left, on a row** — dismiss that notification, or put the player's card away.
     *  - **Press and hold, once unlocked** — go in. See [startHold].
     *
     * ### Why this intercepts
     *
     * The row of media buttons and the track title are clickable children, and a clickable child
     * takes the whole gesture from `ACTION_DOWN`: before this class the parent never saw a drag
     * that began on one, so swiping right on the player did nothing and swiping up from the title
     * did not reach the keypad. `onInterceptTouchEvent` is the standard answer — children keep
     * their taps, and the moment a press turns into a drag the parent takes it over and the child
     * is sent an `ACTION_CANCEL`, which is exactly right: a drag was never a tap.
     *
     * The axis is locked once, at the first movement past the touch slop, and never revisited. A
     * gesture that changes its mind halfway is a gesture that dismisses a notification on the way
     * to the keypad.
     */
    private inner class LockFrame(context: Context) : FrameLayout(context) {

        /** The system's own idea of a tremor. Smaller than any threshold here, deliberately. */
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private var downX = 0f
        private var downY = 0f
        private var drag = Drag.NONE

        /** The row under the finger, while it is being pushed. */
        private var target: View? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                // Always delivered here first, whatever a child goes on to do with it, which is
                // what makes this the one reliable place to record where a gesture began.
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (recognise(ev)) return true
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                // Only reached when no child took the press. Which is the old rule, kept: a press
                // on the skip button is not the beginning of a hold-to-enter.
                MotionEvent.ACTION_DOWN -> {
                    begin(ev)
                    // Only after the phone is unlocked does a hold mean anything. Before that the
                    // keyguard behind us is what a press has to reach.
                    if (enterArmed) startHold()
                }
                MotionEvent.ACTION_MOVE -> {
                    recognise(ev)
                    if (drag == Drag.SIDEWAYS) push(ev.rawX - downX)
                }
                MotionEvent.ACTION_UP -> {
                    cancelHold()
                    finish(ev)
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    settle()
                }
            }
            return true
        }

        private fun begin(ev: MotionEvent) {
            downX = ev.rawX
            downY = ev.rawY
            drag = Drag.NONE
            settle()
        }

        /**
         * Which gesture this is, decided once and kept. True once there is one to take over.
         *
         * A sideways drag that started over nothing dismissable is [Drag.DEAD] rather than falling
         * through to the swipe up: the finger has already committed to an axis, and reading a
         * lazy diagonal as "keypad" is how a face disappears when somebody meant to wipe a row.
         */
        private fun recognise(ev: MotionEvent): Boolean {
            if (drag != Drag.NONE) return true
            val dx = ev.rawX - downX
            val dy = ev.rawY - downY
            if (abs(dx) < slop && abs(dy) < slop) return false
            // This is a drag, not a hold. Whatever it turns out to be, it is not that.
            cancelHold()
            drag = if (abs(dx) > abs(dy)) {
                val row = if (dx < 0) rowAt(downX, downY) else null
                if (row == null) {
                    Drag.DEAD
                } else {
                    target = row
                    Drag.SIDEWAYS
                }
            } else {
                Drag.UPWARD
            }
            return true
        }

        /** The row follows the finger, and fades as it goes. Left only; right is not a gesture. */
        private fun push(dx: Float) {
            val row = target ?: return
            val amount = dx.coerceAtMost(0f)
            val span = (width.takeIf { it > 0 } ?: 1).toFloat()
            row.translationX = amount
            row.alpha = 1f - (-amount / span).coerceIn(0f, 1f) * 0.8f
        }

        private fun finish(ev: MotionEvent) {
            val dx = ev.rawX - downX
            val up = downY - ev.rawY
            when (drag) {
                Drag.SIDEWAYS -> {
                    val row = target
                    if (row != null && -dx > swipeAwayThreshold) away(row) else settle()
                }
                // Up, far enough to be meant. Remembered, so screen-on does not raise the face
                // again: swiping to reach the keypad and having it come straight back would make
                // the keypad unreachable, which is the one bug this feature must never have.
                Drag.UPWARD -> if (up > swipeThreshold) {
                    dismissedByTouch = true
                    hide()
                }
                else -> settle()
            }
            drag = Drag.NONE
        }

        /** Off the left-hand edge, and only then is anything actually dismissed. */
        private fun away(row: View) {
            val span = (width.takeIf { it > 0 } ?: row.width).toFloat()
            target = null
            row.animate()
                .translationX(-span)
                .alpha(0f)
                .setDuration(SWIPE_OUT_MS)
                .withEndAction {
                    // Put back before the row is either rebuilt or hidden. The media row is the
                    // same View for the life of the face, so a translation left behind here is one
                    // that is still there the next time a song starts.
                    row.translationX = 0f
                    row.alpha = 1f
                    val key = row.tag as? String
                    if (key != null) dismissNote(key) else dismissMedia()
                }
                .start()
        }

        /** Nothing was meant by it. Back where it was. */
        private fun settle() {
            val row = target ?: return
            target = null
            row.animate().translationX(0f).alpha(1f).setDuration(SWIPE_BACK_MS).start()
        }

        /**
         * What is under the point the gesture started at, or null for the rest of the face.
         *
         * Notifications first, then the player. Screen coordinates because the rows sit several
         * layouts deep and `rawX`/`rawY` are the only two numbers that mean the same thing at
         * every depth.
         */
        private fun rowAt(x: Float, y: Float): View? {
            notes?.visibleRows()?.forEach { row -> if (hits(row, x, y)) return row }
            val player = mediaRow
            if (player != null && hits(player, x, y)) return player
            return null
        }

        private fun hits(view: View, x: Float, y: Float): Boolean {
            if (view.visibility != View.VISIBLE || view.height == 0) return false
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            return x >= at[0] && x <= at[0] + view.width && y >= at[1] && y <= at[1] + view.height
        }
    }

    /**
     * Cancel it, and take the row off the face now rather than when the platform says so.
     *
     * See [LockNotes.dismiss] for why this is a real cancel and not a list of things this face has
     * decided not to show.
     */
    private fun dismissNote(key: String) {
        dismissed += key
        runCatching { fillNotes() }
        runCatching { onDismissNote?.invoke(key) }
    }

    /**
     * Put the player's card away. The music is not touched.
     *
     * A card is not a transport control: swiping it off is "not now", and stopping the audio
     * because somebody tidied the screen would be the face acting on its own. What comes back is
     * decided in [renderMedia] — a new track, or play pressed again in the app.
     */
    private fun dismissMedia() {
        mediaHiddenKey = mediaKey(media.track) ?: return
        mediaRow?.visibility = View.GONE
    }

    /** What counts as "the same thing playing", for [dismissMedia]. */
    private fun mediaKey(track: LockTrack?): String? =
        track?.let { "${it.pkg}|${it.title}|${it.artist}" }

    // ------------------------------------------------------------------------ the call card

    /**
     * A call arrived, changed, or ended. Told by the service, which owns the watcher.
     *
     * Kept in a field as well as drawn, because the face is rebuilt on every sleep and a call that
     * survives one — put down mid-conversation, picked back up — has to come back with it.
     */
    fun setCall(state: LockCallState?) {
        callState = state
        runCatching { renderCall(state) }
    }

    /**
     * The card: what is happening, who it is, and the two things you can do about it.
     *
     * Built once and hidden, like the now-playing row and for the same reason — a card inserted
     * into the column when the phone rings would shove the clock up the screen at the exact moment
     * somebody is looking at it.
     *
     * The buttons are touchable and are **not** gated on the phone being unlocked. Every phone
     * answers a call from its lock screen; a face that made you unlock first would be a face that
     * loses calls.
     */
    private fun buildCall(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, type.gridPx(1.5f), 0, type.gridPx(0.5f))
        }
        val label = TextView(context).apply {
            typeface = type.medium
            setTextColor(DIM)
            textSize = type.superfine
            letterSpacing = type.buttonTracking
            gravity = Gravity.CENTER
        }
        val who = TextView(context).apply {
            typeface = type.regular
            setTextColor(Color.WHITE)
            textSize = type.heading
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, type.gridPx(0.3f), 0, 0)
        }
        val sub = TextView(context).apply {
            typeface = type.regular
            setTextColor(DIM)
            textSize = type.detail
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, type.gridPx(1f), 0, 0)
        }
        val decline = callButton(filled = false) { runCatching { onDeclineCall?.invoke() } }
        val answer = callButton(filled = true) { runCatching { onAnswerCall?.invoke() } }
        buttons.addView(decline)
        buttons.addView(answer)

        card.addView(label)
        card.addView(who)
        card.addView(sub)
        card.addView(buttons)

        callRow = card
        callLabel = label
        callWho = who
        callSub = sub
        callAnswer = answer
        callDecline = decline
        return card
    }

    /**
     * One button, drawn rather than themed.
     *
     * This app ships no drawables and the SDK's components are Compose, which a `View` cannot take
     * — the same reason [MediaGlyph] exists. A stroked rectangle and a filled one are the whole
     * vocabulary: filled is the thing you meant to do, stroked is the other one.
     */
    private fun callButton(filled: Boolean, press: () -> Unit) = TextView(context).apply {
        typeface = type.medium
        textSize = type.button
        letterSpacing = type.buttonTracking
        gravity = Gravity.CENTER
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = type.gridPx(0.3f).toFloat()
            if (filled) setColor(Color.WHITE) else setStroke(maxOf(2, type.gridPx(0.08f)), Color.WHITE)
        }
        setPadding(0, type.gridPx(0.7f), 0, type.gridPx(0.7f))
        isClickable = true
        setOnClickListener { runCatching { press() } }
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
        ).apply {
            marginStart = type.gridPx(0.4f)
            marginEnd = type.gridPx(0.4f)
        }
    }

    /** Put the call on screen, or take the card away when there is not one. */
    private fun renderCall(state: LockCallState?) {
        val card = callRow ?: return
        if (state == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val ringing = state.stage == LockCallState.Stage.Ringing
        callLabel?.text = if (ringing) "INCOMING CALL" else "ON A CALL"
        callWho?.text = state.who
        callSub?.apply {
            text = state.sub
            visibility = if (state.sub.isBlank()) View.GONE else View.VISIBLE
        }
        // Answered, the only thing left to offer is hanging up — and it is drawn stroked, not
        // filled, because the filled button is the one somebody presses without reading it.
        callAnswer?.apply {
            text = "ANSWER"
            visibility = if (ringing) View.VISIBLE else View.GONE
        }
        callDecline?.text = if (ringing) "DECLINE" else "END"
    }

    // ------------------------------------------------------------------------ now playing

    /**
     * The now-playing row: cover, what it is, and three controls.
     *
     * Built once with the face and left `GONE` until a session says otherwise, rather than added
     * and removed as music starts and stops. A row that appears by being inserted into the column
     * moves everything above it a few pixels the moment a track begins, and on a lock screen that
     * reads as the face glitching.
     *
     * **The buttons are the only touchable things on this face.** Everything else falls through to
     * the frame's listener, which is what the swipe and the hold-to-enter are read from -- a child
     * with a click listener consumes the gesture before the frame ever sees it, so pressing skip
     * cannot half-start a hold, and dragging up from anywhere else still reaches the keypad.
     */
    private fun buildMedia(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, type.gridPx(1f), 0, type.gridPx(0.6f))
        }

        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(type.gridPx(4f), type.gridPx(4f))
            // Grey, like the rest of the face. LightOS is a three-colour phone and a colour
            // photograph the size of a stamp beside white text reads as a foreign element -- and
            // the panel is matte, so the colour was never worth much at this size anyway.
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            // Shown even with no artwork. A square that is sometimes there and sometimes not
            // moves the title, and the radio has no cover for whole shows at a time.
            setBackgroundColor(EMPTY_ART)
        }

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                marginStart = type.gridPx(0.8f)
                marginEnd = type.gridPx(0.5f)
            }
        }
        val title = TextView(context).apply {
            typeface = type.regular
            setTextColor(Color.WHITE)
            textSize = type.copy
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val artist = TextView(context).apply {
            typeface = type.regular
            setTextColor(DIM)
            textSize = type.detail
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(title)
        words.addView(artist)
        // Opening the player is a deliberate act on an unlocked phone, so it is gated on the same
        // arming as the hold: while the keyguard is still up this does nothing at all, because a
        // lock screen that launches an app on one tap is not a lock screen.
        words.setOnClickListener {
            if (!enterArmed) return@setOnClickListener
            media.track?.pkg?.let { pkg -> runCatching { onOpenPlayer?.invoke(pkg) } }
        }

        // The two outer buttons are one button each, whose mark and whose meaning both follow what
        // is playing -- see [MediaKind]. Three views built once and re-labelled beats three sets of
        // views swapped in and out: nothing is added to or removed from the row after it is built,
        // so the row cannot change width or height when a podcast follows a song.
        val left = glyph(MediaGlyph.Kind.PREVIOUS) { pressLeft() }
        val play = glyph(MediaGlyph.Kind.PLAY) { media.playPause() }
        val right = glyph(MediaGlyph.Kind.NEXT) { pressRight() }

        row.addView(cover)
        row.addView(words)
        row.addView(left)
        row.addView(play)
        row.addView(right)

        mediaRow = row
        mediaArt = cover
        mediaTitle = title
        mediaArtist = artist
        mediaPlay = play
        mediaLeft = left
        mediaRight = right
        return row
    }

    /**
     * The outer buttons, dispatched on what is playing rather than bound at build time.
     *
     * Read off [LockMedia.track] at the moment of the press, not off whatever the glyph was last
     * drawn as. The two can differ for one frame -- a track can change under a thumb already on its
     * way down -- and of the two answers, what is playing now is the one the user meant.
     */
    private fun pressLeft() = when (media.track?.kind) {
        MediaKind.SPOKEN -> media.back()
        // Nothing. A stream has no previous, and the button is not on screen to be pressed.
        MediaKind.LIVE -> Unit
        else -> media.previous()
    }

    private fun pressRight() = when (media.track?.kind) {
        MediaKind.SPOKEN -> media.forward()
        MediaKind.LIVE -> media.stopPlayback()
        else -> media.next()
    }

    /** Three grid units of tap target around one and a half of mark. See [MediaGlyph]. */
    private fun glyph(kind: MediaGlyph.Kind, press: () -> Unit) =
        MediaGlyph(context, kind, type.medium).apply {
            layoutParams = LinearLayout.LayoutParams(type.gridPx(3.2f), type.gridPx(3.2f))
            isClickable = true
            setOnClickListener { runCatching { press() } }
        }

    /**
     * Put [track] on screen, or take the row away when there is nothing playing.
     *
     * Called from the session callback and again from [refresh], so a face rebuilt on the next
     * sleep shows what is playing without waiting for the next track change.
     */
    private fun renderMedia(track: LockTrack?) {
        val row = mediaRow ?: return
        if (track == null) {
            row.visibility = View.GONE
            mediaArt?.setImageDrawable(null)
            mediaWasPlaying = false
            return
        }
        // Play pressed somewhere else -- in the app, on a speaker, on the headphones -- is
        // somebody asking for the player back, and it is the only signal for it that reaches this
        // process. A track that merely carried on playing does not qualify; this is the edge.
        if (track.playing && !mediaWasPlaying) mediaHiddenKey = null
        mediaWasPlaying = track.playing
        if (mediaHiddenKey != null && mediaKey(track) == mediaHiddenKey) {
            row.visibility = View.GONE
            return
        }
        // Anything else playing clears the hold, so the marker cannot outlive the track it was
        // set for and silence the row for something the user never swiped.
        mediaHiddenKey = null
        row.visibility = View.VISIBLE
        // A radio stream often fills only one of the two. Whichever it filled goes on the top
        // line, so the row is never a blank headline over a subtitle.
        val headline = track.title.ifBlank { track.artist }
        val second = if (track.title.isBlank()) "" else track.artist
        mediaTitle?.text = headline
        mediaArtist?.apply {
            text = second
            visibility = if (second.isBlank()) View.GONE else View.VISIBLE
        }
        mediaPlay?.show(if (track.playing) MediaGlyph.Kind.PAUSE else MediaGlyph.Kind.PLAY)
        // The controls follow the kind. A podcast gets the fifteen seconds it is always missing, a
        // stream gets a stop instead of two buttons that would do nothing, and music keeps skip.
        when (track.kind) {
            MediaKind.MUSIC -> {
                mediaLeft?.show(MediaGlyph.Kind.PREVIOUS)
                mediaLeft?.visibility = View.VISIBLE
                mediaRight?.show(MediaGlyph.Kind.NEXT)
            }
            MediaKind.SPOKEN -> {
                mediaLeft?.show(MediaGlyph.Kind.SEEK_BACK)
                mediaLeft?.visibility = View.VISIBLE
                mediaRight?.show(MediaGlyph.Kind.SEEK_FORWARD)
            }
            MediaKind.LIVE -> {
                // INVISIBLE, not GONE. The words beside it are the weighted child, so removing a
                // button from the layout would stretch the title and slide the play button across
                // the moment a station replaced a song. The gap stays; only the mark goes.
                mediaLeft?.visibility = View.INVISIBLE
                mediaRight?.show(MediaGlyph.Kind.STOP)
            }
        }
        mediaArt?.setImageBitmap(track.art)
    }

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
        renderMedia(media.track)
        renderCall(callState)
    }

    private fun fillNotes() {
        val list = notes ?: return
        // Wrapped by every caller, but named here too: this runs on a broadcast, on the main
        // thread, behind the lock screen. A throw here is the phone appearing to freeze.
        list.clearRows()
        val all = LockNotes.notes.value
        // A key the platform has stopped reporting is a cancel that landed. Dropping it here is
        // what keeps the optimistic filter from outliving the round trip it is covering for.
        dismissed.retainAll(all.mapTo(HashSet()) { it.key })
        val current = all.filter { it.key !in dismissed }
        val pad = type.gridPx(0.55f)
        // The SDK's list row is `copy` over `detail`; the app name above it is the small tracked
        // label the rest of the phone uses for a section.
        current.take(MAX_NOTES).forEach { note ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
                // What a swipe on this row dismisses. Read back off the View in [LockFrame],
                // because the row a finger landed on is found by hit test, not by index -- an
                // index would be a promise that the list has not been rebuilt since, and it is
                // rebuilt on every notification the phone receives.
                tag = note.key
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
        // Everything past MAX_NOTES was never built; the list adds to this whatever it then had to
        // drop for want of room, and puts the total on the `+N MORE` line itself.
        list.extra = (current.size - MAX_NOTES).coerceAtLeast(0)
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

        // Wi-Fi first, and asked of Wi-Fi itself rather than of whichever network happens to be
        // carrying traffic. `activeNetwork` is the *default route*, and it is cellular in every
        // situation where a Wi-Fi network is joined but not carrying the internet: a captive
        // portal that has not been signed into, a router with no upstream, the seconds during a
        // handover. In all of those the phone is on Wi-Fi, the user can see it is on Wi-Fi, and
        // the bars were quietly reporting the cell tower — which is the wrong number *and*
        // needs a permission this phone may not have granted, so the usual symptom was four
        // empty outlines on a phone with full Wi-Fi.
        wifiBars(cm)?.let { return it }

        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return 0
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

    /**
     * Bars for the Wi-Fi network, or null when there is no Wi-Fi to speak of.
     *
     * Three sources, in falling order of how much they can be trusted, because each one is
     * unavailable on some build or in some state and none of them announces that it is:
     *
     *  1. **`NetworkCapabilities.signalStrength`** of the Wi-Fi network, found across every
     *     network the phone holds rather than only the default one.
     *  2. **`WifiManager`'s own RSSI**, for builds that leave the capability unspecified.
     *  3. **Connected, strength unknown** — which is drawn as full bars. A phone with a working
     *     Wi-Fi connection has, for every purpose this glyph serves, signal; the alternative
     *     is empty outlines that read as "no signal" while pages are loading.
     *
     * Nothing here needs a permission the app does not already hold: `ACCESS_NETWORK_STATE` and
     * `ACCESS_WIFI_STATE` are both normal permissions, granted at install.
     */
    @Suppress("DEPRECATION")
    private fun wifiBars(cm: ConnectivityManager?): Int? = runCatching {
        val caps = cm?.allNetworks
            ?.asSequence()
            ?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val wifi = context.getSystemService(WifiManager::class.java)

        val capsRssi = caps?.signalStrength?.takeIf { it > MIN_SANE_RSSI && it < 0 }
        // `connectionInfo` is deprecated and still the only way to ask this on a build that
        // leaves the capability unspecified. Its SSID is redacted without location; the RSSI,
        // which is all this wants, is not.
        val wifiRssi = wifi?.connectionInfo?.rssi?.takeIf { it > MIN_SANE_RSSI && it < 0 }
        val rssi = capsRssi ?: wifiRssi

        // Is there a Wi-Fi network at all? A capability for one, or an RSSI, or the radio saying
        // it is associated. Any of the three is enough to stop reporting the cell tower.
        val associated =
            caps != null || rssi != null || (wifi?.connectionInfo?.networkId ?: -1) != -1
        if (!associated) return@runCatching null

        if (rssi == null) return@runCatching SignalBars.BARS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi != null) {
            val max = wifi.maxSignalLevel.coerceAtLeast(1)
            (wifi.calculateSignalLevel(rssi) * SignalBars.BARS / max).coerceIn(1, SignalBars.BARS)
        } else {
            SignalBars.BARS
        }
    }.getOrNull()

    /** 0..100, or -1 when the platform will not say -- which the icon draws as an empty shell. */
    private fun batteryLevel(): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?: levelFromBroadcast()
    }.getOrDefault(-1)

    /**
     * Whether the phone is on power.
     *
     * **Not `BatteryManager.isCharging`, which this used and which is not the question it looks
     * like.** That call goes to `IBatteryStats.isCharging`, and battery stats does not mean "a
     * cable is attached" by charging -- it means the run of charging it has decided to count, and
     * it applies hysteresis before saying so. Plug a phone in and it stays false for a while; plug
     * one in at full and it can stay false altogether. Those are the two moments somebody plugs a
     * phone in to leave it, so the bolt was missing exactly when it was wanted, and LightOS's own
     * status bar drew one the moment the phone was unlocked -- which reads as ours being broken.
     *
     * The sticky `ACTION_BATTERY_CHANGED` is the platform's actual answer and it is immediate:
     * `EXTRA_PLUGGED` is non-zero the instant a cable is in, and `EXTRA_STATUS` covers a dock or a
     * pad that reports charging without a plug type. `isCharging` stays underneath as a fallback
     * for a phone that hands back no sticky intent at all.
     */
    private fun batteryCharging(): Boolean = runCatching {
        val battery = batteryBroadcast()
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val onPower = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        onPower || context.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    /** The level off the same broadcast, for a phone whose `BATTERY_PROPERTY_CAPACITY` is absent. */
    private fun levelFromBroadcast(): Int = runCatching {
        val battery = batteryBroadcast() ?: return -1
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) -1 else (level * 100 / scale).coerceIn(0, 100)
    }.getOrDefault(-1)

    /**
     * The sticky battery broadcast, read rather than subscribed to.
     *
     * `registerReceiver(null, ...)` returns the last one the system sent without registering
     * anything. Asked once per repaint, which is the minute tick and every real battery change --
     * the same events that were already driving this bar.
     */
    private fun batteryBroadcast(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

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

        /**
         * The most rows worth building. What actually appears is decided by [LockNoteList], which
         * measures against the room left under the clock -- this is only the point past which
         * building more would be work for rows nothing could ever show.
         */
        const val MAX_NOTES = 6

        /** A row leaving. Quick, because the decision was already made when the finger lifted. */
        const val SWIPE_OUT_MS = 180L

        /** A row that was not pushed far enough, going back. Quicker still. */
        const val SWIPE_BACK_MS = 140L

        /** The square behind a missing cover. Dark enough to be a shape, not a hole. */
        val EMPTY_ART = Color.rgb(0x22, 0x22, 0x22)

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

/**
 * What a drag on the face turned out to mean, decided once at the first movement past the slop.
 *
 * [DEAD] is not an absence: it is a sideways drag that began over nothing dismissable, and it has
 * to be a decision rather than a fall-through, or a lazy diagonal across the middle of the screen
 * would take the face away when somebody meant to wipe a row.
 */
private enum class Drag { NONE, DEAD, SIDEWAYS, UPWARD }
