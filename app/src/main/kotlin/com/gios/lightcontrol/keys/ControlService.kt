package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
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

    /** The synthetic finger that scrolls apps which don't understand the wheel. */
    private lateinit var swipe: WheelSwipe

    /**
     * Which packages are cameras, memoised. Answering means a `PackageManager` query, and the
     * question is asked on the key event — so it is asked once per app and then remembered.
     * Cleared on unbind, which is also when an install would have had time to happen.
     */
    private val cameraPackages = HashMap<String, Boolean>()

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
        swipe = WheelSwipe(this)
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
        val behaviour = if (locked()) lockScreenBehaviour() else Policy.behaviourFor(prefs, front)

        if (key == LightKey.WheelUp || key == LightKey.WheelDown) {
            val notches = if (key == LightKey.WheelUp) 1 else -1
            return onTurn(behaviour, notches, event.action == KeyEvent.ACTION_DOWN)
        }

        val button = LightKeys.buttonOf(key) ?: return false
        if (!behaviour.buttonsActive) return false
        // A camera has first claim on the camera button. See [ownsCameraKey].
        if (button == Button.Camera && ownsCameraKey(front)) return false
        return onButton(button, behaviour, event)
    }

    /**
     * Whether the keyguard is up.
     *
     * The lock screen is LightOS's own window, and its package sits under a hands-off prefix —
     * so by package alone the answer would always be "leave it alone", and the bindings would
     * stop existing exactly where the flashlight matters most. Asked per key event because it
     * changes without any window-state event we can see.
     */
    private fun locked(): Boolean = runCatching {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    /**
     * What the controls do while locked.
     *
     * Turns are brightness rather than pass-through: there is nothing on the lock screen to
     * scroll, and a notch that does nothing is worse than the behaviour LightOS was giving.
     *
     * A binding that opens an app is a different matter — a background activity start behind
     * the keyguard is dropped unless the target itself declares `showWhenLocked`, which is not
     * ours to declare. The torch and brightness are the two that genuinely work here, so those
     * are what the default bindings do; anything else silently waits for an unlock, and the
     * README says so rather than pretending.
     */
    private fun lockScreenBehaviour(): Behaviour = if (prefs.lockScreen) {
        Behaviour(
            bareTurn = TurnAction.Brightness,
            pressTurnBrightness = prefs.pressTurnBrightness,
            buttonsActive = true,
        )
    } else {
        Behaviour(
            bareTurn = TurnAction.PassThrough,
            pressTurnBrightness = false,
            buttonsActive = false,
        )
    }

    /**
     * Whether the app in front is a camera, and so should be handed the camera button
     * untouched.
     *
     * Without this, the camera key is swallowed everywhere and its bound action — by default
     * "open the camera" — runs even when a camera is already open and in front. In a
     * third-party camera that is worse than useless: the app never sees the key, so its
     * shutter is dead, and the LPIII's two-stage release is exactly the thing you installed
     * it for. Launching a camera from inside a camera is meaningless in any case.
     *
     * The test is what the app *declares*, not a list of package names: anything registered
     * for `STILL_IMAGE_CAMERA` is a camera by the only definition the system has, so this
     * keeps working for cameras that don't exist yet. Light's own camera is already hands-off
     * by prefix; this covers `com.gios.lightcamera` and everyone else.
     *
     * An explicit per-app rule still wins — [Behaviour.buttonsActive] is checked first — so a
     * camera can be forced back under the service's control if it ever needs to be.
     */
    private fun ownsCameraKey(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg == packageName) return false
        cameraPackages[pkg]?.let { return it }
        val declared = runCatching {
            packageManager.queryIntentActivities(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                0,
            ).any { it.activityInfo?.packageName == pkg }
        }.getOrDefault(false)
        cameraPackages[pkg] = declared
        return declared
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        readout.dismiss()
        swipe.cancel()
        presses.clear()
        cameraPackages.clear()
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
                if (down) swipe.turn(notches, prefs.swipeDp)
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
