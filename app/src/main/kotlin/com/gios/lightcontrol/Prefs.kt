package com.gios.lightcontrol

import android.content.Context
import android.content.SharedPreferences

/** What a bare wheel turn does in an app. */
enum class TurnAction { PassThrough, Brightness }

/** How one app is treated. */
enum class AppRule {
    /** Whatever [Policy] decides from the built-in table. */
    Default,

    /** Hands off. Every key goes to the app untouched — this is what Light's own tools get. */
    Off,

    /** Turns reach the app so it can scroll; press-and-turn, click and camera key are ours. */
    ScrollThrough,

    /** A bare turn adjusts brightness, for apps that wouldn't scroll anyway. */
    BrightnessOnTurn,
}

/** The resolved behaviour for the app that is currently in front. */
data class Behaviour(
    val bareTurn: TurnAction,
    val pressTurnBrightness: Boolean,
    val clickTorch: Boolean,
    val cameraKeyOpensCamera: Boolean,
)

/**
 * Settings, and the table that decides what an app gets when you haven't said.
 *
 * The defaults matter more than the settings screen does, because the point of the app is
 * that the wheel behaves sensibly without being configured:
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

    /** What a bare turn does in an app with no rule of its own and no entry in the table. */
    var unknownAppTurn: TurnAction
        get() = if (sp.getBoolean("turn_brightness", true)) {
            TurnAction.Brightness
        } else {
            TurnAction.PassThrough
        }
        set(v) = sp.edit().putBoolean("turn_brightness", v == TurnAction.Brightness).apply()

    var pressTurnBrightness: Boolean
        get() = sp.getBoolean("press_turn", true)
        set(v) = sp.edit().putBoolean("press_turn", v).apply()

    var clickTorch: Boolean
        get() = sp.getBoolean("click_torch", true)
        set(v) = sp.edit().putBoolean("click_torch", v).apply()

    var cameraKeyOpensCamera: Boolean
        get() = sp.getBoolean("camera_key", true)
        set(v) = sp.edit().putBoolean("camera_key", v).apply()

    /** Notches from dimmest to brightest. */
    var brightnessSteps: Int
        get() = sp.getInt("steps", 24)
        set(v) = sp.edit().putInt("steps", v.coerceIn(8, 64)).apply()

    /** Whether to flash the level on screen. Needs the overlay appop to appear at all. */
    var showReadout: Boolean
        get() = sp.getBoolean("readout", true)
        set(v) = sp.edit().putBoolean("readout", v).apply()

    fun ruleFor(pkg: String): AppRule =
        runCatching { AppRule.valueOf(sp.getString(key(pkg), null) ?: return AppRule.Default) }
            .getOrDefault(AppRule.Default)

    fun setRule(pkg: String, rule: AppRule) {
        sp.edit().apply {
            if (rule == AppRule.Default) remove(key(pkg)) else putString(key(pkg), rule.name)
        }.apply()
    }

    /** Every package the user has given an explicit rule, for the settings list. */
    fun overrides(): Map<String, AppRule> = sp.all.keys
        .filter { it.startsWith(PREFIX) }
        .associate { it.removePrefix(PREFIX) to ruleFor(it.removePrefix(PREFIX)) }
        .filterValues { it != AppRule.Default }

    private fun key(pkg: String) = PREFIX + pkg

    private companion object {
        const val PREFIX = "app:"
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
     */
    private val scrollAwarePrefixes = listOf(
        "com.gios.",
        "com.lightfastread",
        "com.lightrss.reader",
    )

    fun ruleFor(prefs: Prefs, pkg: String): AppRule {
        val explicit = prefs.ruleFor(pkg)
        if (explicit != AppRule.Default) return explicit
        if (handsOffPrefixes.any { pkg.startsWith(it) }) return AppRule.Off
        if (scrollAwarePrefixes.any { pkg.startsWith(it) }) return AppRule.ScrollThrough
        return AppRule.Default
    }

    /** True if the built-in table decided, rather than the user. Shown in the app list. */
    fun isImplicit(prefs: Prefs, pkg: String): Boolean = prefs.ruleFor(pkg) == AppRule.Default

    fun behaviourFor(prefs: Prefs, pkg: String?): Behaviour {
        val rule = if (pkg == null) AppRule.Default else ruleFor(prefs, pkg)
        if (rule == AppRule.Off) {
            return Behaviour(
                bareTurn = TurnAction.PassThrough,
                pressTurnBrightness = false,
                clickTorch = false,
                cameraKeyOpensCamera = false,
            )
        }
        val bare = when (rule) {
            AppRule.ScrollThrough -> TurnAction.PassThrough
            AppRule.BrightnessOnTurn -> TurnAction.Brightness
            else -> prefs.unknownAppTurn
        }
        return Behaviour(
            bareTurn = bare,
            pressTurnBrightness = prefs.pressTurnBrightness,
            clickTorch = prefs.clickTorch,
            cameraKeyOpensCamera = prefs.cameraKeyOpensCamera,
        )
    }
}
