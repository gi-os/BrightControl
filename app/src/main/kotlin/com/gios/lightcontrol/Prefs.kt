package com.gios.lightcontrol

import android.content.Context
import android.content.SharedPreferences

/** What a bare wheel turn does in an app. */
enum class TurnAction {
    /** The key reaches the app, which scrolls itself if it knows how. */
    PassThrough,

    Brightness,

    /** A synthetic finger-drag, for apps that will never understand the wheel. */
    Swipe,

    /**
     * The key is taken and nothing is done with it.
     *
     * Never selectable and never stored: [Policy] is the only thing that produces it, for
     * LightOS's own screens once its brightness ramp has been switched off. The point of it is
     * the absence — LightOS never sees the notch, so it cannot dim a screen on it.
     */
    Consume,
    ;

    val label: String
        get() = when (this) {
            PassThrough -> "PASS THROUGH"
            Brightness -> "BRIGHTNESS"
            Swipe -> "SWIPE"
            Consume -> "BLOCKED"
        }
}

/** How one app is treated. */
enum class AppRule {
    /** Whatever [Policy] decides from the built-in table. */
    Default,

    /** Hands off. Every key goes to the app untouched — this is what Light's own tools get. */
    Off,

    /** Turns reach the app so it can scroll; press-and-turn, click and camera key are ours. */
    ScrollThrough,

    /** A bare turn adjusts brightness. */
    BrightnessOnTurn,

    /** A bare turn scrolls by synthetic swipe, for apps that ignore the wheel. */
    SwipeOnTurn,
}

/** The resolved behaviour for the app that is currently in front. */
data class Behaviour(
    val bareTurn: TurnAction,
    /** False for hands-off apps: no binding fires, nothing is consumed. */
    val buttonsActive: Boolean,
)

