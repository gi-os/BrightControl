package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gios.lightcontrol.Behaviour
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.TurnAction

/**
 * The wheel and the camera button, everywhere on the phone.
 *
 * An [AccessibilityService] with `flagRequestFilterKeyEvents` is the only way an app can see
 * a key it doesn't have focus for; `INJECT_EVENTS` and the rest are signature-only. The
 * service is declared with `canRetrieveWindowContent="false"` and one event type, so what it
 * can observe is exactly: key codes, and the package name of the app that came to the front.
 * Never a word of what's on screen.
 *
 * ### What it deliberately does not do
 *
 * It does not scroll other apps. It could — a synthetic `dispatchGesture` swipe, or
 * `ACTION_SCROLL_FORWARD` on a node — but a gesture gets misread as a tap or a fling, and a
 * node scroll moves a whole screenful per notch and requires reading the screen. So turns
 * are *passed through* instead, and an app that wants per-notch scrolling implements it
 * itself. That is what the `hw/` module in the LightX apps is for.
 *
 * ### Consuming is the dangerous half
 *
 * Every key this service swallows is a key some app doesn't get, so the rule is narrow:
 * consume only what has actually been acted on, and only for apps whose [Behaviour] asked
 * for it. Light's own tools resolve to hands-off, because the wheel already works there and
 * anything intercepted would be a feature removed rather than added.
 */
class ControlService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private lateinit var brightness: Brightness
    private lateinit var readout: Readout

    /** The app in front, from window-state events. Null until the first one arrives. */
    @Volatile
    private var foreground: String? = null

    private var clickHeld = false

    /** Whether this press has already been spent adjusting brightness. */
    private var clickSpent = false

    private var torchOn = false

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
        val down = event.action == KeyEvent.ACTION_DOWN

        return when (key) {
            LightKey.WheelClick -> onClick(behaviour, down, event.repeatCount)
            LightKey.WheelUp -> onTurn(behaviour, +1, down)
            LightKey.WheelDown -> onTurn(behaviour, -1, down)
            LightKey.Camera -> {
                if (!behaviour.cameraKeyOpensCamera) return false
                if (down && event.repeatCount == 0) openCamera()
                true
            }
            // The camera button's first stage, which arrives paired with Camera in either
            // order. Swallowed alongside it so the app doesn't see half a press.
            LightKey.Focus -> behaviour.cameraKeyOpensCamera
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        readout.dismiss()
        clickHeld = false
        return super.onUnbind(intent)
    }

    /**
     * The click is a modifier as well as a button, and which one it turned out to be is only
     * known at the end. A held click produces no key repeat, so: remember DOWN, and on UP
     * fire the torch only if no notch arrived in between.
     */
    private fun onClick(behaviour: Behaviour, down: Boolean, repeatCount: Int): Boolean {
        // With neither brightness nor torch wanted here, the press isn't ours at all.
        if (!behaviour.pressTurnBrightness && !behaviour.clickTorch) return false

        if (down) {
            if (repeatCount == 0) {
                clickHeld = true
                clickSpent = false
            }
        } else {
            clickHeld = false
            // A press that moved the wheel was a brightness gesture; lighting the torch on
            // the way out of it would be a nasty surprise in the dark.
            if (!clickSpent && behaviour.clickTorch) toggleTorch()
        }
        return true
    }

    private fun onTurn(behaviour: Behaviour, notches: Int, down: Boolean): Boolean {
        if (clickHeld && behaviour.pressTurnBrightness) {
            clickSpent = true
            // One notch is a complete DOWN+UP pair, so act on DOWN and swallow the UP.
            if (down) adjust(notches)
            return true
        }
        if (behaviour.bareTurn == TurnAction.Brightness) {
            if (down) adjust(notches)
            return true
        }
        // Passed through, so the app in front can scroll with it.
        return false
    }

    private fun adjust(notches: Int) {
        val percent = brightness.step(notches, prefs.brightnessSteps) ?: return
        if (prefs.showReadout) readout.show("BRIGHTNESS $percent%")
    }

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
     * Starting an activity from a service is a background activity start, which Android 14
     * blocks unless the app holds the `SYSTEM_ALERT_WINDOW` appop — the same grant the
     * readout needs. The implicit intent is what the home screen's camera key resolves to;
     * the explicit one covers a LightOS that stops publishing the filter.
     */
    private fun openCamera() {
        val attempts = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.camera2", "com.android.camera.CameraActivity"),
        )
        for (intent in attempts) {
            val ok = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    private companion object {
        /** Windows that appear over an app without replacing it. */
        val transientPackages = setOf(
            "com.gios.lightcontrol",
            "com.android.systemui",
        )
    }
}
