package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Behaviour
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.TurnAction
import kotlin.math.abs
import kotlin.math.min

/**
 * The wheel and the buttons, everywhere on the phone.
 *
 * An [AccessibilityService] with `flagRequestFilterKeyEvents` is the only way an app can see
 * a key it doesn't have focus for; `INJECT_EVENTS` and the rest are signature-only. The
 * service declares one event type and `canRetrieveWindowContent="false"`, so what it can
 * observe is exactly: key codes, and the package name of the app that came to the front.
 * Never a word of what's on screen.
 *
 * ### Consuming is the dangerous half
 *
 * Every key this service swallows is a key some app doesn't get, so the rule is narrow: a key
 * is consumed only when a binding for it actually does something, and never for apps whose
 * [Behaviour] is hands-off. Light's own tools resolve to hands-off, because the wheel already
 * works there and anything intercepted would be a feature removed rather than added.
 */
class ControlService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var brightness: Brightness
    private lateinit var readout: Readout

    private val handler = Handler(Looper.getMainLooper())

    /** The app in front, from window-state events. Null until the first one arrives. */
    @Volatile
    private var foreground: String? = null

    /** One press in progress per button. */
    private val presses = mutableMapOf<Button, Press>()

    private var torchOn = false

    /** Notches waiting to become one swipe, and whether a swipe is already in flight. */
    private var swipeDebt = 0f
    private var swiping = false

    /** One press in flight: what it has already done, and its pending hold. */
    private class Press(
        var spent: Boolean = false,
        var holdFired: Boolean = false,
        var pendingHold: Runnable? = null,
    )

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brightness = Brightness(this)
        readout = Readout(this)
    }

    /**
     * Only to learn which app is in front. `event.packageName` rides along with the event
     * itself, so this costs no content access.
     *
     * Transient windows are ignored rather than remembered: the notification shade and our
     * own readout overlay both raise window-state events, and treating either as "the app in
     * front" would silently swap the mapping mid-turn.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in transientPackages) return
        foreground = pkg
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = LightKeys.of(event) ?: return false
        // Our own settings screen reports itself, because window-state events from this
        // package are ignored — the readout overlay raises them too.
        val front = if (OwnWindow.resumed) packageName else foreground
        val behaviour = Policy.behaviourFor(prefs, front)

        if (key == LightKey.WheelUp || key == LightKey.WheelDown) {
            val notches = if (key == LightKey.WheelUp) 1 else -1
            return onTurn(behaviour, notches, event.action == KeyEvent.ACTION_DOWN)
        }

        val button = LightKeys.buttonOf(key) ?: return false
        if (!behaviour.buttonsActive) return false
        return onButton(button, behaviour, event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        readout.dismiss()
        presses.clear()
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------- the buttons

    /**
     * Tap versus hold, for one button.
     *
     * A held key on this phone produces no repeats, so the split is timed: DOWN schedules the
     * hold action, UP either cancels it and runs the tap, or does nothing because the hold
     * already fired. The wheel click has a third possibility — a notch arriving mid-press
     * turns the whole thing into a brightness gesture, which cancels the pending hold and
     * suppresses the tap, because ending a brightness adjustment with the flashlight coming
     * on is a genuinely nasty surprise in the dark.
     */
    private fun onButton(button: Button, behaviour: Behaviour, event: KeyEvent): Boolean {
        val tap = prefs.action(button, Gesture.Tap)
        val hold = prefs.action(button, Gesture.Hold)

        // Nothing bound. Don't touch the key at all — on the volume keys that would be
        // taking away volume control to add nothing.
        val modifier = button == Button.WheelClick && behaviour.pressTurnBrightness
        if (!tap.acts && !hold.acts && !modifier) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return tap.consumes || hold.consumes
                val press = Press()
                presses[button] = press
                if (hold.acts) {
                    // Held by reference so UP can cancel exactly this one.
                    val fire = Runnable { fireHold(button, hold) }
                    press.pendingHold = fire
                    handler.postDelayed(fire, HOLD_MS)
                }
            }

            KeyEvent.ACTION_UP -> {
                val press = presses.remove(button)
                press?.pendingHold?.let { handler.removeCallbacks(it) }
                val spent = press?.spent == true || press?.holdFired == true
                if (!spent && tap.acts) perform(tap)
            }
        }
        // The camera button's first stage is swallowed alongside the second, so the app never
        // sees half a press.
        return tap.consumes || hold.consumes
    }

    private fun fireHold(button: Button, hold: Action) {
        val press = presses[button] ?: return
        if (press.spent || press.holdFired) return
        press.holdFired = true
        perform(hold)
    }

    private fun perform(action: Action) {
        when (action) {
            Action.Torch -> toggleTorch()
            Action.OpenCamera -> openCamera()
            is Action.Launch -> launch(action.pkg)
            Action.None, Action.PassThrough -> Unit
        }
    }

    // --------------------------------------------------------------------- the wheel

    private fun onTurn(behaviour: Behaviour, notches: Int, down: Boolean): Boolean {
        val press = presses[Button.WheelClick]
        if (press != null && behaviour.pressTurnBrightness) {
            // The press has become a modifier, so whatever it was going to do, it isn't.
            press.spent = true
            press.pendingHold?.let { handler.removeCallbacks(it) }
            if (down) adjustBrightness(notches)
            return true
        }
        return when (behaviour.bareTurn) {
            TurnAction.Brightness -> {
                if (down) adjustBrightness(notches)
                true
            }
            TurnAction.Swipe -> {
                if (down) swipe(notches)
                true
            }
            // Passed through, so the app in front can scroll with it.
            TurnAction.PassThrough -> false
        }
    }

    private fun adjustBrightness(notches: Int) {
        val percent = brightness.step(notches, prefs.brightnessSteps) ?: return
        if (prefs.showReadout) readout.show("BRIGHTNESS $percent%")
    }

    /**
     * Scrolling an app that has never heard of the wheel, by drawing a finger on it.
     *
     * `dispatchGesture` is the only route that doesn't require reading the screen — the
     * alternative, `ACTION_SCROLL_FORWARD` on a node, needs `canRetrieveWindowContent` and
     * moves a whole screenful per notch. The drag is well past touch slop so it can't be
     * mistaken for a tap, and notches are coalesced: a gesture takes ~60 ms to play, which is
     * slower than the sensor emits, so firing one per notch would queue them into treacle.
     * Instead the debt accumulates while a swipe is in flight and the next one carries the lot.
     */
    private fun swipe(notches: Int) {
        val density = resources.displayMetrics.density
        swipeDebt += notches * prefs.swipeDp * density
        if (swiping) return

        val metrics = resources.displayMetrics
        // Cap at most of a screen: a longer path is read as a fling, which overshoots.
        val limit = metrics.heightPixels * 0.6f
        val distance = swipeDebt.coerceIn(-limit, limit)
        swipeDebt -= distance
        if (abs(distance) < 1f) return

        val x = metrics.widthPixels / 2f
        val centre = metrics.heightPixels / 2f
        val half = min(abs(distance), limit) / 2f
        // Positive notches move down the page, so the finger travels up the screen.
        val from = centre + if (distance > 0) half else -half
        val to = centre - if (distance > 0) half else -half

        val path = Path().apply {
            moveTo(x, from)
            lineTo(x, to)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        swiping = true
        val done = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) = finish()
            override fun onCancelled(description: GestureDescription?) = finish()

            private fun finish() {
                swiping = false
                // Anything that piled up during the stroke goes out as one more.
                if (abs(swipeDebt) >= 1f) swipe(0)
            }
        }
        if (!dispatchGesture(gesture, done, null)) {
            swiping = false
            swipeDebt = 0f
        }
    }

    // -------------------------------------------------------------------- the actions

    /**
     * `setTorchMode` needs no permission and no open camera session, which is why the
     * flashlight is three lines rather than a CameraX dependency.
     */
    private fun toggleTorch() {
        val manager = getSystemService(CameraManager::class.java) ?: return
        runCatching {
            val id = manager.cameraIdList.firstOrNull { candidate ->
                manager.getCameraCharacteristics(candidate)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            manager.setTorchMode(id, torchOn)
        }.onFailure {
            // Another app holding the camera, or the torch changed under us.
            torchOn = false
        }
    }

    /**
     * The camera, resolved the way the home screen's camera key resolves it: the implicit
     * intent first, which on this build lands on Light's own camera at
     * `com.android.camera2/com.android.camera.CameraActivity`, then that component explicitly
     * in case a LightOS update stops publishing the filter.
     */
    private fun openCamera() {
        val attempts = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.camera2", "com.android.camera.CameraActivity"),
        )
        for (intent in attempts) if (start(intent)) return
    }

    private fun launch(pkg: String) {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return
        start(intent)
    }

    /**
     * Starting an activity from a service is a background activity start, which Android 14
     * blocks unless the app holds the `SYSTEM_ALERT_WINDOW` appop — the same grant the
     * readout needs.
     */
    private fun start(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private companion object {
        /** Long enough that a deliberate hold is unmistakable, short enough to feel answered. */
        const val HOLD_MS = 500L

        /** Stroke duration. Slow enough not to register as a fling, quick enough to keep up. */
        const val SWIPE_MS = 60L

        /** Windows that appear over an app without replacing it. */
        val transientPackages = setOf(
            "com.gios.lightcontrol",
            "com.android.systemui",
        )
    }
}