/**
 * Settings: the button bindings, the wheel, and the table that decides apps you haven't
 * touched.
 *
 * The defaults matter more than the settings screen does, because the point of the app is
 * that the phone behaves sensibly without being configured:
 *
 *  - **Light's own tools** are left alone. They already implement the wheel themselves, so
 *    anything this service consumes is behaviour it would be *removing*.
 *  - **Apps built against `hw/`** — the LightX family — get turns passed through, because
 *    per-notch scrolling inside the app beats anything reachable from outside it.
 *  - **Everything else** treats a bare turn as brightness, the way the home screen does.
 *    Nothing else was going to happen with those notches.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("lightcontrol", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- button bindings

    fun action(button: Button, gesture: Gesture): Action =
        Action.parse(sp.getString(bindKey(button, gesture), null))
            ?: Action.default(button, gesture)

    fun setAction(button: Button, gesture: Gesture, action: Action) {
        sp.edit().putString(bindKey(button, gesture), action.store()).apply()
    }

    /** True if this binding is still whatever the app shipped with. */
    fun isDefault(button: Button, gesture: Gesture): Boolean =
        sp.getString(bindKey(button, gesture), null) == null

    private fun bindKey(button: Button, gesture: Gesture) = "bind:${button.name}:${gesture.name}"

    // -------------------------------------------------------------------- the wheel

    /** What a bare turn does in an app with no rule of its own and no entry in the table. */
    var unknownAppTurn: TurnAction
        get() = runCatching {
            TurnAction.valueOf(sp.getString("turn", null) ?: return TurnAction.Brightness)
        }.getOrDefault(TurnAction.Brightness)
        set(v) = sp.edit().putString("turn", v.name).apply()

    /**
     * Whether the button bindings apply on LightOS's own screens — the lock screen and the home
     * dashboard, which are one activity and so one decision.
     *
     * Deliberately narrow. The first attempt took the *turns* there too and made LightOS
     * unstable, and turns were the part worth least: LightOS already puts brightness on them,
     * on both screens. What is missing there is the buttons — the flashlight you actually want
     * from a locked phone, and whatever else you have bound. So this takes the wheel click and
     * the camera button and leaves every turn to LightOS, which is both the smaller change and
     * the one with something to gain.
     */
    var lightOsScreens: Boolean
        get() = sp.getBoolean("lightos_screens", false)
        set(v) = sp.edit().putBoolean("lightos_screens", v).apply()

    /**
     * Whether LightOS still gets the wheel on its own screens, where it means brightness.
     *
     * On, the shipping default, is the behaviour above: a turn on the lock screen or the
     * dashboard passes through and LightOS dims the screen, the way it always has.
     *
     * Off swallows the turn instead — and swallowing is the whole of it. Not "brightness, but
     * ours": the notch is consumed and nothing acts on it. That distinction is the reason this
     * is shippable at all, because the last time this service claimed turns on those screens it
     * made LightOS unstable, and what it was doing with them was writing the same system
     * brightness LightOS was writing, a notch apart. Two owners of one value is the failure.
     * Dropping the key has no second writer in it.
     *
     * What it costs is a lock screen and a dashboard whose wheel does nothing, which is exactly
     * what someone reaching for this switch is asking for: a brightness that stays where it was
     * put. Every other app is untouched by it.
     */
    var lightOsBrightness: Boolean
        get() = sp.getBoolean("lightos_brightness", true)
        set(v) = sp.edit().putBoolean("lightos_brightness", v).apply()

    /**
     * Whether a double tap of the wheel switches what turning it does.
     *
     * This replaced hold-and-turn. Holding the wheel in while turning it read as a deliberate
     * gesture on paper and as a wrestling match in the hand — it needs two motions at once on a
     * control the size of a fingernail, and every accidental version of it changed the screen
     * brightness. Two taps is one motion, repeated, and it tells you what it did.
     */
    var doubleTapSwitchesTurn: Boolean
        get() = sp.getBoolean("double_tap", true)
        set(v) = sp.edit().putBoolean("double_tap", v).apply()

    /**
     * The master switch. Off means this app does nothing to any key, anywhere.
     *
     * Not a convenience. An accessibility service that filters keys is the one kind of app that can
     * make a phone unusable, and the accessibility setting that turns it off lives in a `settings
     * put secure` line that needs a computer — which is exactly what you don't have at 7am with an
     * alarm going. So there is one switch, at the top of the screen, checked before anything else
     * in `onKeyEvent`: after it, the app is indistinguishable from uninstalled.
     */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /**
     * Whether the service may swallow the home button in order to time a hold on it.
     *
     * Its own switch rather than just a binding, because this is the one key where the failure
     * mode is a phone you cannot get home on. Off means the home button is never consumed: the
     * hold binding stops applying, LightOS sees every press exactly as it does with this app
     * uninstalled, and a short press still fires the tap binding on top ("shadow" mode in
     * [ControlService]).
     *
     * It ships on, and it turns itself off — see [disarmHome].
     *
     * It nearly shipped off. The morning LightOS died during an alarm, this was the obvious suspect;
     * the crash log said otherwise — a different app of ours crash-looping a foreground service and
     * flooding the task stack with several hundred permission-dialog tasks, and not one mention of
     * this package. So the feature stays, and the guards the scare bought it stay too: the master
     * switch above, the ring grace window, one activity start a second, and standing down when the
     * same binding fires four times over.
     */
    var homeTakeover: Boolean
        get() = sp.getBoolean("home_takeover", true)
        set(v) = sp.edit().putBoolean("home_takeover", v).apply()

    /**
     * Why the home takeover switched itself off, if it did.
     *
     * A disarm is deliberately sticky and deliberately loud: the button goes back to behaving
     * natively and the settings screen says what happened, rather than retrying something that
     * has already failed on the one key that has to work.
     */
    fun homeFault(): String? = sp.getString("home_fault", null)

    fun disarmHome(reason: String) {
        sp.edit().putBoolean("home_takeover", false).putString("home_fault", reason).apply()
    }

    fun armHome() {
        sp.edit().putBoolean("home_takeover", true).remove("home_fault").apply()
    }

    /**
     * The last dozen things the key service decided, newest first.
     *
     * Kept in one string rather than a set of keys, because it is written from `onKeyEvent` and the
     * cheapest correct thing there is one `apply()`. It exists because a key filter has no other
     * way to explain itself: the phone has no adb attached when the button misbehaves, and "it
     * flickered and went to the menu" is not enough to fix anything from.
     */
    fun keyLog(): List<String> =
        sp.getString("key_log", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun appendLog(line: String) {
        if (!logKeys) return
        val kept = (listOf(line) + keyLog()).take(LOG_LINES)
        sp.edit().putString("key_log", kept.joinToString("\n")).apply()
    }

    fun clearLog() = sp.edit().remove("key_log").apply()

    /** Whether to keep the log at all. On, because the cost is one small write per press. */
    var logKeys: Boolean
        get() = sp.getBoolean("log_keys", true)
        set(v) = sp.edit().putBoolean("log_keys", v).apply()

    /**
     * The last fault the key service hit, and whether it has gone quiet because of them.
     *
     * Recorded rather than only logged, because the symptom of a dormant filter is buttons that
     * silently stopped working — and a phone that won't say why is worse than one that failed.
     */
    fun fault(): String? = sp.getString("fault", null)

    fun faultDormant(): Boolean = sp.getBoolean("fault_dormant", false)

    fun setFault(message: String?, dormant: Boolean) {
        sp.edit().putString("fault", message).putBoolean("fault_dormant", dormant).apply()
    }

    fun clearFault() = sp.edit().remove("fault").putBoolean("fault_dormant", false).apply()

    /**
     * The last crash that killed the app, kept so the phone can show it.
     *
     * A sideloaded app on LightOS has no crash dialog worth reading and no adb attached when it
     * matters — "it crashes when I open it" is the whole report otherwise. [com.gios.lightcontrol.App]
     * writes here from the uncaught-exception handler, before the process goes, and the settings
     * screen shows it on the next launch.
     */
    fun lastCrash(): String? = sp.getString("last_crash", null)

    fun recordCrash(text: String) {
        // commit(), not apply(): the process is about to die and apply() is asynchronous.
        sp.edit().putString("last_crash", text.take(CRASH_CHARS)).commit()
    }

    fun clearCrash() = sp.edit().remove("last_crash").apply()

    /** Notches from dimmest to brightest. */
    var brightnessSteps: Int
        get() = sp.getInt("steps", 24)
        set(v) = sp.edit().putInt("steps", v.coerceIn(8, 64)).apply()

    /** Whether to flash the level on screen. Needs the overlay appop to appear at all. */
    var showReadout: Boolean
        get() = sp.getBoolean("readout", true)
        set(v) = sp.edit().putBoolean("readout", v).apply()

    /**
     * Whether a volume change flashes the level at the top of the screen.
     *
     * On, because on this phone the alternative is nothing at all: LightOS ships no volume UI, so
     * without this a press changes the level and says nothing, and the ringer's level cannot be
     * checked without waiting for something to ring. Needs the overlay appop, like the brightness
     * readout, and simply doesn't appear without it. Nothing about the keys themselves changes with
     * this off — they were never consumed. See `keys.VolumeHud`.
     */
    var showVolume: Boolean
        get() = sp.getBoolean("volume_hud", true)
        set(v) = sp.edit().putBoolean("volume_hud", v).apply()

    /**
     * Whether tapping the volume strip pins a stream for the keys to move.
     *
     * On, because without it the ringer and alarm levels cannot be reached from this phone at all:
     * Android gives the keys one stream at a time and LightOS has no screen for the others. Off puts
     * the HUD back to reporting and nothing else — no volume key is ever consumed with this off,
     * which is the setting to reach for if a press ever feels like it went missing.
     */
    var volumePin: Boolean
        get() = sp.getBoolean("volume_pin", true)
        set(v) = sp.edit().putBoolean("volume_pin", v).apply()

    /** How far one notch drags the screen, in dp, when a turn is scrolling by swipe. */
    var swipeDp: Int
        get() = sp.getInt("swipe_dp", 64)
        set(v) = sp.edit().putInt("swipe_dp", v.coerceIn(24, 200)).apply()

    // ------------------------------------------------------------------ resume apps

    /**
     * The apps [Action.Resume] is allowed to take you back to.
     *
     * A list rather than "the last app you were in", because the home button is the one key on
     * the phone whose wrong behaviour is not an annoyance. Opt-in per app means every press you
     * did not set up goes home, which is the answer you can predict without remembering what you
     * were doing before the screen timed out.
     */
    fun resumeApps(): Set<String> = sp.getStringSet(RESUME_APPS, emptySet()) ?: emptySet()

    /**
     * Where [Action.Resume] goes when there is nothing to resume — which is most presses.
     *
     * Only ever [Action.DefaultHome] or an [Action.Launch]. It exists because binding the home
     * tap to Resume would otherwise *cost* you the binding it replaced: on a phone where LightOS
     * has to keep the HOME role or it crash-loops, "home" resolves to LightOS, and anyone who
     * had the tap pointed at Luma would have silently lost it. So Resume does not replace the
     * old binding, it wraps it — the app comes back if there is one, and otherwise the button
     * does exactly what it did before.
     *
     * Stored in the same encoding as a binding, so a value that no longer parses — an action
     * removed in some future version — reads as home rather than as nothing.
     */
    var resumeFallback: Action
        get() = Action.parse(sp.getString(RESUME_FALLBACK, null))
            ?.takeIf { it is Action.Launch || it == Action.DefaultHome }
            ?: Action.DefaultHome
        set(v) = sp.edit().putString(RESUME_FALLBACK, v.store()).apply()

    fun toggleResumeApp(pkg: String) {
        val next = resumeApps().toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        // A fresh set, not the one handed out by getStringSet — mutating that instance in place
        // is documented as undefined and does not survive a process restart.
        sp.edit().putStringSet(RESUME_APPS, next).apply()
    }

    // ------------------------------------------------------------------ lock face

    /**
     * Whether to paint our own face over the keyguard while the phone is locked.
     *
     * Off by default, and it will stay off by default. Everything else in this app changes what a
     * key does; this one changes what you see when you pick the phone up, and the thing it draws
     * over is the only screen on the device that has to work when nothing else does. Opting in is
     * the right shape for that.
     *
     * Turning it on does not make the phone less secure, and does not change how the phone
     * unlocks: the face is a window painted over the keyguard rather than an activity in front of
     * it, so the keyguard is never occluded and its fingerprint listener stays armed. But it does
     * put a window between the user and a screen they rely on, so it disarms itself at the first
     * sign of trouble. See [disarmLock].
     */
    var lockScreen: Boolean
        get() = sp.getBoolean("lock_screen", false)
        set(v) = sp.edit().putBoolean("lock_screen", v).apply()

    /**
     * Why the lock face switched itself off, if it did.
     *
     * Sticky and loud, exactly like [homeFault]. A face that failed to start once and quietly kept
     * trying is a face that flickers over the lock screen every time the phone is put down, and
     * that is a fault the user would report as "the phone is broken" rather than as this feature.
     */
    fun lockFault(): String? = sp.getString("lock_fault", null)

    fun disarmLock(reason: String) {
        sp.edit().putBoolean("lock_screen", false).putString("lock_fault", reason).apply()
    }

    fun armLock() {
        sp.edit().putBoolean("lock_screen", true).remove("lock_fault").apply()
    }

    /**
     * The picture behind the clock, as a persisted document URI, or null for plain black.
     *
     * A URI rather than a copy of the file. Copying would survive the user deleting the original,
     * which sounds like a feature until it means this app is holding a photograph the user thinks
     * they deleted.
     */
    var lockImage: String?
        get() = sp.getString("lock_image", null)
        set(v) = sp.edit().putString("lock_image", v).apply()

    /** Whether the face lists what is in the shade. Needs the notification listener grant. */
    var lockNotes: Boolean
        get() = sp.getBoolean("lock_notes", true)
        set(v) = sp.edit().putBoolean("lock_notes", v).apply()

    // ---------------------------------------------------------------- per-app rules

    fun ruleFor(pkg: String): AppRule =
        runCatching { AppRule.valueOf(sp.getString(appKey(pkg), null) ?: return AppRule.Default) }
            .getOrDefault(AppRule.Default)

    fun setRule(pkg: String, rule: AppRule) {
        sp.edit().apply {
            if (rule == AppRule.Default) remove(appKey(pkg)) else putString(appKey(pkg), rule.name)
        }.apply()
    }

    /** Every package the user has given an explicit rule, for the settings list. */
    fun overrides(): Map<String, AppRule> = sp.all.keys
        .filter { it.startsWith(APP_PREFIX) }
        .associate { it.removePrefix(APP_PREFIX) to ruleFor(it.removePrefix(APP_PREFIX)) }
        .filterValues { it != AppRule.Default }

    private fun appKey(pkg: String) = APP_PREFIX + pkg

    private companion object {
        const val APP_PREFIX = "app:"

        const val RESUME_APPS = "resume_apps"
        const val RESUME_FALLBACK = "resume_fallback"

        /** Lines of key log kept. A dozen is two or three presses' worth of story. */
        const val LOG_LINES = 12

        /** How much of a stack trace to keep. Enough for the cause and the top frames. */
        const val CRASH_CHARS = 1600
    }
}

