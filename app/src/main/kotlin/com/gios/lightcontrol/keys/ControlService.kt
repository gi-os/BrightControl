package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
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

    /** When an unconsumed press started, for the shadowed home button. */
    private var shadowDownAt = 0L

    /** A wheel tap waiting to see whether a second one follows. */
    private var pendingTap: Runnable? = null

    /** One press in progress per button. */
    private val presses = mutableMapOf<Button, Press>()

    private var torchOn = false

    /** Consecutive faults, and when the last one landed. See [dormant]. */
    private var faults = 0
    private var lastFaultAt = 0L

    /** Home presses in a row where nothing the service tried reported success. */
    private var homeMisses = 0

    /** The synthetic finger that scrolls apps which don't understand the wheel. */
    private lateinit var swipe: WheelSwipe

    /**
     * Which packages are cameras, memoised. Answering means a `PackageManager` query, and the
     * question is asked on the key event — so it is asked once per app and then remembered.
     * Cleared on unbind, which is also when an install would have had time to happen.
     */
    private val cameraPackages = HashMap<String, Boolean>()

    /** Which packages are clocks, memoised for the same reason. */
    private val alarmPackages = HashMap<String, Boolean>()

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

    /**
     * Never let a fault take a key away.
     *
     * Everything below runs inside a catch that answers `false`, because the failure this app is
     * uniquely able to cause is the one that matters: a key filter that throws is a key filter
     * that swallowed a press and then crashed, and the morning it happens is the morning an
     * alarm won't turn off. Passing the key through is always a safe answer; consuming one is
     * not. Repeated faults put the service to sleep entirely — see [dormant].
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = try {
        if (dormant() || alarmSounding()) false else handleKey(event)
    } catch (t: Throwable) {
        recordFault(t)
        false
    }

    /**
     * Whether something is ringing or sounding an alarm right now.
     *
     * Nothing is worth intercepting in that moment. The dismiss gesture belongs to whatever is
     * making the noise, and being clever about which key it needs is exactly the kind of guess
     * that fails at 6am. `activePlaybackConfigurations` is the cheap honest signal — it reports
     * what is actually playing and with what intent, rather than what the ringer mode says.
     */
    private fun alarmSounding(): Boolean = runCatching {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        audio.activePlaybackConfigurations.any {
            val usage = it.audioAttributes.usage
            usage == AudioAttributes.USAGE_ALARM ||
                usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
    }.getOrDefault(false)

    /**
     * Whether the service has faulted often enough to stop trusting itself.
     *
     * Three throws inside a minute and it goes quiet until the app is opened again, which resets
     * the count. The alternative — retrying forever — is what turns one bug into a phone you
     * cannot dismiss an alarm on, and a dormant key filter is indistinguishable from an
     * uninstalled one, which is the correct thing to degrade into.
     */
    private fun dormant(): Boolean {
        if (OwnWindow.resumed) {
            faults = 0
            return false
        }
        return faults >= MAX_FAULTS
    }

    private fun recordFault(t: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFaultAt > FAULT_WINDOW_MS) faults = 0
        lastFaultAt = now
        faults++
        // Kept for the settings screen rather than only logged: a filter that has gone quiet
        // needs to be able to say why, or the only symptom is buttons that stopped working.
        prefs.setFault("${t.javaClass.simpleName}: ${t.message}", faults >= MAX_FAULTS)
        // Whatever was mid-gesture is now of unknown shape. Drop all of it.
        runCatching { swipe.cancel() }
        presses.clear()
        pendingTap = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val key = LightKeys.of(event) ?: return false
        // Our own settings screen reports itself, because window-state events from this
        // package are ignored — the readout overlay raises them too.
        val front = if (OwnWindow.resumed) packageName else foreground
        // A clock owns every key it can see. See [ownsAlarmKeys].
        if (ownsAlarmKeys(front)) return false
        val behaviour = Policy.behaviourFor(prefs, front)

        if (key == LightKey.WheelUp || key == LightKey.WheelDown) {
            val notches = if (key == LightKey.WheelUp) 1 else -1
            return onTurn(behaviour, notches, event.action == KeyEvent.ACTION_DOWN)
        }

        val button = LightKeys.buttonOf(key) ?: return false
        if (!behaviour.buttonsActive) return false
        // A camera has first claim on the camera button. See [ownsCameraKey].
        if (button == Button.Camera && ownsCameraKey(front)) return false
        // The home button is the one key the phone cannot do without. See [onHome].
        if (button == Button.Home) return onHome(front, behaviour, event)
        return onButton(button, behaviour, event)
    }

    // ---------------------------------------------------------------- the home button

    /**
     * The home button, which gets its own door.
     *
     * Timing a hold means swallowing the DOWN, and a swallowed key cannot be handed back — so
     * the moment the hold is bound, *this service* is what makes the home button work. That is a
     * promise worth being unwilling to make, and this is the list of moments it declines to:
     *
     *  - **The takeover is off**, by hand or because it turned itself off. See [noteHomeDispatch].
     *  - **The screen is off, or the phone is locked.** A home press there is a wake or an
     *    unlock, and neither belongs to us; a background activity start is dropped behind a
     *    keyguard anyway, so taking the key would trade a working button for nothing.
     *  - **LightOS is in front.** Its dashboard and its lock screen are one activity ([Policy]),
     *    and home already goes there — swallowing the key on the screen you were trying to reach
     *    can only lose. This holds even with `lightOsScreens` on, which is otherwise the switch
     *    that hands LightOS's screens their buttons.
     *  - **The hold needs an activity start and the overlay appop is missing**, which would mean
     *    a consumed press and a launch dropped in silence. See [Action.needsActivityStart].
     *
     * Every one of those falls through to [shadowHome], which consumes nothing at all: LightOS
     * sees the whole press and behaves exactly as it would with this app uninstalled. Degrading
     * into "uninstalled" is the only correct failure for this key.
     */
    private fun onHome(front: String?, behaviour: Behaviour, event: KeyEvent): Boolean {
        val hold = prefs.action(Button.Home, Gesture.Hold)
        if (!hold.acts) return shadowHome(event)
        if (front != null && front.startsWith(LIGHTOS)) return shadowHome(event)
        if (!homeConsumable(hold)) return shadowHome(event)
        return onButton(Button.Home, behaviour, event)
    }

    /** Whether this is a moment the home key may be swallowed in. See [onHome]. */
    private fun homeConsumable(hold: Action): Boolean = runCatching {
        if (!prefs.homeTakeover) return false
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return false
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) return false
        if (hold.needsActivityStart && !Grants.canDrawOverlays(this)) return false
        true
    }.getOrDefault(false) // An unreadable state is not one to start swallowing keys in.

    /**
     * The home button with nothing consumed: LightOS gets the entire press, long presses
     * included, and the tap binding fires on top of whatever it already did.
     *
     * This is what "hold left to the app" means, and it is where every guard in [onHome] lands.
     * It works precisely because it gives nothing up — which is also why it can't offer a hold:
     * you cannot know a press was long until it ends, and by then the press has already been
     * delivered. Firing the tap twice over is invisible when the tap is home; it wouldn't be for
     * most actions, which is why this shape is the home button's alone.
     */
    private fun shadowHome(event: KeyEvent): Boolean {
        // A press that began under the takeover and arrived here instead — the screen went off
        // mid-hold, or the binding changed — is dropped rather than completed. Otherwise its hold
        // fires into a phone that is now locked, which is both useless and a failure the disarm
        // counter would take seriously.
        presses.remove(Button.Home)?.pendingHold?.let { handler.removeCallbacks(it) }
        val tap = prefs.action(Button.Home, Gesture.Tap)
        if (!tap.acts) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                shadowDownAt = SystemClock.uptimeMillis()
            }
            KeyEvent.ACTION_UP -> {
                val started = shadowDownAt
                shadowDownAt = 0L
                if (started != 0L && SystemClock.uptimeMillis() - started < HOLD_MS) {
                    perform(tap)
                }
            }
        }
        return false
    }

    /**
     * What to make of a home binding that reported failure.
     *
     * Worth being clear about how much this can see, because it is less than it looks: a blocked
     * background activity start is dropped *silently* on Android 14, and `performGlobalAction`
     * returns true for "injected", not for "went home". So what reaches here is the honest
     * failures — nothing resolves the intent, the component is gone, the start threw — and not
     * every possible one. It is the last guard, not the first: the pre-flight checks in [onHome]
     * are what actually keep the key safe.
     *
     * Two in a row rather than one, because a single refusal mid-transition is plausible;
     * permanent rather than timed, because "the home button works again in a minute" is not a
     * thing to ship.
     */
    private fun noteHomeDispatch(ok: Boolean, action: Action) {
        if (ok) {
            homeMisses = 0
            return
        }
        homeMisses++
        if (homeMisses < MAX_HOME_MISSES) return
        homeMisses = 0
        prefs.disarmHome(
            "${action.store()} reported failure twice, so the home button is the system's again",
        )
    }

    /**
     * Whether the app in front is a clock, and so keeps every key untouched.
     *
     * The alarm-sounding check catches the moment audio is actually playing, which is most of
     * what matters — but not all of it. A ringing alarm that has been silenced, a pre-alarm
     * screen, a snooze countdown: all of those are a clock in front with something urgent to
     * dismiss and no sound to detect. And an alarm is the one thing on a phone where the cost
     * of being clever is oversleeping.
     *
     * So a clock is hands-off entirely, identified by what it declares rather than by name:
     * anything registered for `SHOW_ALARMS` or `SET_ALARM` is a clock by the only definition
     * the system has. On this phone that is `com.android.deskclock`, which is not in the
     * hands-off prefix list and, before this, was being intercepted like any other app.
     */
    private fun ownsAlarmKeys(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg == packageName) return false
        alarmPackages[pkg]?.let { return it }
        val declared = runCatching {
            listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM).any { action ->
                packageManager.queryIntentActivities(Intent(action), 0)
                    .any { it.activityInfo?.packageName == pkg }
            }
        }.getOrDefault(false)
        alarmPackages[pkg] = declared
        return declared
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
        pendingTap = null
        presses.clear()
        cameraPackages.clear()
        alarmPackages.clear()
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
        val switcher = button == Button.WheelClick && prefs.doubleTapSwitchesTurn
        if (!tap.acts && !hold.acts && !switcher) return false

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
                // A release whose press we never saw. On the home button that means the DOWN was
                // gated — the screen was off, or the phone was locked — and the release arrived
                // after the wake, so acting on it would fire home on the way out of unlocking.
                // Half a press is not a press.
                if (press == null && button == Button.Home) return false
                press?.pendingHold?.let { handler.removeCallbacks(it) }
                val spent = press?.spent == true || press?.holdFired == true
                if (spent) return true
                if (switcher) {
                    // Second tap inside the window: the first one never happened, and turning
                    // the wheel means the other thing now.
                    val waiting = pendingTap
                    if (waiting != null) {
                        handler.removeCallbacks(waiting)
                        pendingTap = null
                        switchTurn()
                        return true
                    }
                    // Hold the tap back until the window closes, so a double tap doesn't also
                    // fire the flashlight on its way past.
                    if (tap.acts) {
                        val fire = Runnable {
                            pendingTap = null
                            perform(tap)
                        }
                        pendingTap = fire
                        handler.postDelayed(fire, DOUBLE_TAP_MS)
                    }
                    return true
                }
                if (tap.acts) act(button, tap)
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
        act(button, hold)
    }

    /** Perform a binding, and on the home button keep score of whether it worked. */
    private fun act(button: Button, action: Action) {
        val ok = perform(action)
        if (button == Button.Home) noteHomeDispatch(ok, action)
    }

    /** True if the action reported that it did something. */
    private fun perform(action: Action): Boolean = when (action) {
        Action.Torch -> {
            toggleTorch()
            true
        }
        Action.OpenCamera -> openCamera()
        is Action.Launch -> launch(action.pkg)
        Action.DefaultHome -> goHome()
        Action.LightOsHome -> goLightOsHome()
        Action.None, Action.PassThrough -> true
    }

    // --------------------------------------------------------------------- the wheel

    /**
     * Flip what turning the wheel does, and say so on screen.
     *
     * Only ever between brightness and scrolling: those are the two things a turn can mean, and
     * the synthetic-swipe mode is a per-app decision rather than something to land on by
     * accident while tapping.
     */
    private fun switchTurn() {
        val next = if (prefs.unknownAppTurn == TurnAction.Brightness) {
            TurnAction.PassThrough
        } else {
            TurnAction.Brightness
        }
        prefs.unknownAppTurn = next
        readout.show(if (next == TurnAction.Brightness) "BRIGHTNESS" else "SCROLL")
    }

    private fun onTurn(behaviour: Behaviour, notches: Int, down: Boolean): Boolean {
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
    private fun openCamera(): Boolean {
        val attempts = listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.camera2", "com.android.camera.CameraActivity"),
        )
        for (intent in attempts) if (start(intent)) return true
        return false
    }

    private fun launch(pkg: String): Boolean {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        // Nothing to launch — an app uninstalled since it was bound, or one with no launcher
        // entry. Fall back to home rather than swallowing the press: on the home button that
        // would strand the user on whatever screen they were trying to leave.
        if (intent != null && start(intent)) return true
        return goHome()
    }

    /**
     * LightOS's dashboard by name, because resolving `CATEGORY_HOME` would just return whatever
     * launcher is default — and reaching Light's home when it *isn't* the default is the entire
     * job of this action. On a phone with a third-party launcher installed to see sideloaded
     * APKs, this is the only way back.
     *
     * Three attempts, narrowest first: the component, then whatever `com.lightos` publishes as
     * its launcher entry in case Light renames the activity, then plain home. The last one is
     * the interesting fallback — it costs a wrong destination but never a dead press.
     */
    private fun goLightOsHome(): Boolean {
        val explicit = Intent(Intent.ACTION_MAIN)
            .setClassName(LIGHTOS, "$LIGHTOS.MainActivity")
        if (start(explicit)) return true
        val published = runCatching { packageManager.getLaunchIntentForPackage(LIGHTOS) }.getOrNull()
        if (published != null && start(published)) return true
        return goHome()
    }

    /**
     * Home: the default launcher, brought to the front by name.
     *
     * It has to be the intent, and `GLOBAL_ACTION_HOME` is the interesting wrong answer. That
     * global action does not start the home activity — AOSP's `SystemActionPerformer` implements
     * it as `sendDownAndUpKeyEvents(KEYCODE_HOME)`, an *injected key event*. Injecting a home key
     * hands the press to whatever already has focus, and LightOS reads a home press as "go back to
     * the idle face" — so on LightOS's own screens the result was a flash of the dashboard and a
     * bounce straight back to the lock screen, with the default launcher never reached. An
     * activity start doesn't ask anybody: it puts the launcher in front.
     *
     * The global action stays as the fallback for the cases the intent can't cover — no resolvable
     * home activity, a start that throws, or no overlay appop, without which the start would be
     * dropped in silence. An injected key is a poor home button, but it needs no grant at all, so
     * it is the right thing to be left holding.
     */
    private fun goHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Grants.canDrawOverlays(this) && start(intent)) return true
        return runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
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
        /** Faults tolerated before the filter goes quiet, and the window they must fall in. */
        const val MAX_FAULTS = 3
        const val FAULT_WINDOW_MS = 60_000L

        /** Long enough that a deliberate hold is unmistakable, short enough to feel answered. */
        const val HOLD_MS = 500L

        /** Failed home dispatches in a row before the takeover disarms itself. */
        const val MAX_HOME_MISSES = 2

        /** LightOS itself: its dashboard, its lock screen, its launcher entry. */
        const val LIGHTOS = "com.lightos"

        /** Gap allowed between the two taps of a double tap. */
        const val DOUBLE_TAP_MS = 320L

        /** Stroke duration. Slow enough not to register as a fling, quick enough to keep up. */
        const val SWIPE_MS = 60L

        /** Windows that appear over an app without replacing it. */
        val transientPackages = setOf(
            "com.gios.lightcontrol",
            "com.android.systemui",
        )
    }
}
