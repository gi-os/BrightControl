package com.gios.lightcontrol.keys

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioManager
import android.content.ComponentName
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.gios.lightcontrol.Action
import com.gios.lightcontrol.Behavior
import com.gios.lightcontrol.Button
import com.gios.lightcontrol.Gesture
import com.gios.lightcontrol.Policy
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.TurnAction
import com.gios.lightcontrol.lock.Lock
import com.gios.lightcontrol.lock.LockCall
import com.gios.lightcontrol.lock.LockCallState
import com.gios.lightcontrol.lock.LockOverlay
import com.gios.lightcontrol.switcher.ForceStop
import com.gios.lightcontrol.switcher.Recents
import com.gios.lightcontrol.switcher.SwitcherOverlay
import com.gios.lightcontrol.switcher.appName

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
 * [Behavior] is hands-off. Light's own tools resolve to hands-off, because the wheel already
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

    /**
     * Whether the last window-state event came from an actual app screen.
     *
     * Carried so the delayed re-asserts inherit it. Without that, the guard in
     * [ColorMode.skipBaseline] would refuse the write on the event itself and then allow the
     * identical write 250 ms later, which is a bug that looks like a race and is not.
     */
    @Volatile
    private var lastWindowWasActivity = true

    /** When a wheel click went down while the switcher was up, for its hold. */
    private var switcherDownAt = 0L

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

    /** [ringing]'s last answer, and when it was worked out. See the note there. */
    private var lastRingCheckAt = 0L
    private var lastRingAnswer = false

    /** When an activity was last started from here, and where it was going. See [start]. */
    private var lastStartAt = 0L
    private var lastStartTarget: String? = null

    /** The synthetic finger that scrolls apps which don't understand the wheel. */
    private lateinit var swipe: WheelSwipe
    private lateinit var colorMode: ColorMode

    /** The Light face over the lock screen. A window this service owns, not an activity. */
    private lateinit var lockFace: LockOverlay

    /** The app switcher, opened by pressing home twice. Also a window, not an activity. */
    private lateinit var switcher: SwitcherOverlay

    /** Whether the phone is ringing or on a call, and how to answer. See [LockCall]. */
    private lateinit var lockCall: LockCall

    /** The call speaker's level, on the one route it is allowed to touch. See [CallAudio]. */
    private lateinit var callAudio: CallAudio

    /**
     * True while the face has stood down for a call in progress rather than been dismissed.
     *
     * The two are not the same and must not be stored the same way. A dismiss is the user saying
     * "not now" and is sticky until the next sleep; this is the face getting out of the way of the
     * dialer's own screen for the length of a call, and it has to come back by itself when the
     * call ends — the screen is still on and still locked, and nothing else would raise it.
     */
    private var lockStoodDownForCall = false

    /**
     * Whether the dialer's own call screen has already been asked for on this call.
     *
     * See [openCallScreen]. Cleared when the call ends, which is the only thing that makes the
     * next raise a different call rather than the same one twice.
     */
    private var callScreenOpened = false

    /**
     * Which apps you have been in, built from the window-state events this service already gets.
     * The only source of a recents order an unprivileged app has on this phone. See [Recents].
     */
    private lateinit var recents: Recents

    /** When the last short home press was released, for the double press. See [homeDouble]. */
    private var homeTapAt = 0L

    /** What the face is being held up over, while an unlock's launch lands. See [onUserPresent]. */
    private var coverTarget: String? = null
    /**
     * A home press claimed by the armed lock face, held until its release.
     *
     * The launch on the DOWN brings the app forward, which hides the face and clears `armed`
     * before the UP arrives -- and a home UP that then falls through to [onHome] is a lone home
     * release, which LightOS reads as a home press of its own and answers with its dashboard. So
     * once the DOWN is claimed, every event of that press is swallowed here through the UP,
     * whatever the face is doing by then.
     */
    private var armedHomeConsuming = false

    /** Held only so it can be unregistered. See [onUnbind]. */
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null

    /**
     * Which packages are cameras, memoised. Answering means a `PackageManager` query, and the
     * question is asked on the key event — so it is asked once per app and then remembered.
     * Cleared on unbind, which is also when an install would have had time to happen.
     */
    private val cameraPackages = HashMap<String, Boolean>()

    /** Which packages are clocks, memoised for the same reason. */
    private val alarmPackages = HashMap<String, Boolean>()

    /** Which packages can be opened at all, memoised. See [hasFrontDoor]. */
    private val frontDoors = HashMap<String, Boolean>()

    /**
     * Which packages ship a keyboard. Held as a whole set rather than per package, because the
     * system answers the whole question in one call, and re-read every [IME_CACHE_MS] so a
     * keyboard installed mid-session starts being recognised.
     */
    private var imePackages: Set<String>? = null
    private var imePackagesAt = 0L

    /** Which `package/class` pairs are activities, memoised — only asked of IME packages. */
    private val activityClasses = HashMap<String, Boolean>()

    /** One press in flight. Nothing but when it started — the release does the deciding. */
    private class Press(val downAt: Long)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        recents = Recents(prefs)
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
        switcher = SwitcherOverlay(this)
        // The list picks; the service launches. Every activity start in this app goes through one
        // throttle and one log line, and a window that started its own would be outside both.
        switcher.onPick = { pkg -> runCatching { log("switcher → ${pkg.substringAfterLast('.')}"); launch(pkg) } }
        switcher.onStop = { pkg -> runCatching { stopApp(pkg) } }
        switcher.onSystem = { runCatching { openSystemSwitcher() } }
        switcher.onAppInfo = { pkg -> runCatching { openAppInfo(pkg) } }
        // The deliberate hold-to-enter gesture reports here; the service owns where an unlock lands
        // (its resume list and snapshot), so the face only tells it the hold completed.
        lockFace.onEnter = { runCatching { homeFromLock() } }
        // The now-playing row on the face reports a tap here rather than starting anything itself.
        // Every activity start in this app goes through one throttle, one log line and one cover.
        lockFace.onOpenPlayer = { pkg -> runCatching { openFromLock(pkg) } }
        // A ringing phone is the one thing the face was hiding rather than drawing: it is a window
        // at layer 31, so it painted straight over the dialer's incoming-call screen. See
        // [onCallChanged].
        callAudio = CallAudio(this, allowed = { prefs.callBoost }, log = { line -> log(line) })
        lockCall = LockCall(this)
        lockCall.onChange = { state -> runCatching { onCallChanged(state) } }
        lockCall.onTick = { runCatching { callAudio.check() } }
        lockCall.start()
        lockFace.onAnswerCall = { runCatching { answerCall() } }
        lockFace.onDeclineCall = { runCatching { declineCall() } }
        // Per-app color. Captures the daltonizer baseline the first time it runs and drives it
        // from the front app thereafter. Inert unless colorAutoSwitch is on and the secure-
        // settings grant is present. See keys/ColorMode.kt.
        colorMode = ColorMode(this, prefs)
        // Our own settings screen, onto the recents list. Nothing else can put it there: window
        // events from this package are transient by policy, because the overlays this service
        // owns raise them too. See [OwnWindow.onResumed].
        OwnWindow.onResumed = { runCatching { recents.note(packageName) } }
        recoverForeground()
        runCatching { colorMode.applyFor(foreground) }
        scheduleColorReasserts()
        registerColorObserver()
        // The first-class version of "is the phone open", on the versions that have it. A listener
        // rather than only a broadcast, because ACTION_USER_PRESENT is the one signal in this
        // feature that has already been observed not to arrive, and a lock face that outlives the
        // unlock is the worst failure this app can produce.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
                    if (!locked) runCatching { onUserPresent() }
                }
                getSystemService(KeyguardManager::class.java)
                    ?.addKeyguardLockedStateListener(mainExecutor, listener)
                // Held so it can be handed back on unbind. A listener registered and never removed
                // survives the service that registered it: the system keeps calling into an
                // instance that has been unbound, and every rebind adds another one. Three
                // rebinds — a settings change, an update, toggling the service — and an unlock is
                // calling `onUserPresent` on four dead objects.
                keyguardListener = listener
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
     * Guess which app is in front, for the moment right after this service starts.
     *
     * Every release of this app rebinds the service, and a fresh service has seen no
     * window-state event — so `foreground` is null while something is plainly on screen. Per-app
     * color is driven from that package name, and with no name the rule cannot be applied: the
     * app on screen keeps whatever mode the phone was in until it is force-closed and reopened.
     * That is the whole of the "each update my apps go back and forth as to whether the colours
     * work, and force-closing brings them back" report, and it is not a colour bug at all.
     *
     * The guess is the last package the previous process saw, and it is only made while it is
     * **fresh** and the phone is **awake**. An app update happens with that app, or the store, in
     * front seconds earlier; a name from yesterday morning is not evidence of anything. Being
     * wrong costs one wrong colour rule until the next window change corrects it, which is why
     * this is allowed to guess at all — [ColorMode.applyFor] states the desired state rather than
     * toggling, so nothing here can be stranded.
     */
    private fun recoverForeground() {
        val (pkg, at) = prefs.lastFront() ?: return
        if (System.currentTimeMillis() - at > FRONT_MEMORY_MS) return
        val power = runCatching { getSystemService(PowerManager::class.java) }.getOrNull()
        if (power != null && !power.isInteractive) return
        foreground = pkg
        foregroundAt = SystemClock.uptimeMillis()
        log("service up · assuming ${pkg.substringAfterLast('.')} in front")
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
        val activityWindow = classIsActivity(event, pkg)
        if (
            Policy.isTransientWindow(
                pkg = pkg,
                isInputMethodPackage = isInputMethodPackage(pkg),
                classIsActivity = activityWindow,
                hasFrontDoor = hasFrontDoor(pkg),
            )
        ) {
            return
        }
        if (pkg != foreground) {
            previous = foreground
            foreground = pkg
            foregroundAt = SystemClock.uptimeMillis()
            // LightOS's own shell is left out on purpose: one press of home already goes there,
            // so a row for it could only ever be the slower way to do the same thing. This app's
            // own package needs no test here — [Policy.isTransientWindow] returned above for it,
            // because the overlay windows this service owns raise these events too. The settings
            // screen puts itself on the list from [OwnWindow], where an activity resuming is the
            // one signal that means what it says.
            if (!pkg.startsWith(LIGHTOS)) recents.note(pkg)
            // Written down for the next process. See [Prefs.lastFront] and [recoverForeground].
            runCatching { prefs.setLastFront(pkg, System.currentTimeMillis()) }
            // The app an unlock was aimed at has arrived, so the face has nothing left to hide.
            if (pkg == coverTarget) dropCover()
        }
        // Re-asserted on every event rather than only when the package changes, so the screen
        // heals itself. ColorMode.set() writes only on a difference, so for the overwhelmingly
        // common case — same app, already the right color — this costs two reads of a secure
        // setting and writes nothing.
        //
        // What it buys: anything that moves the daltonizer while an app stays in front no longer
        // strands it. Before, color was strictly edge-triggered, so a single missed edge lasted
        // until you switched apps and back, and there was no way for the app to notice.
        // The window's own kind is passed along: a floating window from a package with no color
        // rule must not be allowed to restore the baseline over the app in front. See
        // [ColorMode.skipBaseline].
        lastWindowWasActivity = activityWindow
        runCatching { colorMode.applyFor(pkg, realScreen = activityWindow) }
        // ...and again, shortly. A window-state event is raised when the app's window arrives,
        // which is *before* whatever LightOS does about color on the way out of its own shell.
        // Whoever writes last wins, and on the LightOS -> app path that was not this app: the
        // rule was applied and then painted over within the same second, which is exactly the
        // shape of "it only works if I come back to the app from somewhere else". These
        // re-asserts write nothing when nothing moved, so the cost of being wrong about the race
        // is two reads of a secure setting.
        scheduleColorReasserts()
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
        armedHomeConsuming = false
        homeTapAt = 0L
        // A switcher that survives the screen going off is a black window an unlock lands on.
        runCatching { switcher.hide() }
        handler.removeCallbacks(lockWatch)
        handler.removeCallbacks(coverTimeout)
        coverTarget = null
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
        runCatching { colorMode.applyFor(foreground) }
        scheduleColorReasserts()
        if (!prefs.lockScreen) return
        // Not if a tap put it away. Re-raising a face the user just dismissed to reach the keypad
        // would make the keypad unreachable, which is the one bug this feature must never have.
        if (!lockFace.dismissed()) showLockFace()
        // Start dark and fade up, so the fastest unlock never sees a lock screen at all.
        if (lockFace.showing) runCatching { lockFace.wake() }
        // The watch belongs to the screen being on, not to the face being up. See [lockWatch].
        handler.removeCallbacks(lockWatch)
        if (lockFace.showing) handler.postDelayed(lockWatch, LOCK_WATCH_MS)
    }

    /**
     * The phone is open. Take the face down, then go where Resume would have gone.
     *
     * Handled here rather than in the activity because the activity may well have been stopped by
     * the bouncer that was just dismissed — see [screenOff]. Dismiss first and launch on the next
     * loop: an activity start aimed at a task that is still tearing down is the one shape of this
     * that reliably lands behind the wrong window.
     */
    /**
     * The phone is open. Launch first, and keep the face up until the app is actually in front.
     *
     * The order is the fix for the flash of LightOS on every unlock. Taking the face down first
     * uncovers whatever the *system* put in front, which on this phone is LightOS — it holds the
     * HOME role and comes forward the instant the keyguard goes — and our launch then arrives over
     * the top of it a beat later. Two screens for one unlock.
     *
     * The face is a window at layer 31, so it is already above everything the handover involves.
     * Leaving it there costs nothing and hides the whole transition: what the user sees is the lock
     * face, then the app. It comes down when the target reports itself in front (see
     * [onAccessibilityEvent]) or when [LOCK_COVER_MAX_MS] runs out, whichever is first — a cover
     * with no deadline is the same bug as a face that will not go.
     */
    private fun onUserPresent() {
        val wasUp = lockFace.showing || lockFace.dismissed()
        handler.removeCallbacks(lockWatch)
        if (!wasUp) return
        // Unlocked -- but do not rip the face away. The keyguard authenticated in the background
        // and the phone is open, yet the notifications on the face are the reason it exists, and an
        // unlock that launches an app in the same instant is an unlock nobody got to read. So hold
        // the face up, armed, and go in only on a deliberate press-and-hold (see [LockOverlay]).
        // The app cannot see the fingerprint sensor itself, so the hold is on the glass, not the
        // button. Off -> the old behavior, launch the moment the phone opens.
        if (prefs.lockHoldToEnter && lockFace.showing) {
            runCatching { lockFace.armEnter() }
            return
        }
        enterFromLock()
    }

    /**
     * Leave the face and go where Resume would have gone.
     *
     * Was the tail of [onUserPresent]; split out so the deliberate hold-to-enter gesture and the
     * old launch-on-unlock both land in the same place. Launch first and keep the face up until the
     * app is in front -- taking the cover down first uncovers whatever the system put forward (on
     * this phone, LightOS), which is the flash this ordering exists to hide. See [dropCover].
     */
    private fun enterFromLock() {
        val target = runCatching { resumeFromLock() }.getOrNull()
        // Nothing was launched, or the face was already gone: no transition left to cover.
        if (target == null || !lockFace.showing) {
            dropCover()
            return
        }
        coverTarget = target
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, LOCK_COVER_MAX_MS)
    }

    /**
     * A deliberate "go in" from the armed face -- the touch hold, or the home button.
     *
     * Different from [enterFromLock]/[resumeFromLock] in what it falls back to. The auto-resume
     * path is list-gated on purpose: it fires on the unlock itself, so its safe default is
     * LightOS. But a hold or a home press is the user asking to *leave* the face on purpose, and
     * their home is their launcher (Luma) or the app they were in -- never LightOS's dashboard,
     * which is all `goHome()` can resolve to while LightOS holds the HOME role.
     *
     * So the destination is: the app you slept in (returned to even when it is not on the resume
     * list, which is what makes going back to Luma work), then the configured "Otherwise open"
     * launcher, then the home button's own tap target, and LightOS only when nothing else is set.
     */
    private fun homeFromLock() {
        val was = Lock.pending
        val sleptPkg = was?.takeUnless { it == foreground || it.startsWith(LIGHTOS) }
        val fallbackPkg = (prefs.resumeFallback as? Action.Launch)?.pkg
        val tapPkg = (prefs.action(Button.Home, Gesture.Tap) as? Action.Launch)?.pkg
        val target = sleptPkg ?: fallbackPkg ?: tapPkg
        Lock.pending = null
        if (sleptPkg != null) slept = null
        val ok = if (target != null) launch(target) else goHome()
        log("lock home → " + (target?.substringAfterLast('.') ?: "lightos") +
            if (ok) "" else " · FAILED")
        if (!ok || target == null || !lockFace.showing) {
            dropCover()
            return
        }
        coverTarget = target
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, LOCK_COVER_MAX_MS)
    }

    /**
     * The now-playing row was tapped. Open the player it named.
     *
     * Simpler than [homeFromLock] because there is no rule to resolve: the row says which package
     * is making the sound, and that is the app the tap means. Reached only on an unlocked face --
     * the row checks the arming before it calls -- so this is an ordinary launch, held under the
     * same cover as every other way out of the face so LightOS does not flash between the two.
     */
    private fun openFromLock(pkg: String) {
        Lock.pending = null
        if (slept == pkg) slept = null
        val ok = launch(pkg)
        log("lock media \u2192 " + pkg.substringAfterLast('.') + if (ok) "" else " \u00b7 FAILED")
        if (!ok || !lockFace.showing) {
            dropCover()
            return
        }
        coverTarget = pkg
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, LOCK_COVER_MAX_MS)
    }

    /** Take the cover down and stop waiting for anything to appear under it. */
    private fun dropCover() {
        coverTarget = null
        handler.removeCallbacks(coverTimeout)
        val gone = runCatching { lockFace.hide() }.getOrDefault(false)
        if (!gone) log("unlocked · face WOULD NOT GO")
    }

    private val coverTimeout = Runnable {
        log("cover timed out")
        dropCover()
    }

    /**
     * Ask, three times a second, whether the phone has been opened.
     *
     * Inelegant and deliberate. Two signals should already have answered — `ACTION_USER_PRESENT`
     * and the keyguard's own locked-state listener — and the face has twice now been left on
     * screen over an unlocked phone because the one signal in play did not arrive.
     * `KeyguardManager.isDeviceLocked` is not a notification that can be missed; it is the state
     * itself.
     *
     * **Only while the screen is on**, which is the whole reason this is safe. Started from
     * `ACTION_SCREEN_ON` and stopped by `ACTION_SCREEN_OFF`, so it runs for the few seconds
     * between picking the phone up and opening it — never for the eight hours it spends on a
     * bedside table. Tying it to the face being up instead, as it first was, meant a handler loop
     * ticking three times a second all night in a process the system is not allowed to freeze.
     * That is the shape of bug that has taken this phone down before; see LightGlance.
     */
    private val lockWatch = object : Runnable {
        override fun run() {
            if (!lockFace.showing || !interactive()) return
            val km = runCatching { getSystemService(KeyguardManager::class.java) }.getOrNull()
            if (km != null && !km.isDeviceLocked) {
                onUserPresent()
                return
            }
            handler.postDelayed(this, LOCK_WATCH_MS)
        }
    }

    private fun interactive(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(false)

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
        // **Answered from cache for a quarter of a second.** This runs inside `onKeyEvent`, and an
        // accessibility filter's `onKeyEvent` is *blocking* -- the input dispatcher holds the key
        // until it returns. The two lines below are binder calls, `activePlaybackConfigurations`
        // returning a list of parcelables, and they ran twice per press, on the way down and on
        // the way up. On a single press nobody notices. Held or spammed, the volume keys repeat
        // faster than four binder round trips take, and the volume climbs in steps you can count.
        //
        // Nothing can be missed by this. The busy answer already carries a thirty-second grace
        // below; this caches the *quiet* one, and an alarm that starts ringing does not need
        // dismissing within 250 ms of the last time we looked.
        if (now - lastRingCheckAt < RING_CHECK_MS) return@runCatching lastRingAnswer
        lastRingCheckAt = now
        // Still inside the grace window from the last thing that rang. Sampling only at key events
        // means the moment an alarm is *silenced* looks identical to silence, while the screen with
        // the "stop" button on it is still up and being pressed at. Half a minute of hands-off
        // after a ring costs nothing and covers the whole of that.
        if (lastRingAt != 0L && now - lastRingAt < RING_GRACE_MS) {
            lastRingAnswer = true
            return true
        }
        val audio = getSystemService(AudioManager::class.java) ?: run {
            lastRingAnswer = false
            return false
        }
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
        lastRingAnswer = busy
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
        if (faults < MAX_FAULTS) return false
        // **It comes back by itself.** Standing down until somebody opens the app made sense while
        // the only way to notice was to open the app; it does not make sense as the resting state of
        // a phone's buttons. Three throws inside a minute is still a reason to go quiet — whatever
        // is wrong, doing it again immediately will not fix it — but a minute later the keys are
        // worth another try, because the alternative is a wheel that is dead until somebody
        // remembers this app exists.
        //
        // Sticky is still available for anybody who wants it, on the same switch as the mash guard.
        if (prefs.standDownOnMash) return true
        if (SystemClock.uptimeMillis() - lastFaultAt < RECOVER_MS) return true
        faults = 0
        prefs.setFault("recovered by itself after a quiet minute", false)
        log("faults cleared · trying again")
        return false
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
        runCatching { switcher.hide() }
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
        // The switcher owns every key it can use while it is up. It is a full-screen window over
        // whatever you were doing, so a key that fell through it would act on an app nobody can
        // see. Volume is the exception, because volume is never about what is on screen.
        if (switcher.showing) return onSwitcherKey(key, event)

        val behavior = Policy.behaviorFor(prefs, front)

        if (key == LightKey.WheelUp || key == LightKey.WheelDown) {
            val notches = if (key == LightKey.WheelUp) 1 else -1
            return onTurn(front, behavior, notches, event.action == KeyEvent.ACTION_DOWN)
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

        // Our own settings screen, with the wheel driving its rows: the click is how you open the
        // highlighted one, so it has to reach the activity. It did not — this app resolves to the
        // scroll-aware rule, which passes turns through and keeps the press, and the press's
        // default binding is the torch. So the wheel moved the highlight and the click turned on
        // the flashlight. Declining the key here is the whole fix; the activity reads it.
        if (button == Button.WheelClick && prefs.wheelCursor && OwnWindow.resumed) {
            if (isFreshDown(event)) log("WheelClick · ours to select with")
            return false
        }

        // The lock face is up and armed -- the phone is already unlocked and the face is being
        // held open to be read. A home press there means "go in now", exactly like finishing the
        // touch hold. Take the whole press (down and the release) so LightOS does not get a lone
        // home release and pull its dashboard up behind our cover. Only Home; the wheel and camera
        // button are left alone.
        if (button == Button.Home && (armedHomeConsuming || (lockFace.showing && lockFace.armed))) {
            if (isFreshDown(event) && !armedHomeConsuming) {
                log("HOME · enter from armed lock")
                armedHomeConsuming = true
                homeFromLock()
            }
            // Claimed to the end: the release is swallowed too, even though the app that just
            // opened has already taken the face down and cleared `armed`. An unconsumed home UP
            // here is the lone release that sends LightOS to its dashboard right after the app.
            if (event.action == KeyEvent.ACTION_UP) armedHomeConsuming = false
            return true
        }

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
        if (!fresh && presses.containsKey(button)) return onButton(button, behavior, event)

        // The home button is the one key the phone cannot do without, so its door comes *before*
        // the hands-off gate. [onHome] holds the complete refusal list, and a hands-off app in
        // front is one of its reasons to decline — but declining has to mean shadow mode, where
        // the tap still fires on top, and a tap that names its own destination still takes the
        // key. The gate below used to eat the key first, which made LightOS's screens exactly the
        // place the tap stopped working: wake the phone, land on the dashboard, and the press that
        // should have opened your launcher belonged to LightOS alone — the idle face again, with
        // nothing left anywhere to leave it by. See [onHome].
        if (button == Button.Home) return onHome(front, behavior, event)
        // Hands off — with one exception, and it has its own switch. The camera button is the
        // one key whose whole purpose is to open something *from the home screen*, which on this
        // phone is LightOS: gating it there meant the binding could only fire in the places
        // nobody presses it. "I rebound the camera button and it refuses to acknowledge my
        // change" is the report this exists for, and it was right — the setting saved, and then
        // never applied where the thumb was. See [Behavior.cameraActive].
        if (!behavior.buttonsActive && !(button == Button.Camera && behavior.cameraActive)) {
            if (fresh) log("${button.name} hands off")
            return false
        }
        // A camera has first claim on the camera button. See [ownsCameraKey].
        if (button == Button.Camera && ownsCameraKey(front)) return false
        return onButton(button, behavior, event)
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
    private fun onHome(front: String?, behavior: Behavior, event: KeyEvent): Boolean {
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
            !behavior.buttonsActive && !tap.picksDestination -> "hands off"
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
        return onButton(Button.Home, behavior, event)
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
        // Note the missing `if (!tap.acts) return false` this used to open with. The double press
        // is a gesture of this key rather than of its binding, so it has to be counted even when
        // the tap does nothing — an unbound home button is the commonest way to have one.
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
                    // The switcher instead of the tap, when this release is the second of two.
                    // LightOS still saw the press — nothing is consumed here — so it has gone
                    // home underneath, and the list is drawn over the top of that.
                    if (!homeDouble() && tap.acts) perform(tap)
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

    // ------------------------------------------------------------------ the app switcher

    /**
     * Was this home release the second of two? If so, the switcher is now up.
     *
     * **The first press is never held back.** Reading a double press the usual way means waiting
     * out the window before acting on the first one, and on this key that is a third of a second
     * added to the gesture a phone is used with most. So home fires the instant it is released,
     * every time, and a second release inside [HOME_DOUBLE_MS] opens the switcher over whatever
     * the first press landed on. What it costs is a glimpse of home on the way to the list. What
     * it buys is a home button that still feels like a button.
     *
     * Returns false — meaning "this was an ordinary tap" — whenever the switcher could not be
     * shown, including when there is nothing to show. A gesture that swallows the press and then
     * produces no window is the failure this whole file is written around.
     */
    private fun homeDouble(): Boolean {
        if (!prefs.homeDoubleSwitcher) return false
        val now = SystemClock.uptimeMillis()
        val first = homeTapAt
        homeTapAt = now
        if (first == 0L || now - first >= HOME_DOUBLE_MS) return false
        homeTapAt = 0L
        // Logged with the gap. A double press that does not open the list has three possible
        // causes — the second press fell outside the window, the phone was not awake, or the
        // window failed to be added — and from the phone they are indistinguishable without this.
        log("HOME double · ${now - first}ms")
        return openSwitcher()
    }

    /**
     * Put the list of recent apps up. False if there is nothing to put up.
     *
     * Never while locked or with the screen off: the list is a window at layer 31, so it would
     * happily draw over the keyguard, and a lock screen is not a thing this app covers with a way
     * into every app on the phone.
     */
    private fun openSwitcher(): Boolean {
        if (!awake()) return false
        if (switcher.showing) return true
        val front = if (OwnWindow.resumed) packageName else foreground
        // Only what you are looking at is left out. This app used to be excluded outright, which
        // is right while its settings are the front app — `front` already says so — and wrong
        // every other time: light-reports#47 asks for the row, and a switcher that cannot switch
        // back to the app you switched away from is missing the obvious entry.
        val skip = setOfNotNull(front)
        // The first notch after the list opens always lands. See [moveSwitcher].
        lastSwitcherMoveAt = 0L
        val list = runCatching {
            // As many as fit, asked of the window rather than fixed here: the row that does not
            // fit is the one furthest back, which is the one a switcher exists for.
            recents.entries(packageManager, skip, switcher.capacity()) { appName(this, it) }
        }.getOrDefault(emptyList())
        // Anything the lock face is holding up has to come down first, the same as for a launch:
        // layer 31 is layer 31, and two windows there is a coin toss nobody wins.
        if (lockFace.showing) runCatching { lockFace.dismiss() }
        val up = runCatching { switcher.show(list) }.getOrDefault(false)
        log("HOME double · switcher ${if (up) "${list.size} apps" else "FAILED"}")
        return up
    }

    /**
     * Force stop an app from the switcher, off the main thread.
     *
     * A thread rather than the handler, because the adb path opens a socket and waits for a
     * command to exit — and this service's main thread is the one key events are dispatched on.
     * An accessibility filter that blocks is a phone whose buttons have stopped answering.
     *
     * What comes back is reported rather than assumed. [ForceStop] can do the real thing or only
     * the weaker fallback, and the difference matters to somebody stopping an app *because* it is
     * misbehaving: "stopped" and "backgrounded" are not the same promise.
     */
    private fun stopApp(pkg: String) {
        val label = appName(this, pkg)
        // Never this app. Now that it has a row of its own, a hold on that row would otherwise
        // kill the process this service runs in — the phone's key filter, its lock face and its
        // colour, stopped by a gesture meant to tidy up a misbehaving app.
        if (pkg == packageName) {
            runCatching {
                switcher.stopped(pkg, "CANNOT STOP $label · it is the key filter", gone = false)
            }
            return
        }
        Thread {
            val result = runCatching { ForceStop.stop(this, pkg) }
                .getOrDefault(ForceStop.Result.Failed)
            handler.post {
                val note = when (result) {
                    ForceStop.Result.Stopped -> "STOPPED $label"
                    ForceStop.Result.Backgrounded -> "BACKGROUNDED $label · no adb for a full stop"
                    ForceStop.Result.Failed -> "COULD NOT STOP $label"
                }
                log("switcher stop ${pkg.substringAfterLast('.')} · ${result.name}")
                if (result != ForceStop.Result.Failed) recents.forget(pkg)
                runCatching {
                    switcher.stopped(pkg, note, gone = result != ForceStop.Result.Failed)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Ask the platform for its own recents, and be honest about what happened.
     *
     * `performGlobalAction` reports that the action was *injected*, not that anything appeared, and
     * on this phone the two have never been the same thing — which is why the home button draws its
     * own list instead. So this asks, waits, and then reads the only evidence available from a
     * service that cannot enumerate windows: whether any package came to the front. Nothing came
     * forward means nothing was there, and the list comes back with a line saying so rather than
     * leaving somebody looking at the app they were trying to leave.
     *
     * The window goes down *before* the ask. It is layer 31; a recents screen is an activity, and
     * an activity underneath this window is the whole reason the switcher exists.
     */
    private fun openSystemSwitcher() {
        val list = switcher.snapshot()
        val wasPkg = foreground
        val wasAt = foregroundAt
        runCatching { switcher.hide() }
        val asked = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
        log("switcher · system recents " + if (asked) "asked" else "REFUSED")
        if (!asked) {
            restoreSwitcher(list, "NO SYSTEM SWITCHER · the phone refused")
            return
        }
        handler.postDelayed({
            if (foreground != wasPkg || foregroundAt != wasAt) {
                log("switcher · system recents came up")
                return@postDelayed
            }
            log("switcher · system recents drew nothing")
            restoreSwitcher(list, "NOTHING CAME UP · no system switcher here")
        }, SYSTEM_RECENTS_GRACE_MS)
    }

    /**
     * The system's App info page for [pkg] — where AOSP keeps a Force stop that needs no adb.
     *
     * The answer to the switcher's hold only being able to background an app on a phone with no
     * paired shell. Two taps from here, and the button on that page is the real one.
     */
    private fun openAppInfo(pkg: String) {
        val list = switcher.snapshot()
        runCatching { switcher.hide() }
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { startActivity(intent); true }.getOrDefault(false)
        log("switcher app info ${pkg.substringAfterLast('.')}" + if (ok) "" else " · FAILED")
        if (!ok) restoreSwitcher(list, "COULD NOT OPEN APP INFO")
    }

    /** Put the list back after a way out that led nowhere, with the reason on it. */
    private fun restoreSwitcher(list: List<SwitcherOverlay.Entry>, note: String) {
        if (!interactive()) return
        val up = runCatching { switcher.show(list) }.getOrDefault(false)
        if (up) runCatching { switcher.note(note) }
    }

    /**
     * The keys, while the switcher is up.
     *
     * Consuming here is safe in the way it usually is not: this window covers the screen, so the
     * app underneath cannot be reached by the key anyway, and the list closes itself after a few
     * idle seconds even if every one of these is somehow missed.
     *
     * The camera button closes rather than opens the camera. Starting a viewfinder *behind* a
     * full-screen overlay is a bug this app has already shipped once (see [perform]), and one
     * press to get out followed by another to open it is both obvious and impossible to get wrong.
     */
    private fun onSwitcherKey(key: LightKey, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        return when (key) {
            // Turning towards the top of the phone moves the selection up the list.
            LightKey.WheelUp -> { if (down) moveSwitcher(-1); true }
            LightKey.WheelDown -> { if (down) moveSwitcher(1); true }
            // Tap opens the selection; holding it stops that app — the same pair the rows offer
            // a thumb. Timed at the release like every other hold in this service: one that fired
            // mid-press would stop an app while the button was still down and then hand the rest
            // of the press to a list that had changed underneath it.
            LightKey.WheelClick -> {
                if (down && event.repeatCount == 0) switcherDownAt = SystemClock.uptimeMillis()
                if (up) {
                    val started = switcherDownAt
                    switcherDownAt = 0L
                    val held = started != 0L && SystemClock.uptimeMillis() - started >= HOLD_MS
                    val pkg = switcher.selected
                    if (held && pkg != null) runCatching { stopApp(pkg) }
                    else runCatching { switcher.choose() }
                }
                true
            }
            LightKey.Home -> {
                if (up) {
                    log("switcher closed · home")
                    runCatching { switcher.hide() }
                }
                true
            }
            LightKey.Camera, LightKey.Focus -> {
                if (up) runCatching { switcher.hide() }
                true
            }
            // Volume is about the phone, not about what is on screen.
            LightKey.VolumeUp, LightKey.VolumeDown -> false
        }
    }

    /**
     * Move the switcher's selection, at most one row per [Prefs.switcherStepMs].
     *
     * The wheel emits a whole DOWN/UP pair per detent, 35–60 ms apart (see [LightKeys]), and one
     * row per pair against a list of [SWITCHER_MAX] means an ordinary flick laps it two or three
     * times before your eye catches up — which is exactly what light-reports#47 describes. A
     * floor is the only thing that helps: the hardware will not send fewer notches, so the list
     * has to take fewer of them.
     *
     * The settings screens solved the same problem in [com.gios.lightcontrol.ui.WheelCursor] by
     * scrolling to the selection instead of counting rows, which cannot apply here — the switcher
     * is eight rows on one screen with nothing to scroll. So this is the same idea spent on time
     * rather than on distance, and the interval is a preference for the same reason the swipe
     * distance is one: what reads as fast is a fact about the hand, not about the phone.
     */
    private fun moveSwitcher(delta: Int) {
        val now = SystemClock.uptimeMillis()
        val step = prefs.switcherStepMs
        // Dropped, never queued. A notch that arrives inside the window is a notch nobody saw the
        // result of, and replaying it late would move the list after the hand had stopped.
        if (lastSwitcherMoveAt != 0L && now - lastSwitcherMoveAt < step) return
        lastSwitcherMoveAt = now
        runCatching { switcher.move(delta) }
    }

    /** When the switcher's selection last moved. Zero means "the next notch goes through". */
    private var lastSwitcherMoveAt = 0L

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
    /**
     * Whether this package ships a keyboard.
     *
     * Asked of the installed IME list rather than a list of package names, for the same reason
     * [ownsCameraKey] asks what an app declares: it keeps working for keyboards that do not
     * exist yet, including whichever one the user installs next. The whole list is read at once
     * and held for [IME_CACHE_MS], so a keyboard installed mid-session is picked up without
     * this costing a system call per window change.
     */
    private fun isInputMethodPackage(pkg: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (imePackages == null || now - imePackagesAt > IME_CACHE_MS) {
            imePackages = runCatching {
                getSystemService(InputMethodManager::class.java)
                    ?.inputMethodList
                    ?.mapNotNull { it.packageName }
                    ?.toSet()
            }.getOrNull().orEmpty()
            imePackagesAt = now
        }
        return pkg in imePackages.orEmpty()
    }

    /**
     * Whether the event's class is a real activity of [pkg], which is what separates a keyboard
     * package's settings screen from its soft-input window. The soft-input window reports a
     * window class, not an activity, so this comes back false for it and the event is treated as
     * the keyboard floating over whatever is underneath.
     */
    private fun classIsActivity(event: AccessibilityEvent, pkg: String): Boolean {
        val cls = event.className?.toString()?.takeIf { it.isNotBlank() } ?: return false
        activityClasses["$pkg/$cls"]?.let { return it }
        val isActivity = runCatching {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        }.getOrDefault(false)
        activityClasses["$pkg/$cls"] = isActivity
        return isActivity
    }

    /**
     * Whether this package can be opened from a launcher, memoised.
     *
     * "Can be opened" rather than "is installed", because that is the question that separates an
     * app from a dialog. MediaProvider, the permission controller and every other system component
     * that puts a confirmation over what you are doing have no front door — and the album's delete
     * prompt being read as a new app in front is what put a colour album back to monochrome
     * mid-delete.
     *
     * Home counts as a front door, which is load-bearing rather than thorough: LightOS declares
     * `CATEGORY_HOME` and no `CATEGORY_LAUNCHER`, and calling the phone's own shell transient would
     * stop this service tracking the package every one of its key rules is written against.
     *
     * Asked once per package and remembered, like the camera and clock answers, because it runs on
     * the window-state event.
     */
    private fun hasFrontDoor(pkg: String): Boolean {
        frontDoors[pkg]?.let { return it }
        val open = runCatching {
            if (packageManager.getLaunchIntentForPackage(pkg) != null) return@runCatching true
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(pkg)
            packageManager.resolveActivity(home, 0) != null
        }.getOrDefault(true) // Unreadable is not a reason to stop tracking an app.
        frontDoors[pkg] = open
        return open
    }

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
     * An explicit per-app rule still wins — [Behavior.buttonsActive] is checked first — so a
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

    // ------------------------------------------------------------------- color, held

    /** Held only so it can be unregistered. See [registerColorObserver]. */
    private var colorObserver: ContentObserver? = null

    /** Re-assert the front app's color rule. Posted, so it can be cancelled and coalesced. */
    private val colorReassert = Runnable {
        runCatching { colorMode.applyFor(foreground, realScreen = lastWindowWasActivity) }
    }

    /**
     * The same work, as a second object, so the two schedulers cannot cancel each other.
     *
     * `Handler.removeCallbacks` matches on the Runnable instance, so one shared object made the
     * settings observer and [scheduleColorReasserts] a single queue with one entry in it. They
     * want different things — the observer wants the latest change coalesced, the train wants
     * three fixed posts kept — and neither can have both from one identity.
     */
    private val colorObserverReassert = Runnable {
        runCatching { colorMode.applyFor(foreground, realScreen = lastWindowWasActivity) }
    }

    /**
     * Watch the two daltonizer settings and put the front app's rule back whenever anything else
     * moves them.
     *
     * The feature was previously driven by window-state events alone, so this app only ever got
     * to state the color at the instant an app came forward. Anything that wrote the settings
     * afterwards owned the screen until the next app switch — and LightOS writes them, every time
     * its own shell comes forward, because monochrome is how the whole phone is meant to look. Go
     * LightOS -> app and the last writer was LightOS; go app -> Android settings -> app and the
     * last writer was this service. One path worked and the other did not, and it looked like the
     * grant, or the write, or the rule.
     *
     * An observer closes it: the rule is re-stated whenever the state it describes stops being
     * true, from whatever direction. It cannot loop on this app's own writes, because
     * `ColorMode.set` writes only on a difference — the write wakes the observer once, the
     * re-assert finds both values already correct, and nothing further is written.
     */
    private fun registerColorObserver() {
        runCatching {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    // Coalesced: a mode/enabled pair arrives as two changes a millisecond apart,
                    // and re-asserting between them would fight a half-written state.
                    //
                    // Its own Runnable, and that is the whole of light-reports#37/38/44/45. Every
                    // Color write ends in ColorMode.nudge(), which writes ENABLED twice on
                    // purpose — and those writes wake this observer, whose removeCallbacks then
                    // cancelled the 800 ms and 2000 ms re-asserts scheduled by
                    // [scheduleColorReasserts] a moment earlier. Two seconds of cover collapsed
                    // to a single post at +120 ms, which is before LightOS has finished
                    // repainting. Cancelling now only ever cancels this observer's own post.
                    handler.removeCallbacks(colorObserverReassert)
                    handler.postDelayed(colorObserverReassert, COLOR_SETTLE_MS)
                }
            }
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ColorMode.ENABLED), false, observer,
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(ColorMode.MODE), false, observer,
            )
            colorObserver = observer
        }
    }

    /** Re-state the rule a few times over the second after an app arrives. See the call site. */
    private fun scheduleColorReasserts() {
        for (delay in COLOR_REASSERT_MS) handler.postDelayed(colorReassert, delay)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { unregisterReceiver(screenOff) }
        colorObserver?.let { observer ->
            colorObserver = null
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        Lock.pending = null
        OwnWindow.onResumed = null
        handler.removeCallbacks(colorReassert)
        handler.removeCallbacks(colorObserverReassert)
        handler.removeCallbacks(lockWatch)
        handler.removeCallbacks(coverTimeout)
        coverTarget = null
        runCatching { lockFace.hide() }
        runCatching { lockCall.stop() }
        lockStoodDownForCall = false
        callScreenOpened = false
        // Both windows this service owns come down with it. One left behind is a black screen
        // with nothing bound to the keys that would have closed it.
        runCatching { switcher.hide() }
        keyguardListener?.let { listener ->
            keyguardListener = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    getSystemService(KeyguardManager::class.java)
                        ?.removeKeyguardLockedStateListener(listener)
                }
            }
        }
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
        frontDoors.clear()
        alarmPackages.clear()
        activityClasses.clear()
        imePackages = null
        // Deliberately NOT restoring the color baseline here.
        //
        // An unbind is almost never "the user turned this off" — it is an app update, a reboot,
        // or the system recycling the service. Forcing the baseline on the way out meant an
        // update repainted the phone mono, and because color is otherwise only applied when the
        // front *package changes*, an app that was already on screen never got it back: the new
        // process starts with no idea what is in front, and the app it is looking at raises no
        // new window-state event. Roll went black and white and stayed there.
        //
        // Switching the feature off is the case restoreBaseline exists for, and that is now
        // where it is called from — see ui/ColorScreens.kt.
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
    private fun onButton(button: Button, behavior: Behavior, event: KeyEvent): Boolean {
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
                // Home, twice, quickly — the switcher, and nothing else this release. The first
                // press already went home; repeating it under the list would only fight it.
                if (!held && button == Button.Home && homeDouble()) return true
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
        // Four presses of the same binding inside four seconds. The original reading was that
        // somebody pressing a button over and over is somebody whose phone is not doing what they
        // asked, and the useful thing a key filter can do about that is stop — it came out of a run
        // of presses that ended with an activity being started at a launcher over and over.
        //
        // The reading was wrong more often than it was right. Four presses of the flashlight in
        // four seconds is a flashlight being used; four presses of home is walking through a menu.
        // Both stood the whole service down, which took the wheel, the buttons and the lock face
        // with them — and standing down is *sticky*, so the phone stayed like that until somebody
        // opened this app and noticed. A guard whose false positive is "your phone's buttons have
        // stopped working" has to be worth more than this one is.
        //
        // So it is off unless asked for. See [Prefs.standDownOnMash].
        if (action == lastAction && now - runStartedAt < MASH_WINDOW_MS) {
            sameActionRun++
            if (sameActionRun >= MASH_PRESSES) {
                sameActionRun = 0
                if (prefs.standDownOnMash) {
                    faults = MAX_FAULTS
                    lastFaultAt = now
                    prefs.setFault("${action.store()} $MASH_PRESSES times over — stood down", true)
                    log("${button.name} MASH — stood down")
                    return
                }
                // Logged and carried on. The line is worth keeping: a genuine loop looks exactly
                // like this in the key log, and now it is the only place it shows up.
                log("${button.name} MASH — kept going")
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
    private fun perform(action: Action): Boolean {
        // Anything that brings something to the front has to take the lock face with it. The window
        // is at layer 31, above even an app that has just been started, so the camera button used to
        // open the camera *behind* it: the shutter worked and the viewfinder was invisible. The
        // torch and the volume keys are deliberately not in this set — they change nothing about
        // what is on screen, and putting the face away for them would mean the lock screen
        // disappearing whenever you found the flashlight in the dark.
        if (action.needsActivityStart && lockFace.showing) {
            log("lock face away for ${action.store()}")
            runCatching { lockFace.dismiss() }
        }
        return performAction(action)
    }

    private fun performAction(action: Action): Boolean = when (action) {
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
        behavior: Behavior,
        notches: Int,
        down: Boolean,
    ): Boolean {
        return when (behavior.bareTurn) {
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

    // -------------------------------------------------------------------- calls

    /**
     * The phone started ringing, was answered, or the call ended.
     *
     * Three states, three different right answers:
     *
     *  - **Ringing.** Put the card on the face, and make sure the face is up — a call can arrive
     *    on a phone whose screen was off, and the card is the only thing on this screen that can
     *    answer it.
     *  - **Answered.** Stand the face down. LightOS's in-call screen has mute, speaker, the keypad
     *    and hang up on it, all of which are underneath a window at layer 31, and none of which is
     *    worth re-drawing badly. This is a plain [LockOverlay.hide], deliberately not
     *    [LockOverlay.dismiss] — a dismiss is sticky and would cost the face the rest of the day.
     *  - **Over.** Bring it back, if the phone is still locked and still awake. Nothing else will:
     *    the screen never went off, so there is no `ACTION_SCREEN_ON` coming.
     */
    private fun onCallChanged(state: LockCallState?) {
        callAudio.onCall(state)
        if (!prefs.enabled || !prefs.lockScreen || !prefs.lockCalls) {
            runCatching { lockFace.setCall(null) }
            // With the card switched off, the face still cannot be allowed to sit on top of a
            // ringing phone. It gets out of the way for the whole call instead, and comes back the
            // same way it does after an answered one.
            if (state != null && lockFace.showing) {
                standDownForCall("card off")
                // With no card there is nothing on this screen that can answer the call, so the
                // dialer's screen is not a hand-off, it is the only way to take it.
                openCallScreen("card off")
            } else if (state == null && lockStoodDownForCall) {
                lockStoodDownForCall = false
                callScreenOpened = false
                if (locked() && interactive() && !lockFace.dismissed()) showLockFace()
            }
            return
        }
        when (state?.stage) {
            LockCallState.Stage.Ringing -> {
                if (locked() && !lockFace.dismissed()) showLockFace()
                runCatching { lockFace.setCall(state) }
                // The face fades in 500 ms after the screen lights, which is right for a phone
                // being picked up and wrong for one that is ringing.
                if (lockFace.showing) runCatching { lockFace.reveal() }
                log("call ringing · " + if (lockFace.showing) "card up" else "no face")
                // What the phone actually told us about this call, once per ring. Two fixes to
                // this card were aimed at the wrong half because this line did not exist.
                log("call ringing · " + lockCall.evidence())
            }
            LockCallState.Stage.Active -> {
                runCatching { lockFace.setCall(state) }
                // Answered somewhere else -- a headset button, the dialer's own screen, a car. The
                // face was still the top window, so getting out of the way is not enough on its
                // own: whatever is under it is not the call.
                val wasUp = lockFace.showing
                standDownForCall("answered")
                if (wasUp) openCallScreen("answered")
            }
            else -> {
                runCatching { lockFace.setCall(null) }
                callScreenOpened = false
                if (!lockStoodDownForCall) return
                lockStoodDownForCall = false
                if (locked() && interactive() && !lockFace.dismissed()) {
                    showLockFace()
                    if (lockFace.showing) runCatching { lockFace.reveal() }
                    log("call ended · face back")
                }
            }
        }
    }

    /**
     * ANSWER, pressed on the card.
     *
     * The face comes down **on the press**, not when something else notices the call was answered.
     * It used to wait for the audio mode to move and the change to come back round through
     * [LockCall], which is a second on a good day and a poll tick on a bad one — a second of a
     * clock and a dead ANSWER button after a thumb has already landed, which reads exactly like a
     * button that did not work. The mode still arrives and still agrees; this is only earlier.
     *
     * Optimistic in the right direction, too: nothing here is undone if the answer does not take,
     * because [LockCall] publishes no call in that case and the face comes straight back up.
     */
    private fun answerCall() {
        val ok = lockCall.answer()
        log("call answer" + if (ok) "" else " · NO ROUTE")
        if (!ok) return
        standDownForCall("answered")
        openCallScreen("answered")
    }

    private fun declineCall() {
        val ok = lockCall.decline()
        log("call decline" + if (ok) "" else " · NO ROUTE")
    }

    /** Take the face off a live call, once, and remember to bring it back. */
    private fun standDownForCall(why: String) {
        if (!lockFace.showing) return
        lockStoodDownForCall = true
        val gone = runCatching { lockFace.hide() }.getOrDefault(false)
        log("call $why · face " + if (gone) "stood down" else "WOULD NOT GO")
    }

    /**
     * Hand the call back to LightOS, once per call.
     *
     * Once, because the routes underneath it start an activity and a call that re-raised the
     * dialer every second would be a phone you could not leave. The latch clears when the call
     * does, in [onCallChanged].
     */
    private fun openCallScreen(why: String) {
        if (callScreenOpened) return
        callScreenOpened = true
        val route = runCatching { lockCall.openCallScreen() }.getOrNull()
        if (route != null) {
            log("call $why · in-call screen via $route")
            return
        }
        // Nothing in the shade to send, and `showInCallScreen` cannot say whether it worked -- so
        // go and get the dialer. Resuming its task is what puts its call screen in front, because
        // during a call that task's top activity *is* the call screen. On this phone the dialer is
        // LightOS itself, one activity that draws the ring, the call and the lock screen in turn,
        // and resuming it lands on the call.
        //
        // Only reached on a phone that posted no call notification at all. Any dialer that posts
        // one was handled two lines up, so a launch that would land on a keypad instead of a call
        // never happens on the dialers where that distinction exists.
        val dialer = lockCall.dialerPackage()
        val ok = dialer != null && launch(dialer)
        log(
            "call $why · in-call screen " +
                if (ok) "via ${dialer?.substringAfterLast('.')}" else "NO ROUTE",
        )
        if (!ok) log("call $why · " + lockCall.evidence())
    }

    /** Whether the keyguard is up. Not [KeyguardManager.isDeviceLocked] — that answers credentials. */
    private fun locked(): Boolean = runCatching {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }.getOrDefault(false)

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
     * The unlock landed. Go where the home button's Resume would have gone, and say which app.
     *
     * Its own decision rather than a call into [resume], because the caller needs to know *which*
     * package it aimed at: the face is held up until that one is in front, and "something was
     * launched" is not enough to know when to put it down. The rule is the same one — the list
     * first, the fallback second, the snapshot spent on use — so an unlock and a home press still
     * land in the same place.
     */
    private fun resumeFromLock(): String? {
        val was = Lock.pending
        Lock.pending = null
        if (!prefs.enabled || !prefs.lockScreen) return null

        val resumable = was?.takeIf { it in prefs.resumeApps() && it != foreground }
        val fallback = prefs.resumeFallback as? Action.Launch
        val target = resumable ?: fallback?.pkg

        if (resumable != null) slept = null

        // Logged either way. "Unlocking didn't open anything" has four possible causes — no
        // snapshot, the app not on the list, the fallback still pointing at plain home, or a launch
        // that failed — and from the phone they are indistinguishable without this line.
        val ok = if (target != null) launch(target) else goHome()
        log(
            "unlock → " + when {
                resumable != null -> resumable.substringAfterLast('.')
                target != null -> target.substringAfterLast('.') + " · fallback"
                else -> "home · fallback"
            } + if (ok) "" else " · FAILED",
        )
        return if (ok) target else null
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

        /** How long to let a settings change settle before re-stating the rule. */
        const val COLOR_SETTLE_MS = 120L

        /**
         * When to re-state an app's color rule after it comes forward. The first is the window
         * itself; the rest cover a launcher that repaints once its own animation is over.
         */
        val COLOR_REASSERT_MS = longArrayOf(250L, 800L, 2000L)

        /** How recently LightOS must have come forward to be read as its lock screen arriving. */
        const val LOCK_GRACE_MS = 2_000L

        /** Gap allowed between the two taps of a double tap. */
        const val DOUBLE_TAP_MS = 320L

        /**
         * Release-to-release gap for the home double press that ends a LightOS visit. Wider than
         * the wheel's window — a whole press sits inside it, not just a second click.
         */
        const val HOME_DOUBLE_MS = 600L

        /**
         * How long a remembered front app is worth acting on. See [recoverForeground].
         *
         * Two minutes covers an app update, which is the case this exists for, and rules out a
         * phone that has been in a pocket since last night.
         */
        const val FRONT_MEMORY_MS = 2 * 60_000L

        /** Window in which the same binding twice over is one binding. See [act]. */
        const val DEDUPE_MS = 350L

        /** How long [ringing]'s quiet answer is reused. See the note there. */
        const val RING_CHECK_MS = 250L

        /**
         * How long the filter stays quiet after a run of faults before trying again.
         *
         * Long enough that whatever threw has finished doing it, short enough that nobody has time
         * to conclude the buttons are broken. See [dormant].
         */
        const val RECOVER_MS = 60_000L

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
        /**
         * How often to ask the keyguard whether the phone is open. See [lockWatch].
         *
         * Tight, because this interval is now the delay between the thumb landing and the app
         * starting. It only ever runs while the screen is on and the phone is still locked, which
         * is a couple of seconds a day.
         */
        const val LOCK_WATCH_MS = 120L

        /**
         * The longest the face is held up over an unlock's launch.
         *
         * A ceiling rather than a timing: the cover normally comes down the moment the target
         * reports itself in front. This is what stops a launch that never arrives — an app that
         * was uninstalled between sleeping and waking, say — from leaving the face on an open
         * phone, which is the failure this whole feature keeps having to be defended against.
         */
        const val LOCK_COVER_MAX_MS = 2_000L

        /** Failed lock-face starts in a row before the face disarms itself. */
        const val MAX_LOCK_MISSES = 3

        /**
         * How long the system is given to put a recents screen up before the switcher decides
         * nothing is coming.
         *
         * Long enough for an activity to launch cold on this phone, short enough that a dead
         * button answers inside the moment somebody is still looking at it.
         */
        const val SYSTEM_RECENTS_GRACE_MS = 800L

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

        /** How long the installed-keyboard list is trusted before it is read again. */
        const val IME_CACHE_MS = 5 * 60_000L
    }
}