/** Turning settings plus a package name into behaviour, defaults and all. */
object Policy {

    /**
     * Light's own software. Left strictly alone: the wheel and the flashlight already work
     * in these, and every key this service swallowed would be a feature it broke. The
     * launcher and SystemUI are here for the same reason — the home screen's wheel is not
     * ours to reinterpret.
     */
    private val handsOffPrefixes = listOf(
        "com.lightos",
        "com.thelightphone.",
        "com.lightphone.",
        "app.lightphonekeyboard",
        "com.android.systemui",
        "com.android.launcher3",
        "com.android.camera2",
    )

    /**
     * Apps that handle wheel turns themselves — the ones carrying the `hw/` module. They
     * scroll per notch, which nothing outside the app can do, so their turns pass through.
     *
     * Note what is deliberately *absent*: the light-sdk tools (`com.thelightphone.*`) scroll
     * with the wheel too, but they stay hands-off, because in an SDK tool an unclaimed key is
     * forwarded to LightOS — which already does the right thing with it. Claiming their
     * buttons would remove behaviour that works.
     */
    private val scrollAwarePrefixes = listOf(
        "com.gios.",
        "com.lightfastread",
        "com.lightrss.reader",
        // Giovanni's phono fork ships under a Light-looking id, so it would otherwise be
        // caught by the hands-off list below and lose its button bindings for no reason.
        "com.lightphone.spotify",
    )

