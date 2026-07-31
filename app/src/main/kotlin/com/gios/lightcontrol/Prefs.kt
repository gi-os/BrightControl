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
    ;

    val label: String
        get() = when (this) {
            PassThrough -> "PASS THROUGH"
            Brightness -> "BRIGHTNESS"
            Swipe -> "SWIPE"
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
     * Whether the service may swallow the home button in order to time a hold on it.
     *
     * Its own switch rather than just a binding, because this is the one key where the failure
     * mode is a phone you cannot get home on. Off means the home button is never consumed: the
     * hold binding stops applying, LightOS sees every press exactly as it does with this app
     * uninstalled, and a short press still fires the tap binding on top ("shadow" mode in
     * [ControlService]).
     *
     * It ships on, and it turns itself off — see [disarmHome]. A feature that quietly stops
     * working is a fair price for a button that always does.
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

    /** Notches from dimmest to brightest. */
    var brightnessSteps: Int
        get() = sp.getInt("steps", 24)
        set(v) = sp.edit().putInt("steps", v.coerceIn(8, 64)).apply()

    /** Whether to flash the level on screen. Needs the overlay appop to appear at all. */
    var showReadout: Boolean
        get() = sp.getBoolean("readout", true)
        set(v) = sp.edit().putBoolean("readout", v).apply()

    /** How far one notch drags the screen, in dp, when a turn is scrolling by swipe. */
    var swipeDp: Int
        get() = sp.getInt("swipe_dp", 64)
        set(v) = sp.edit().putInt("swipe_dp", v.coerceIn(24, 200)).apply()

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

        /** Lines of key log kept. A dozen is two or three presses' worth of story. */
        const val LOG_LINES = 12
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
        // LightOS's lock screen and dashboard are one activity, so they are one decision — and
        // the turns stay theirs, because taking those is what broke it.
        if (pkg != null && pkg.startsWith("com.lightos") && prefs.lightOsScreens) {
            return Behaviour(bareTurn = TurnAction.PassThrough, buttonsActive = true)
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
            AppRule.SwipeOnTurn -> TurnAction.Swipe
            else -> prefs.unknownAppTurn
        }
        return Behaviour(bareTurn = bare, buttonsActive = true)
    }
}
