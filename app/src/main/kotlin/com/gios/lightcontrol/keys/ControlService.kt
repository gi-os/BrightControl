package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
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
import com.gios.lightcontrol.lock.Lock
import com.gios.lightcontrol.lock.LockOverlay

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
    private lateinit var volumeHud: VolumeHud
    private lateinit var volume: VolumeWatcher

    private val handler = Handler(Looper.getMainLooper())

    /** The app in front, from window-state events. Null until the first one arrives. */
    @Volatile
    private var foreground: String? = null

    /** The one before it, and when the change happened. See [onScreenOff]. */
    @Volatile
    private var previous: String? = null

    @Volatile
    private var foregroundAt = 0L

    /**
     * The app the screen went off on, if it is worth offering back. Cleared as soon as it is
     * used, or as soon as you go somewhere else. See [Action.Resume].
     */
    @Volatile
    private var slept: String? = null

    /** When an unconsumed press started, for the shadowed home button. */
    private var shadowDownAt = 0L

    /**
     * Whether you are on LightOS because the hold *sent* you there — a visit, not a landing.
     *
     * The distinction the home button lives on. A tap that names a destination takes the key
     * anywhere (v2.1), which un-stranded the screens a wake dumps you on — and stranded the
     * dashboard you deliberately opened instead: LightOS enters its menu on a home press, and
     * the tap was stealing exactly that press. So arriving by hold marks a visit, and while it
     * lasts, home belongs to LightOS. Cleared by leaving, by the screen going off — a wake is a
     * landing, and a single press must escape it — and by the double press that ends it.
     */
    @Volatile
    private var visitingLightOs = false

    /** When the previous visiting tap's release landed, for the double press. */
    private var visitTapAt = 0L

    /** When a visiting press went down, so a hold's release doesn't count as a tap. */
    private var visitDownAt = 0L

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

    /** Lock faces in a row that failed to start. See [showLockFace]. */
    private var lockMisses = 0

    /** The last binding performed and when, so the same one twice over can be dropped. */
    private var lastAction: Action? = null
    private var lastActionAt = 0L

    /** How many times the same binding has fired in a row, and when the run started. */
    private var sameActionRun = 0
    private var runStartedAt = 0L

    /** The last turn written to the key log, and when. See [logTurn]. */
    private var lastTurnKey = ""
    private var lastTurnLogAt = 0L

    /** The last whole-filter refusal logged, and when. See [logRefusal]. */
    private var lastRefusalReason = ""
    private var lastRefusalLogAt = 0L

    /** When something last rang. See [ringing]. */
    private var lastRingAt = 0L

    /** When an activity was last started from here, and where it was going. See [start]. */
    private var lastStartAt = 0L
    private var lastStartTarget: String? = null

    /** The synthetic finger that scrolls apps which don't understand the wheel. */
    private lateinit var swipe: WheelSwipe

    /** The Light face over the lock screen. A window this service owns, not an activity. */
    private lateinit var lockFace: LockOverlay

    /**
     * Which packages are cameras, memoised. Answering means a `PackageManager` query, and the
     * question is asked on the key event — so it is asked once per app and then remembered.
     * Cleared on unbind, which is also when an install would have had time to happen.
     */
    private val cameraPackages = HashMap<String, Boolean>()

    /** Which packages are clocks, memoised for the same reason. */
    private val alarmPackages = HashMap<String, Boolean>()

    /** One press in flight. Nothing but when it started — the release does the deciding. */
    private class Press(val downAt: Long)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        brightness = Brightness(this)
        readout = Readout(this)
        volumeHud = VolumeHud(this)
        // Gated on the master switch as well as its own setting: "this app does nothing" has to
        // mean nothing, including drawing over other apps.
        volume = VolumeWatcher(
            context = this,
            hud = volumeHud,
            front = { if (OwnWindow.resumed) packageName else foreground },
            wanted = { prefs.enabled && prefs.showVolume },
            pinningAllowed = { prefs.volumePin },
        )
        volumeHud.onTap = { volume.onHudTap() }
        volume.start()
        swipe = WheelSwipe(this)
        lockFace = LockOverlay(this)
        // The first-class version of "is the phone open", on the versions that have it. A listener
        // rather than only a broadcast, because ACTION_USER_PRESENT is the one signal in this
        // feature that has already been observed not to arrive, and a lock face that outlives the
        // unlock is the worst failure this app can produce.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                getSystemService(KeyguardManager::class.java)
                    ?.addKeyguardLockedStateListener(mainExecutor) { locked ->
                        if (!locked) runCatching { onUserPresent() }
                    }
            }
        }
        // ACTION_SCREEN_OFF is a protected system broadcast, so the export flag is not strictly
        // required — passed anyway, because "not exported" is the true answer and the default
        // has changed once already.
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOff, filter)
            }
        }
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
        if (pkg != foreground) {
            previous = foreground
            foreground = pkg
            foregroundAt = SystemClock.uptimeMillis()
        }
        // The offer to go back only stands while you are still sitting on LightOS's lock screen
        // or dashboard, which is where a wake leaves you. Reach any other app under your own
        // steam and the offer is withdrawn — otherwise home would yank you out of the thing you
        // deliberately opened, on the strength of what you happened to be doing last night.
        val pending = slept
        if (pending != null && pkg != pending && !pkg.startsWith(LIGHTOS)) slept = null
        // Reaching anywhere that isn't LightOS ends a visit — most naturally by opening an app
        // from LightOS's own menu, which is one of the two ways out.
        if (visitingLightOs && !pkg.startsWith(LIGHTOS)) visitingLightOs = false
    }

    /**
     * Remember what to offer back, at the moment the screen goes off.
     *
     * `ACTION_SCREEN_OFF` cannot be declared in a manifest — it is delivered only to receivers
     * registered in code — which is exactly why this lives in the service and not in the apps
     * themselves. A backgrounded app is cached and frozen, and its context-registered broadcasts
     * are queued until something unfreezes it; this process is bound by the system and so is
     * never either.
     *
     * The awkward case is LightOS's lock screen, which comes over *as* the screen goes off and
     * would otherwise be recorded as where you were. So a LightOS window that arrived in the last
     * breath before the broadcast is read as the lock screen arriving rather than somewhere you
     * navigated to, and the app underneath it is what gets remembered.
     */
    private fun onScreenOff() {
        val front = foreground
        val justChanged = SystemClock.uptimeMillis() - foregroundAt < LOCK_GRACE_MS
        slept = when {
            front == null -> null
            !front.startsWith(LIGHTOS) -> front
            justChanged -> previous?.takeUnless { it.startsWith(LIGHTOS) }
            else -> null
        }
        // A wake is a landing, not a visit: after the screen has been off, one press escapes.
        visitingLightOs = false
        log("screen off · ${slept?.substringAfterLast('.') ?: "nothing to resume"}")
        // Strictly after the snapshot. The face reads it to say what unlocking will open, and a
        // face that raised its own window first would be reading the value it had just changed.
        Lock.pending = slept
        // Immediately, with no delay to tune. v2.6 had to wait 900 ms for LightOS's lock screen to
        // finish coming up, because an activity would otherwise have been started underneath it.
        // A window at layer 31 is above anything LightOS can put on screen whenever it arrives, so
        // there is nothing left to race.
        showLockFace()
    }

    /**
     * Re-assert the face as the panel lights.
     *
     * Belt to the delayed start's braces. If anything came over ours while the phone was down — a
     * notification's full-screen intent, LightOS redrawing its own lock screen on a timer — this is
     * the last moment before the user sees the result. `singleTask` means a second start is a
     * `onNewIntent` on the activity already there, which costs nothing.
     */
    private fun onScreenOn() {
        if (!prefs.lockScreen) return
        // Not if a tap put it away. Re-raising a face the user just dismissed to reach the keypad
        // would make the keypad unreachable, which is the one bug this feature must never have.
        if (lockFace.dismissed()) return
        showLockFace()
    }

    /**
     * The phone is open. Take the face down, then go where Resume would have gone.
     *
     * Handled here rather than in the activity because the activity may well have been stopped by
     * the bouncer that was just dismissed — see [screenOff]. Dismiss first and launch on the next
     * loop: an activity start aimed at a task that is still tearing down is the one shape of this
     * that reliably lands behind the wrong window.
     */
    private fun onUserPresent() {
        val wasUp = lockFace.showing || lockFace.dismissed()
        handler.removeCallbacks(lockWatch)
        val gone = runCatching { lockFace.hide() }.getOrDefault(false)
        log(if (gone) "unlocked · face down" else "unlocked · face WOULD NOT GO")
        if (!wasUp) return
        handler.postDelayed({ runCatching { resumeFromLock() } }, LOCK_HANDOFF_MS)
    }

    /**
     * Ask, three times a second, whether the phone has been opened.
     *
     * Inelegant and deliberate. Two signals should already have answered — `ACTION_USER_PRESENT`
     * and the keyguard's own locked-state listener — and between v2.5 and v2.8 the face has now
     * twice been left on screen over an unlocked phone because the one signal in play did not
     * arrive. `KeyguardManager.isDeviceLocked` is not a notification that can be missed; it is the
     * state itself. It costs one binder call per tick, only while the face is up, and it stops the
     * moment anything takes the face down.
     */
    private val lockWatch = object : Runnable {
        override fun run() {
            if (!lockFace.showing) return
            val km = runCatching { getSystemService(KeyguardManager::class.java) }.getOrNull()
            if (km != null && !km.isDeviceLocked) {
                onUserPresent()
                return
            }
            handler.postDelayed(this, LOCK_WATCH_MS)
        }
    }

    /**
     * Screen off, screen on, and the unlock.
     *
     * All three in one receiver because all three are the lock face's, and because *this* is where
     * they have to be handled. `ACTION_USER_PRESENT` used to be registered by the lock activity
     * itself, which was the v2.5 bug that left the face up over an unlocked phone: showing the
     * bouncer over an occluding activity stops that activity, `onStop` unregistered the receiver,
     * and the broadcast arrived while nothing was listening. A bound accessibility service is
     * never stopped and never frozen, so it cannot miss it.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_USER_PRESENT -> onUserPresent()
                }
            }
        }
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
        // Before every guard below, and outside all of them, because the volume HUD is not a
        // binding: nothing is consumed, nothing is adjusted, and the moments this service keeps
        // its hands off keys — an alarm ringing, a call, a clock in front — are moments the volume
        // still moves and still wants showing. It cannot affect what this method returns.
        noteVolumeKey(event)
        // Named and logged, because this used to be the one refusal with no witness: three
        // conditions folded into one silent `false`, upstream of every log line — so a filter
        // standing down here was indistinguishable, from the phone, from a filter that never got
        // the key. Days were lost to exactly that.
        val refusal = when {
            !prefs.enabled -> "switched off"
            dormant() -> "dormant"
            ringing() -> "ringing"
            else -> null
        }
        if (refusal != null) {
            logRefusal(refusal, event)
            false
        } else {
            handleKey(event)
        }
    } catch (t: Throwable) {
        recordFault(t)
        false
    }

    /**
     * A volume key arrived: read the level back and flash it at the top of the screen.
     *
     * LightOS has no volume UI, so a press is silent in both senses — the level changes and nothing
     * says so. [VolumeWatcher] also listens for the system's own volume broadcast, which catches
     * changes made by apps and headsets; this is the fallback for a press whose broadcast never
     * comes, and the reason it is the *fallback* is that the value has to be read after the system
     * applies the press, not when the key arrives.
     *
     * Its own catch, not the one in [onKeyEvent]: a cosmetic overlay is not worth a fault count
     * against a key filter, and certainly not worth standing the service down.
     */
    private fun noteVolumeKey(event: KeyEvent) {
        runCatching {
            if (event.action != KeyEvent.ACTION_DOWN) return
            val key = LightKeys.of(event) ?: return
            if (key != LightKey.VolumeUp && key != LightKey.VolumeDown) return
            volume.onVolumeKey()
        }
    }

    /**
     * Whether something is ringing, alarming, or in a call — now or in the last [RING_GRACE_MS].
     *
     * Nothing is worth intercepting in that moment. The dismiss gesture belongs to whatever is
     * making the noise, and being clever about which key it needs is exactly the kind of guess
     * that fails at 6am — which it duly did: LightOS went down during an alarm, and LightOS runs as
     * uid 1000, so that is the whole interface. Widened afterwards from "alarm or ringtone playing"
     * to every ring-ish usage, the ringer and call audio modes, and a grace window, because the
     * previous version could only see the seconds when audio was actually coming out.
     */
    private fun ringing(): Boolean = runCatching {
        val now = SystemClock.uptimeMillis()
        // Still inside the grace window from the last thing that rang. Sampling only at key events
        // means the moment an alarm is *silenced* looks identical to silence, while the screen with
        // the "stop" button on it is still up and being pressed at. Half a minute of hands-off
        // after a ring costs nothing and covers the whole of that.
        if (lastRingAt != 0L && now - lastRingAt < RING_GRACE_MS) return true
        val audio = getSystemService(AudioManager::class.java) ?: return false
        val playing = audio.activePlaybackConfigurations.any {
            it.audioAttributes.usage in ringUsages
        }
        // Ringer and call modes are the other half of the same question, and they answer it even
        // when nothing is coming out of the speaker yet.
        val mode = audio.mode
        val busy = playing ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        if (busy) lastRingAt = now
        busy
    }.getOrDefault(false)

    /**
     * One line for a key the whole filter declined to look at, deduped hard.
     *
     * These refusals hold for stretches of time rather than single presses — a ring's grace
     * window is half a minute, dormancy lasts until the app is opened — and a twelve-line log
     * must not be twelve copies of the same fact. One line per reason per window, fresh downs
     * only, and its own catch: a refusal is not worth a fault.
     */
    private fun logRefusal(reason: String, event: KeyEvent) {
        runCatching {
            if (!isFreshDown(event)) return
            val key = LightKeys.of(event) ?: return
            val now = SystemClock.uptimeMillis()
            if (reason == lastRefusalReason && now - lastRefusalLogAt < REFUSAL_LOG_MS) return
            lastRefusalReason = reason
            lastRefusalLogAt = now
            log("${key.name} · filter down — $reason")
        }
    }

    /**
     * One line in the on-screen key log.
     *
     * A key filter is close to undebuggable from the outside: the only symptom of anything going
     * wrong is a button that did the wrong thing, and by the time you've noticed, the evidence is
     * the memory of a flicker. There is no adb in a pocket. So the last dozen decisions are kept
     * somewhere they can be read on the phone — what arrived, what was in front, what was done.
     */
    private fun log(line: String) {
        runCatching { prefs.appendLog(line) }
    }

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

    /** A first press, not a repeat and not a release. Named apart from the local `fresh` vals. */
    private fun isFreshDown(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0

    private fun handleKey(event: KeyEvent): Boolean {
        val key = LightKeys.of(event) ?: return false
        // Our own settings screen reports itself, because window-state events from this
        // package are ignored — the readout overlay raises them too.
        val front = if (OwnWindow.resumed) packageName else foreground
        // A clock owns every key it can see. See [ownsAlarmKeys]. Logged, because this refusal
        // sits above every other and used to be silent — a key eaten here is indistinguishable
        // in the log from a key that never arrived, which is two different bugs.
        if (ownsAlarmKeys(front)) {
            if (isFreshDown(event) && key != LightKey.WheelUp && key != LightKey.WheelDown) {
                log("${key.name} · clock owns the keys")
            }
            return false
        }
        val behaviour = Policy.behaviourFor(prefs, front)

        if (key == LightKey.WheelUp || key == LightKey.WheelDown) {
            val notches = if (key == LightKey.WheelUp) 1 else -1
            return onTurn(front, behaviour, notches, event.action == KeyEvent.ACTION_DOWN)
        }

        // A stream pinned by tapping the volume strip. The only place this app moves a volume
        // itself, and the only place it consumes a volume key — both of which need an explicit tap
        // first and expire with the strip. Note where this sits: after the alarm and clock refusals
        // above, and inside a method the service does not reach at all while anything is ringing, so
        // a pin can never be holding the keys that dismiss an alarm.
        if (key == LightKey.VolumeUp || key == LightKey.VolumeDown) {
            if (volume.takeKey(key == LightKey.VolumeUp, event)) {
                if (isFreshDown(event)) log("${key.name} pinned stream")
                return true
            }
        }

        val button = LightKeys.buttonOf(key) ?: return false

        // A press already taken is a press owned to the end.
        //
        // Every check below asks about the app in front, and the app in front changes *because of
        // what this service just did*: the hold fires, an activity comes over, and the release
        // arrives to a different set of answers than the press did. Re-deciding then is how a
        // consumed DOWN grew an unconsumed UP — LightOS got a lone home release and read it as a
        // home press of its own, so holding home brought its dashboard over and then walked
        // straight on into the menu. A binding that launches something must not hand the rest of
        // its own press to the thing it launched.
        //
        // Only a fresh DOWN gets to consult the rules. Everything after it belongs to the press.
        val fresh = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (fresh) log("${button.name} down · ${front?.substringAfterLast('.') ?: "?"}")
        if (!fresh && presses.containsKey(button)) return onButton(button, behaviour, event)

        // The home button is the one key the phone cannot do without, so its door comes *before*
        // the hands-off gate. [onHome] holds the complete refusal list, and a hands-off app in
        // front is one of its reasons to decline — but declining has to mean shadow mode, where
        // the tap still fires on top, and a tap that names its own destination still takes the
        // key. The gate below used to eat the key first, which made LightOS's screens exactly the
        // place the tap stopped working: wake the phone, land on the dashboard, and the press that
        // should have opened your launcher belonged to LightOS alone — the idle face again, with
        // nothing left anywhere to leave it by. See [onHome].
        if (button == Button.Home) return onHome(front, behaviour, event)
        if (!behaviour.buttonsActive) {
            if (fresh) log("${button.name} hands off")
            return false
        }
        // A camera has first claim on the camera button. See [ownsCameraKey].
        if (button == Button.Camera && ownsCameraKey(front)) return false
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
     *  - **LightOS is in front and the tap has nowhere of its own to go.** Its dashboard and its
     *    lock screen are one activity ([Policy]), and home already goes there — swallowing the key
     *    on the screen you were trying to reach can only lose. This holds even with
     *    `lightOsScreens` on, which is otherwise the switch that hands LightOS's screens their
     *    buttons. A tap bound to Resume or to an app is the exception, and see below for why.
     *  - **The hold needs an activity start and the overlay appop is missing**, which would mean
     *    a consumed press and a launch dropped in silence. See [Action.needsActivityStart].
     *
     * Every one of those falls through to [shadowHome], which consumes nothing at all: LightOS
     * sees the whole press and behaves exactly as it would with this app uninstalled. Degrading
     * into "uninstalled" is the only correct failure for this key.
     *
     * Note that this only ever runs for a *fresh* press — see [handleKey]. Once the dashboard is
     * over, the next hold falls through here to LightOS itself, which is what makes the second one
     * open its menu.
     */
    private fun onHome(front: String?, behaviour: Behaviour, event: KeyEvent): Boolean {
        val fresh = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val hold = prefs.action(Button.Home, Gesture.Hold)
        val tap = prefs.action(Button.Home, Gesture.Tap)
        // A visit outranks everything, including a tap that picks a destination — the visit is
        // what you pressed the hold *for*. See [visitHome].
        if (front != null && front.startsWith(LIGHTOS) && visitingLightOs) {
            return visitHome(event, tap)
        }
        // A tap that names its own destination is reason enough to take the key, and it is reason
        // wherever you are standing. Shadow mode hands the identical press to LightOS, which reads
        // home as "back to the idle face" — so the app you pointed the tap at opened *and* the idle
        // face was summoned over it, and which of the two you were left looking at was a race this
        // app kept losing. That was invisible while the tap could only be home; it stopped being
        // invisible the moment Resume, or an app, could be bound to it. See [Action.picksDestination].
        val reason = when {
            !hold.acts && !tap.picksDestination -> "hold unbound"
            // Hands-off apps reach here now instead of being gated upstream, so the refusal is
            // owned here, where refusing still means the shadow tap. Same exception as LightOS
            // below: a tap that picks a destination is reason to take the key anywhere.
            !behaviour.buttonsActive && !tap.picksDestination -> "hands off"
            front != null && front.startsWith(LIGHTOS) && !tap.picksDestination ->
                "LightOS in front"
            // The expensive checks last, and each refusal under its own name: "not consumable"
            // covered four different states, and which one it was is the whole diagnosis when
            // the only witness is the key log.
            else -> homeRefusal(tap, hold)
        }
        if (reason != null) {
            if (fresh) log("HOME shadow · $reason")
            return shadowHome(event)
        }
        return onButton(Button.Home, behaviour, event)
    }

    /**
     * Why the home key may not be swallowed right now — or null when it may. See [onHome].
     *
     * A named reason rather than a boolean, because the name goes straight into the key log and
     * the log is the only witness this service has when the button misbehaves in a pocket three
     * states away from adb.
     */
    private fun homeRefusal(tap: Action, hold: Action): String? = runCatching {
        if (!prefs.homeTakeover) return "takeover off"
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return "screen off"
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard != null && keyguard.isKeyguardLocked) return "keyguard locked"
        // Both gestures, not just the hold. The hold was checked here from the start because it
        // is the one that costs the key; the tap was not, and a tap bound to something that
        // launches — an app, or Resume — then had its press swallowed and its launch dropped in
        // the same silence. A press that does nothing is the failure this whole door exists to
        // avoid, and which gesture caused it makes no difference to the thumb.
        val launching = listOf(tap, hold).filter { it.acts && it.needsActivityStart }
        if (launching.isNotEmpty() && !Grants.canDrawOverlays(this)) return "no overlay appop"
        null
    }.getOrDefault("state unreadable") // Not a state to start swallowing keys in.

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
        // mid-press, or the binding changed — is dropped rather than completed, so its release
        // can't dispatch into a phone that is now locked.
        presses.remove(Button.Home)
        val tap = prefs.action(Button.Home, Gesture.Tap)
        if (!tap.acts) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                shadowDownAt = SystemClock.uptimeMillis()
            }
            KeyEvent.ACTION_UP -> {
                val started = shadowDownAt
                shadowDownAt = 0L
                // Only while interactive and unlocked. A shadow press on the lock screen or a
                // wake press would otherwise fire the tap into a keyguard that drops the start
                // silently — worse than nothing, because the attempt still stamps the launch
                // throttle, and the real press just after the unlock then reads as a repeat and
                // gets declined. The press that wakes the phone is the system's; ours is the
                // next one.
                if (started != 0L && SystemClock.uptimeMillis() - started < HOLD_MS && awake()) {
                    perform(tap)
                }
            }
        }
        return false
    }

    /**
     * The home button while visiting LightOS: LightOS's, until pressed twice.
     *
     * Nothing is consumed — LightOS needs the real press to enter its menu, and a key filter
     * cannot hand back a press it swallowed, so pass-through is the only shape interaction can
     * take. That also rules a hold out as the way home (timing one means consuming the DOWN),
     * which leaves the double press: two quick taps end the visit and fire the tap binding.
     * LightOS sees both presses — its menu flickers once on the way out, which is the price of
     * a key that two owners can read.
     *
     * A hold's release is deliberately not a tap here: holding home mid-visit stays LightOS's,
     * whatever it makes of it.
     */
    private fun visitHome(event: KeyEvent, tap: Action): Boolean {
        presses.remove(Button.Home)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                visitDownAt = SystemClock.uptimeMillis()
            }
            KeyEvent.ACTION_UP -> {
                val started = visitDownAt
                visitDownAt = 0L
                val now = SystemClock.uptimeMillis()
                if (started == 0L || now - started >= HOLD_MS) return false
                if (visitTapAt != 0L && now - visitTapAt < HOME_DOUBLE_MS) {
                    visitTapAt = 0L
                    visitingLightOs = false
                    log("HOME double · visit over")
                    if (tap.acts) perform(tap) else goHome()
                } else {
                    visitTapAt = now
                    log("HOME · LightOS's while visiting")
                }
            }
        }
        return false
    }

    /** Interactive and unlocked — the only state a shadow tap may launch anything in. */
    private fun awake(): Boolean = runCatching {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        power?.isInteractive != false && keyguard?.isKeyguardLocked != true
    }.getOrDefault(false)

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
        // LightOS's screens are never "a clock", whatever intents the package declares — its
        // alarms live in com.android.deskclock. Reading the dashboard as a clock would put this
        // refusal above the home button's door and eat the one key that gets you off it.
        if (pkg.startsWith(LIGHTOS)) return false
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
        runCatching { unregisterReceiver(screenOff) }
        Lock.pending = null
        handler.removeCallbacks(lockWatch)
        runCatching { lockFace.hide() }
        slept = null
        visitingLightOs = false
        visitTapAt = 0L
        visitDownAt = 0L
        handler.removeCallbacksAndMessages(null)
        readout.dismiss()
        volume.stop()
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
     * **The release decides, and nothing happens before it.** A held key on this phone produces
     * no repeats, so the two are told apart by time — but the timing is measured at the release
     * rather than run off a timer that fires mid-press. That ordering is the whole point: a hold
     * that fires while the button is still down brings an app to the front *during* the press, and
     * the rest of the press then belongs to a foreground that has changed underneath it. That is
     * what made one hold of the home button bring LightOS's dashboard over and then carry on into
     * its menu. Deciding at the release means one press is one decision, dispatched once, with the
     * key already accounted for.
     *
     * It costs the feeling of a hold "going off" in your hand. Worth it.
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
                presses[button] = Press(downAt = SystemClock.uptimeMillis())
            }

            KeyEvent.ACTION_UP -> {
                val press = presses.remove(button)
                // A release whose press we never saw. On the home button that means the DOWN was
                // gated — the screen was off, or the phone was locked — and the release arrived
                // after the wake, so acting on it would fire home on the way out of unlocking.
                // Half a press is not a press.
                if (press == null && button == Button.Home) return false
                val held = press != null &&
                    SystemClock.uptimeMillis() - press.downAt >= HOLD_MS
                if (switcher && !held) {
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
                val action = if (held) hold else tap
                if (action.acts) act(button, action)
            }
        }
        // The camera button's first stage is swallowed alongside the second, so the app never
        // sees half a press.
        return tap.consumes || hold.consumes
    }

    /**
     * Perform a binding — once.
     *
     * The same action arriving twice inside [DEDUPE_MS] is dropped, and the window is short enough
     * that no deliberate second gesture can fall inside it: a hold takes 500 ms of holding plus a
     * release before it dispatches at all. What it does collapse is a duplicate nobody asked for —
     * a key event delivered twice, another key-filtering service in the chain (LightVoice runs
     * one), a binding somehow dispatched from two paths. Those show up as an action that "fires,
     * then fires again", and on LightOS's dashboard firing twice is visible: the second one walks
     * on into the menu.
     *
     * A launch that genuinely wants repeating at speed doesn't exist among these actions.
     */
    private fun act(button: Button, action: Action) {
        val now = SystemClock.uptimeMillis()
        if (action == lastAction && now - lastActionAt < DEDUPE_MS) {
            log("${button.name} ${action.store()} DUP dropped")
            return
        }
        // Someone pressing the same button over and over is someone whose phone is not doing what
        // they asked. Whatever the service thinks is happening, it is wrong, and the useful thing
        // it can do is stop — a fight with a key filter is one the phone loses, and it was a run of
        // presses like this that ended with an activity being started at a launcher over and over.
        if (action == lastAction && now - runStartedAt < MASH_WINDOW_MS) {
            sameActionRun++
            if (sameActionRun >= MASH_PRESSES) {
                faults = MAX_FAULTS
                lastFaultAt = now
                sameActionRun = 0
                prefs.setFault("${action.store()} $MASH_PRESSES times over — stood down", true)
                log("${button.name} MASH — stood down")
                return
            }
        } else {
            sameActionRun = 1
            runStartedAt = now
        }
        lastAction = action
        lastActionAt = now
        val ok = perform(action)
        log("${button.name} ${action.store()}${if (ok) "" else " FAILED"}")
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
        // Arriving by this action is what makes LightOS a visit rather than a landing, which is
        // what hands it the home button while you're there. See [visitHome].
        Action.LightOsHome -> goLightOsHome().also { if (it) visitingLightOs = true }
        Action.Resume -> resume()
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

    private fun onTurn(
        front: String?,
        behaviour: Behaviour,
        notches: Int,
        down: Boolean,
    ): Boolean {
        return when (behaviour.bareTurn) {
            TurnAction.Brightness -> {
                if (down) adjustBrightness(front, notches)
                true
            }
            TurnAction.Swipe -> {
                if (down) {
                    swipe.turn(notches, prefs.swipeDp)
                    logTurn(front, notches, "SWIPE", "${prefs.swipeDp} dp")
                }
                true
            }
            // Taken and dropped. Nothing is done with the notch, which is the point: LightOS
            // never sees it, so its own brightness ramp cannot run on it.
            TurnAction.Consume -> {
                if (down) logTurn(front, notches, "BLOCKED", "LightOS never sees it")
                true
            }
            // Passed through, so the app in front can scroll with it.
            TurnAction.PassThrough -> {
                if (down) logTurn(front, notches, "PASS THROUGH", "the app scrolls itself")
                false
            }
        }
    }

    private fun adjustBrightness(front: String?, notches: Int) {
        val percent = brightness.step(notches, prefs.brightnessSteps)
        if (percent == null) {
            // The one silent failure the wheel has: no `WRITE_SETTINGS` appop, and there is nothing
            // to fall back on, because a service has no window of its own to dim.
            logTurn(front, notches, "BRIGHTNESS", "blocked - no WRITE_SETTINGS")
            return
        }
        if (prefs.showReadout) readout.show("BRIGHTNESS $percent%")
        logTurn(front, notches, "BRIGHTNESS", "$percent%")
    }

    /**
     * One line in the key log for a wheel turn.
     *
     * Turns were the half of this app the log could not see, and they are the half that fails
     * *quietly*: a wheel that does nothing in one app looks identical whether the key never
     * arrived, the app resolved to pass through, or the brightness write was refused. Those are
     * three different fixes and there was no way to tell them apart from the phone. Now the
     * absence of a line is itself the answer — nothing logged means the service was never handed
     * the key, and the problem is upstream of every setting in this app.
     *
     * Deduped on the app and the decision rather than on the value, because a gesture is twenty
     * notches and the log is twelve lines: one turn should cost one line.
     */
    private fun logTurn(front: String?, notches: Int, tag: String, detail: String) {
        val app = front?.substringAfterLast('.') ?: "?"
        val key = "$app/$tag"
        val now = SystemClock.uptimeMillis()
        if (key == lastTurnKey && now - lastTurnLogAt < TURN_LOG_MS) return
        lastTurnKey = key
        lastTurnLogAt = now
        log("TURN ${if (notches > 0) "+" else "-"} · $app · $tag $detail")
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
        for (intent in attempts) if (start(intent, target = CAMERA_TARGET) != Start.Failed) return true
        return false
    }

    /**
     * An app, by its launcher entry.
     *
     * Falling back to home is for an app that *cannot be opened* — uninstalled since it was bound,
     * or one with no launcher entry — because on the home button a refusal would strand the user on
     * whatever screen they were trying to leave. It is emphatically not for a start the throttle
     * declined: that one already has a launch of its own in flight, and substituting home for it
     * meant a second press of home landed you home *instead of* the app you chose it to open. Doing
     * nothing is the honest answer there, and the visible one, since the first launch is arriving.
     */
    private fun launch(pkg: String): Boolean {
        val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: launcherEntry(pkg)
        if (intent == null) {
            log("launch $pkg · no entry at all")
            return goHome()
        }
        return when (start(intent, target = pkg)) {
            Start.Done, Start.Throttled -> true
            Start.Failed -> goHome()
        }
    }

    /**
     * A launcher app's own front door, for a package [launch] found no launcher entry for.
     *
     * A launcher is opened by the HOME intent, and it may publish no `CATEGORY_LAUNCHER`
     * activity at all — `getLaunchIntentForPackage` answers null for it, and that null used to
     * read as "cannot be opened", whose deliberate fallback is home. On the one binding whose
     * entire point is *reaching a launcher that is not the default*, that fallback is the exact
     * inversion of the request: home, bound to Luma, lands on LightOS — deterministically, every
     * press, and the log said only FAILED. Scoping the HOME intent to the package resolves the
     * activity the system itself would start if the app ever became default, and naming the
     * component keeps the start unambiguous.
     */
    private fun launcherEntry(pkg: String): Intent? = runCatching {
        val probe = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(pkg)
        packageManager.resolveActivity(probe, 0)?.activityInfo
            ?.let { probe.setClassName(it.packageName, it.name) }
    }.getOrNull()

    /**
     * Back to the app the screen went off on — or home, which is the answer most of the time.
     *
     * The membership test is done here rather than when the snapshot was taken, so removing an
     * app from the list takes effect on the next press instead of the next sleep.
     *
     * The snapshot is spent on use. That is what makes the second press mean home: the first one
     * brings the app over and empties the offer, and pressing again finds nothing to resume and
     * falls through to exactly what the home button did before.
     */
    // -------------------------------------------------------------------- the lock face

    /**
     * Put the Light face up over the lock screen.
     *
     * A window, not an activity, and that distinction is the whole feature — see [LockOverlay] for
     * the layer table and for why v2.5 and v2.6 could not make the fingerprint work. Because
     * nothing here starts an activity there is no background-activity-start to be refused, no
     * overlay appop involved, and no way for this to fail silently: `addView` either works or
     * throws, and a throw leaves the stock lock screen exactly where it was.
     *
     * Two guards, both about not drawing over a screen that has a job to do. The master switch,
     * and a keyguard that is actually securing something — with no PIN set there is nothing behind
     * this and "press the power button" would be a lie.
     */
    private fun showLockFace() {
        if (!prefs.enabled || !prefs.lockScreen) return

        val km = runCatching { getSystemService(KeyguardManager::class.java) }.getOrNull()
        if (km?.isDeviceSecure != true) {
            prefs.disarmLock("no screen lock is set, so there is nothing to draw over")
            return
        }

        val before = lockFace.showing
        runCatching { lockFace.show(prefs) }
        if (!before && lockFace.showing) {
            lockMisses = 0
            log("lock face up")
            handler.removeCallbacks(lockWatch)
            handler.postDelayed(lockWatch, LOCK_WATCH_MS)
        } else if (!lockFace.showing) {
            lockMisses++
            log("lock face failed · $lockMisses")
            if (lockMisses >= MAX_LOCK_MISSES) {
                prefs.disarmLock("the window failed to attach $lockMisses times running")
                lockMisses = 0
            }
        }
    }

    /**
     * The unlock landed. Go wherever the home button's Resume would have gone.
     *
     * Reusing [resume] rather than writing a second rule is the point, not a shortcut. The list of
     * apps, the fallback, the spend-on-use that makes a second press mean home — all of it already
     * exists and is already the behaviour the user configured for the home button, so an unlock
     * that went somewhere else would be a second thing to learn and a second thing to get wrong.
     * Unlocking is just the earliest possible press of it.
     */
    private fun resumeFromLock() {
        val was = Lock.pending
        Lock.pending = null
        if (!prefs.enabled || !prefs.lockScreen) return
        // Logged either way. "Unlocking didn't open anything" is a report with four possible
        // causes — no snapshot, the app not on the list, the fallback still pointing at home, or a
        // launch that failed — and from the phone they are indistinguishable without this line.
        val listed = was != null && was in prefs.resumeApps()
        val ok = resume()
        log(
            "unlock → " + when {
                listed -> was?.substringAfterLast('.').orEmpty()
                was != null -> "not on the list · fallback"
                else -> "nothing slept · fallback"
            } + if (ok) "" else " · FAILED",
        )
    }

    private fun resume(): Boolean {
        val pkg = slept
        if (pkg == null || pkg !in prefs.resumeApps()) return resumeFallback()
        slept = null
        // Already looking at it — going back to where you are is not a thing anyone pressed home
        // for, so this is the fallback too.
        if (pkg == foreground) return resumeFallback()
        return launch(pkg)
    }

    /**
     * What Resume does when there is nothing to resume, which is most of the time.
     *
     * The second press of a pair lands here, and so does every ordinary press. Configurable
     * because Resume is bound *over* whatever the home tap used to be: on this phone LightOS
     * holds the HOME role, so plain home means LightOS, and someone whose tap pointed at Luma
     * would have lost their home screen by turning this feature on. Wrapping the old binding
     * instead of replacing it is the whole difference.
     */
    private fun resumeFallback(): Boolean {
        val fallback = prefs.resumeFallback
        // launch() already falls back to home on an app that has been uninstalled, so a stale
        // package here costs a wrong destination rather than a dead press.
        return if (fallback is Action.Launch) launch(fallback.pkg) else goHome()
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
        // If LightOS is already the default launcher, this action and plain home are the same
        // destination — and then the home intent is the *only* correct way to get there. Starting a
        // home activity by component is how you put a launcher somewhere it doesn't belong in the
        // task hierarchy, and this launcher is uid 1000. Ask by category when the answer is the
        // same; name the component only when it isn't, which is the case this action exists for.
        if (defaultHomeIsLightOs()) return goHome()
        val explicit = Intent(Intent.ACTION_MAIN)
            .setClassName(LIGHTOS, "$LIGHTOS.MainActivity")
        if (start(explicit, target = LIGHTOS) != Start.Failed) return true
        val published = runCatching { packageManager.getLaunchIntentForPackage(LIGHTOS) }.getOrNull()
        if (published != null && start(published, target = LIGHTOS) != Start.Failed) return true
        return goHome()
    }

    /** Whether the launcher the system would go home to is LightOS's own. */
    private fun defaultHomeIsLightOs(): Boolean = runCatching {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0,
        )?.activityInfo?.packageName?.startsWith(LIGHTOS) == true
    }.getOrDefault(false)

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
        // A throttled home start falls through to the global action rather than being called done:
        // an injected home key needs no grant and no start slot, and home is the one destination
        // where arriving twice costs nothing.
        if (Grants.canDrawOverlays(this) && start(intent, target = HOME_TARGET) == Start.Done) return true
        return runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }.getOrDefault(false)
    }

    /**
     * Starting an activity from a service is a background activity start, which Android 14
     * blocks unless the app holds the `SYSTEM_ALERT_WINDOW` appop — the same grant the
     * readout needs.
     *
     * Rate-limited, and the limit is the point rather than a nicety. The activity this most often
     * starts is a *launcher*, LightOS's, which runs as uid 1000 — and a launcher being restarted
     * repeatedly while it is showing something modal, an alarm say, is a system process being asked
     * to do something no user could ask it to do.
     *
     * The limit is **per destination**, which it did not used to be, and one flat second across all
     * of them was wrong in a way only a two-press sequence shows: home tapped once opens the app you
     * were in, and tapped again is supposed to move on to the fallback — a second press inside the
     * same second, which the throttle ate. So the same target keeps its full second, and a start
     * somewhere *else* only has to clear a much shorter floor. Repetition is what the guard is for,
     * and a different destination is not repetition. Mashing is still covered upstream: the same
     * binding twice inside [DEDUPE_MS] is one binding, and four times over stands the service down.
     *
     * Three answers, not two: a start the throttle declined is a different thing from one that
     * failed, and [launch] is where telling them apart matters.
     */
    private fun start(intent: Intent, target: String? = null): Start {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastStartAt
        val repeat = target != null && target == lastStartTarget
        if (gap < if (repeat) START_INTERVAL_MS else MIN_START_GAP_MS) {
            log("start dropped — ${if (repeat) "one a second" else "too soon"}")
            return Start.Throttled
        }
        lastStartAt = now
        lastStartTarget = target
        val ok = runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        return if (ok) Start.Done else Start.Failed
    }

    /** What became of an activity start. See [start]. */
    private enum class Start { Done, Throttled, Failed }

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

        /** How recently LightOS must have come forward to be read as its lock screen arriving. */
        const val LOCK_GRACE_MS = 2_000L

        /** Gap allowed between the two taps of a double tap. */
        const val DOUBLE_TAP_MS = 320L

        /**
         * Release-to-release gap for the home double press that ends a LightOS visit. Wider than
         * the wheel's window — a whole press sits inside it, not just a second click.
         */
        const val HOME_DOUBLE_MS = 600L

        /** Window in which the same binding twice over is one binding. See [act]. */
        const val DEDUPE_MS = 350L

        /** The same binding this many times inside this window and the service stands down. */
        const val MASH_PRESSES = 4
        const val MASH_WINDOW_MS = 4_000L

        /** Hands off for this long after anything last rang. See [ringing]. */
        const val RING_GRACE_MS = 30_000L

        /** One refusal line per reason per this window. See [logRefusal]. */
        const val REFUSAL_LOG_MS = 5_000L

        /** Minimum gap between two starts aimed at the *same* destination. See [start]. */
        /** One line per turn gesture, not per notch. See [logTurn]. */
        const val TURN_LOG_MS = 1_500L

        const val START_INTERVAL_MS = 1_000L

        /** Minimum gap between starts aimed at different destinations. See [start]. */
        const val MIN_START_GAP_MS = 250L

        /** Throttle keys for the destinations that aren't named by package. See [start]. */
        const val HOME_TARGET = "\u0000home"
        const val CAMERA_TARGET = "\u0000camera"
        /** Gap between taking the face down and launching, so the window is gone first. */
        const val LOCK_HANDOFF_MS = 120L

        /** How often to ask the keyguard whether the phone is open. See [lockWatch]. */
        const val LOCK_WATCH_MS = 300L

        /** Failed lock-face starts in a row before the face disarms itself. */
        const val MAX_LOCK_MISSES = 3

        /**
         * Playback usages that mean something is *demanding* the user — a screen with a dismiss
         * gesture on it that must own every key.
         *
         * `USAGE_NOTIFICATION` and `USAGE_NOTIFICATION_EVENT` were in this set and must never
         * come back. A notification ping is one second of sound that asks for nothing — but with
         * the 30-second grace window it turned every text message into half a minute of dead
         * keys: home passing through to LightOS, wheel dead, all of it refused upstream of every
         * log line. Intermittent, self-healing, and invisible — it cost days. The guard is for
         * alarms, ringing calls, and calls in progress, which are the things with a dismiss
         * gesture to protect.
         */
        val ringUsages = setOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )

        /** Stroke duration. Slow enough not to register as a fling, quick enough to keep up. */
        const val SWIPE_MS = 60L

        /** Windows that appear over an app without replacing it. */
        val transientPackages = setOf(
            "com.gios.lightcontrol",
            "com.android.systemui",
        )
    }
}