    fun ruleFor(prefs: Prefs, pkg: String): AppRule {
        val explicit = prefs.ruleFor(pkg)
        if (explicit != AppRule.Default) return explicit
        // Scroll-aware is checked first because it holds the more specific ids: one of them
        // sits inside a hands-off prefix, and being ours is the stronger fact.
        if (scrollAwarePrefixes.any { pkg.startsWith(it) }) return AppRule.ScrollThrough
        if (handsOffPrefixes.any { pkg.startsWith(it) }) return AppRule.Off
        return AppRule.Default
    }

    fun behaviourFor(prefs: Prefs, pkg: String?): Behaviour {
        // LightOS's lock screen and dashboard are one activity, so they are one decision.
        if (pkg != null && pkg.startsWith("com.lightos")) {
            // Turns are still never reinterpreted here — doing something with them is what broke
            // it. The only choice is whether LightOS receives them at all: through, and it dims
            // the screen; or dropped on the floor, and its brightness ramp never runs.
            val turn = if (prefs.lightOsBrightness) TurnAction.PassThrough else TurnAction.Consume
            if (prefs.lightOsScreens) return Behaviour(bareTurn = turn, buttonsActive = true)
            // Blocking turns is its own switch, so it applies even with the buttons left alone.
            // Hands-off for everything else, which is what the table would have said anyway.
            if (turn == TurnAction.Consume) {
                return Behaviour(bareTurn = turn, buttonsActive = false)
            }
        }
        val rule = if (pkg == null) AppRule.Default else ruleFor(prefs, pkg)
        if (rule == AppRule.Off) {
            return Behaviour(bareTurn = TurnAction.PassThrough, buttonsActive = false)
        }
        val bare = when (rule) {
            // Brightness wins here, deliberately. An app carrying the hw/ module scrolls better
            // than anything reachable from outside it, so scrolling is its job — but brightness
            // has to mean brightness in every app, or the mode switch becomes something you have
            // to remember the exceptions to. This is also what keeps brightness out of the apps
            // themselves: they never need to know the mode exists, and turning it on doesn't mean
            // shipping sixteen releases.
            AppRule.ScrollThrough -> if (prefs.unknownAppTurn == TurnAction.Brightness) {
                TurnAction.Brightness
            } else {
                TurnAction.PassThrough
            }
            AppRule.BrightnessOnTurn -> TurnAction.Brightness
            // Same deal as ScrollThrough, and it used to not be: SWIPE overrode the mode switch
            // outright, so the double tap flipped the readout to BRIGHTNESS and the very next
            // notch swiped anyway. The per-app rule says how a *scroll* is delivered to this app
            // — a synthetic finger instead of a passed-through key — not that scrolling is the
            // only thing a turn may ever mean here. Brightness has to mean brightness everywhere.
            AppRule.SwipeOnTurn -> if (prefs.unknownAppTurn == TurnAction.Brightness) {
                TurnAction.Brightness
            } else {
                TurnAction.Swipe
            }
            else -> prefs.unknownAppTurn
        }
        return Behaviour(bareTurn = bare, buttonsActive = true)
    }
}
