package com.gios.lightcontrol

import android.content.Context
import android.content.pm.PackageManager
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

/**
 * What a network says the ringer should be.
 *
 * Two opinions and an absence. There is deliberately no `Vibrate`: the phone this runs on
 * distinguishes vibrate from silent in its own settings, and a third state here would be a third
 * thing to tap past on every network in the list to reach the one you meant.
 */
enum class RingerRule {
    Silent,
    Ring,
    ;

    val label: String get() = if (this == Silent) "SILENT" else "RING"
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

/**
 * What the screen's color does while one app is in front.
 *
 * LightOS pins the whole system to monochrome through the accessibility daltonizer, which is
 * a single system-wide setting. There is no per-app color on this phone, so this app supplies
 * it: the service watches which app comes to the front and drives that one setting to whatever
 * the front app asks for. [Default] means "leave it at the baseline" — whatever the phone was
 * doing before a rule ever fired, which on a LightOS phone is monochrome.
 */
enum class ColorRule {
    /** No opinion. The baseline is restored — mono on a stock LightOS phone. */
    Default,

    /** Force full color while this app is in front. */
    Color,

    /** Force monochrome while this app is in front, even if the baseline is color. */
    Mono,

    /**
     * Do not touch the daltonizer for this app at all.
     *
     * Distinct from [Default], and the difference is the whole reason it exists: Default is an
     * opinion — put the phone back to the baseline — and writing the baseline over an app that
     * drives the filter itself is this app winning an argument it should not have been in. Roll
     * and BrightChat both hold `WRITE_SECURE_SETTINGS` and set their own colour, so anything
     * stated here is a second writer fighting the first, and the visible result is a screen that
     * flickers or lands wherever the last write happened to be.
     *
     * Passthrough states nothing. The setting keeps whatever the app in front put there, and the
     * next app with a real rule takes it back — [ColorMode.applyFor] is written as state rather
     * than as transitions, so nothing is stranded by a rule that declines to write.
     */
    Passthrough,
}

/** The resolved behavior for the app that is currently in front. */
data class Behavior(
    val bareTurn: TurnAction,
    /** False for hands-off apps: no binding fires, nothing is consumed. */
    val buttonsActive: Boolean,
    /**
     * Whether the *camera button* fires anyway, with everything else left alone.
     *
     * One exception to hands-off, for one key, because that key is different: the camera button
     * exists to open something from wherever you are standing, and where people stand is LightOS's
     * home screen. Gating it with the rest meant a rebound camera button could only fire in the
     * places nobody presses it — the setting saved, and then never applied. It has its own switch
     * ([Prefs.cameraOnLightOs]) rather than riding on `buttonsActive`, so turning it on does not
     * also hand LightOS's screens the wheel click, which is the change that once made LightOS
     * unstable.
     */
    val cameraActive: Boolean = false,
)

/**
 * Settings: the button bindings, the wheel, and the table that decides apps you haven't
 * touched.
 *
 * The defaults matter more than the settings screen does, because the point of the app is
 * that the phone behaves sensibly without being configured:
 *
 *  - **Light's own tools** are left alone. They already implement the wheel themselves, so
 *    anything this service consumes is behavior it would be *removing*.
 *  - **Apps built against `hw/`** — the LightX family — get turns passed through, because
 *    per-notch scrolling inside the app beats anything reachable from outside it.
 *  - **Everything else** treats a bare turn as brightness, the way the home screen does.
 *    Nothing else was going to happen with those notches.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("lightcontrol", Context.MODE_PRIVATE)

    init {
        runCatching { migrateDoubleTaps() }
        runCatching { dropVolumeGestures() }
    }

    /**
     * Forget any hold or double tap ever stored on a volume key.
     *
     * [action] already refuses to read them, so this is tidiness rather than the fix — but a stored
     * value that nothing will ever honour is a trap for the next person to add a code path that
     * reads the store directly, and there is one of those in this file for every setting.
     */
    private fun dropVolumeGestures() {
        val dead = listOf(Button.VolumeUp, Button.VolumeDown)
            .flatMap { b -> listOf(Gesture.Hold, Gesture.DoubleTap).map { bindKey(b, it) } }
            .filter { sp.contains(it) }
        if (dead.isEmpty()) return
        sp.edit().apply { dead.forEach { remove(it) } }.apply()
    }

    // ---------------------------------------------------------------- button bindings

    fun action(button: Button, gesture: Gesture): Action {
        // **The volume keys have a tap and nothing else.** Refused here rather than merely hidden
        // in the UI, so a value stored by an older build cannot still be read back and acted on.
        if (!bindable(button, gesture)) return Action.PassThrough
        return Action.parse(sp.getString(bindKey(button, gesture), null))
            ?: Action.default(button, gesture)
    }

    fun setAction(button: Button, gesture: Gesture, action: Action) {
        if (!bindable(button, gesture)) return
        sp.edit().putString(bindKey(button, gesture), action.store()).apply()
    }

    /**
     * Whether this gesture may be bound on this button at all.
     *
     * Only the volume keys say no, and only to the hold and the double tap. Both of those have to
     * be *timed*, and timing a gesture means keeping the press until it is over — on a key whose
     * ordinary job is a repeating, system-owned function this app cannot reproduce, that means the
     * volume stops changing while you wait to find out whether you were holding it.
     *
     * That is not a hypothetical. It is what happened: any binding on either volume key's hold or
     * double tap made *every* press on that key vanish, because the press was consumed to time a
     * gesture that mostly never came. The volume stopped moving and the strip reported the level
     * that had not moved, which is what four releases were spent chasing.
     *
     * A hold on a volume key was never worth much anyway — holding one is how you change the volume
     * quickly — so the honest fix is that the gesture does not exist here rather than that it is
     * bound to nothing by default.
     */
    fun bindable(button: Button, gesture: Gesture): Boolean =
        gesture == Gesture.Tap ||
            (button != Button.VolumeUp && button != Button.VolumeDown)

    /**
     * The same two calls addressed by [BindSlot], so the picker does not have to know which kind of
     * thing it is binding. The button and edge stores stay separate underneath; only the caller is
     * shared.
     */
    fun action(slot: BindSlot): Action = when (slot) {
        is BindSlot.Key -> action(slot.button, slot.gesture)
        is BindSlot.Edge -> edgeAction(slot.side, slot.length)
    }

    fun setAction(slot: BindSlot, action: Action) = when (slot) {
        is BindSlot.Key -> setAction(slot.button, slot.gesture, action)
        is BindSlot.Edge -> setEdgeAction(slot.side, slot.length, action)
    }

    /** True if this binding is still whatever the app shipped with. */
    fun isDefault(button: Button, gesture: Gesture): Boolean =
        sp.getString(bindKey(button, gesture), null) == null

    private fun bindKey(button: Button, gesture: Gesture) = "bind:${button.name}:${gesture.name}"

    /**
     * Whether the tap waits to see if a second press is coming, on a button that has a double
     * tap bound.
     *
     * The one place the three gestures cannot be made identical, because the two ways of reading
     * a double press cost different things and both costs are real:
     *
     *  - **Waiting** is correct. The tap is held back for [ControlService.DOUBLE_TAP_MS] and
     *    fires only once the window closes with no partner, so a double tap never also fires the
     *    tap on its way past. It costs a third of a second on every single press.
     *  - **Not waiting** is fast. The tap fires the instant the button is released, and a second
     *    release inside the window fires the double *on top of* whatever the first one did.
     *
     * Home defaults to not waiting and everything else defaults to waiting, which is exactly what
     * each of them did before any of this was a setting. Home is the key a phone cannot do
     * without and a third of a second on it is felt every time; the glimpse of home on the way to
     * the switcher is the cheaper of the two prices. On the wheel there is no such argument, and
     * a flashlight that comes on every time you meant to switch the turn mode is the report this
     * whole distinction came from.
     *
     * Only consulted when a double tap actually acts. A button with none bound never waits.
     */
    fun tapWaitsForDouble(button: Button): Boolean =
        sp.getBoolean(waitKey(button), button != Button.Home)

    fun setTapWaitsForDouble(button: Button, waits: Boolean) {
        sp.edit().putBoolean(waitKey(button), waits).apply()
    }

    private fun waitKey(button: Button) = "doublewait:${button.name}"

    /**
     * The two hard-wired double taps, carried into the bindings that replaced them.
     *
     * Both were booleans defaulting to on, so the overwhelmingly common case — never touched —
     * needs nothing written: the binding defaults say the same thing. Only a deliberate *off*
     * has to survive, and it survives as [Action.None]. Written once and stamped, because this
     * class is constructed per screen and re-deciding it on every composition is a write per
     * frame in a settings list.
     */
    private fun migrateDoubleTaps() {
        if (sp.getBoolean(DOUBLE_MIGRATED, false)) return
        val edit = sp.edit()
        val legacy = listOf(
            Triple("double_tap", Button.WheelClick, true),
            Triple("home_double_switcher", Button.Home, true),
        )
        legacy.forEach { (key, button, on) ->
            val slot = bindKey(button, Gesture.DoubleTap)
            if (sp.contains(key) && !sp.getBoolean(key, on) && !sp.contains(slot)) {
                edit.putString(slot, Action.None.store())
            }
        }
        edit.putBoolean(DOUBLE_MIGRATED, true).apply()
    }

    // --------------------------------------------------------------------- the hotspot

    /**
     * Presence-triggered tethering, folded in from BrightHotspot.
     *
     * It lives here rather than in an app of its own because the privileged half was always the
     * hard half: raising an access point needs a shell UID, and this app has held one the whole
     * time. The separate app borrowed one from Shizuku, whose way in Android tears down on every
     * reboot — so the feature worked and nobody could keep it working. See
     * [com.gios.lightcontrol.hotspot.SoftAp].
     *
     * Plain SharedPreferences like everything else here. The two strings are a network name and
     * its password, which the shell command needs as arguments because `start-softap` takes the
     * configuration rather than reading the saved one.
     */
    var hotspotAuto: Boolean
        get() = sp.getBoolean(HOTSPOT_AUTO, false)
        set(value) { sp.edit().putBoolean(HOTSPOT_AUTO, value).apply() }

    /** Bluetooth identity addresses whose presence raises the hotspot. */
    var hotspotTriggers: Set<String>
        get() = sp.getStringSet(HOTSPOT_TRIGGERS, emptySet()).orEmpty()
        set(value) { sp.edit().putStringSet(HOTSPOT_TRIGGERS, value).apply() }

    fun toggleHotspotTrigger(address: String): Boolean {
        val next = hotspotTriggers.toMutableSet()
        val added = next.add(address)
        if (!added) next.remove(address)
        hotspotTriggers = next
        return added
    }

    /** Networks where the hotspot stays down, because everything here already has internet. */
    var hotspotTrustedSsids: Set<String>
        get() = sp.getStringSet(HOTSPOT_TRUSTED, emptySet()).orEmpty()
        set(value) { sp.edit().putStringSet(HOTSPOT_TRUSTED, value).apply() }

    fun toggleHotspotTrusted(ssid: String): Boolean {
        val next = hotspotTrustedSsids.toMutableSet()
        val added = next.add(ssid)
        if (!added) next.remove(ssid)
        hotspotTrustedSsids = next
        return added
    }

    /**
     * The network the hotspot comes up as.
     *
     * Has to match what the iPad already knows, or it will see a network it has never met and
     * will not join it unprompted — which is the whole feature. The settings screen offers to
     * read both off the phone so that in the ordinary case nobody types anything.
     */
    var hotspotSsid: String
        get() = sp.getString(HOTSPOT_SSID, "").orEmpty()
        set(value) { sp.edit().putString(HOTSPOT_SSID, value).apply() }

    var hotspotPassword: String
        get() = sp.getString(HOTSPOT_PASSWORD, "").orEmpty()
        set(value) { sp.edit().putString(HOTSPOT_PASSWORD, value).apply() }

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
     * On, the shipping default, is the behavior above: a turn on the lock screen or the
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
     * Whether the wheel selects rows in this app's own screens instead of scrolling them.
     *
     * The switcher's controls, applied to the settings: a turn moves a highlight from row to row,
     * a click opens the highlighted one. Off returns every screen to plain scrolling and a click
     * to whatever it is bound to.
     *
     * Deliberately scoped to this app for now. The version worth having is system-wide, and that
     * one needs `canRetrieveWindowContent="true"` on the service — the capability this app does
     * not take, and the reason it can say it never reads what is on your screen. Worth proving
     * the interaction before spending that.
     */
    var wheelCursor: Boolean
        get() = sp.getBoolean("wheel_cursor", true)
        set(v) = sp.edit().putBoolean("wheel_cursor", v).apply()

    /**
     * Which app the pinned **Home** row opens. Empty means the system's home.
     *
     * A stored package rather than anything worked out from the phone. Two releases were spent
     * deducing it — from the home button's tap binding, then from "the one launcher that is not
     * LightOS" — and both were rules with fallbacks in them that handed somebody the wrong app.
     * LightOS holds the HOME role on every one of these phones whether it is your launcher or not,
     * so there is no signal here worth three rules. See [com.gios.lightcontrol.switcher.HomeApp].
     *
     * Not validated on write. A package can be uninstalled after it is chosen, so the check has to
     * happen where it is read anyway, and a setting that silently forgets your answer because the
     * app was briefly unreadable is worse than one that keeps it.
     */
    var switcherHomePkg: String
        get() = sp.getString("switcher_home_pkg", "") ?: ""
        set(v) = sp.edit().putString("switcher_home_pkg", v).apply()

    /**
     * Whether the switcher pins a **Home** row to the bottom of the list — the show/hide toggle.
     *
     * Every other row in the switcher is somewhere you *were*. Home is where you go to leave
     * wherever you were: always worth offering, never worth ranking by recency. So it sits below
     * the recents under its own heading with a drawn house, and the app it opens is taken out of
     * the rows above it — [switcherHomePkg] says which app that is.
     *
     * A preference and not a rule, because it is a claim about how somebody uses their phone.
     * Anyone who treats their launcher as an app — who switches to it the way they switch to
     * anything else, or who runs two of them and needs to tell them apart — is better served by
     * its real name in the recents and nothing pinned.
     */
    var switcherHomeRow: Boolean
        get() = sp.getBoolean("switcher_home_row", true)
        set(v) = sp.edit().putBoolean("switcher_home_row", v).apply()

    /**
     * The shortest gap between two rows while the switcher is up, in milliseconds.
     *
     * The wheel sends a whole key pair per detent, 35–60 ms apart, and one row per pair against
     * an eight-row list means a flick laps it two or three times — reported as "scrolling on
     * recent view is very fast". 120 ms is roughly two detents per row, which is a list that
     * keeps up with a deliberate turn and ignores a spin.
     *
     * A preference because the reporter asked for one and because the right number is a fact
     * about the hand. Coerced rather than trusted: zero would restore the bug and anything above
     * half a second would read as a wheel that has stopped answering.
     */
    var switcherStepMs: Long
        get() = sp.getLong("switcher_step_ms", 120L)
        set(v) = sp.edit().putLong("switcher_step_ms", v.coerceIn(40L, 500L)).apply()

    /**
     * Whether the camera button's binding applies on LightOS's own screens.
     *
     * The one key that needed its own answer. Everything else on those screens is gated behind
     * [lightOsScreens], which ships off because the last time this service claimed LightOS's keys
     * wholesale it made LightOS unstable — and that gate is correct for the wheel and its click.
     * It was wrong for the camera button, because the camera button is pressed *from the home
     * screen*: rebinding it to another camera app looked like a setting that saved and then did
     * nothing, since the only places the binding could fire were the places nobody presses it.
     *
     * On by default. With the default binding it changes nothing anybody can see — the tap already
     * resolves to the same camera LightOS would have opened — and with a binding, it is the whole
     * point of having set one.
     */
    var cameraOnLightOs: Boolean
        get() = sp.getBoolean("camera_on_lightos", true)
        set(v) = sp.edit().putBoolean("camera_on_lightos", v).apply()

    /**
     * Whether the keyboard-replace prototype is armed: put our own keyboard band over LightOS
     * apps' built-in keyboard and type into the focused field.
     *
     * Off by default — it is a prototype, it needs a second accessibility service enabled, and it
     * is the one feature in this app that deliberately reads which field is focused.
     */
    var keyboardReplace: Boolean
        get() = sp.getBoolean("keyboard_replace", false)
        set(v) = sp.edit().putBoolean("keyboard_replace", v).apply()

    /**
     * Whether keyboard replace simulates presses on the underlying LightOS keyboard (finding its
     * key nodes and clicking them) instead of injecting text into the field.
     *
     * Off by default: text injection is the cleaner path. The simulate mode is the fallback for
     * fields that refuse text actions — password prompts, custom editors.
     */
    var keyboardReplaceSimulate: Boolean
        get() = sp.getBoolean("keyboard_replace_simulate", false)
        set(v) = sp.edit().putBoolean("keyboard_replace_simulate", v).apply()

    /**
     * Whether either camera gesture names an app of its own.
     *
     * The gate on [cameraOnLightOs], and it exists because of what the *default* binding does on
     * those screens. `OpenCamera` fires `INTENT_ACTION_STILL_IMAGE_CAMERA`, and on a phone with
     * two camera apps and no default chosen, Android answers that with its own "which app?"
     * dialog — so taking the key on LightOS's home screen replaced a camera that opened with a
     * chooser that had to be dismissed. LightOS answers its own camera key perfectly well; the
     * only thing it cannot do is open *your* camera. So the key is claimed there only when a
     * gesture points somewhere specific, which is the only case where claiming it adds anything.
     */
    fun cameraNamesApp(): Boolean = Gesture.entries.any { action(Button.Camera, it) is Action.Launch }

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
    /**
     * Whether the service is allowed to stand *itself* down.
     *
     * Off, which is the change. Two guards used to do it and both were sticky until somebody opened
     * this app:
     *
     *  - **Four presses of the same binding inside four seconds.** Written as "somebody is fighting
     *    the phone", read in practice as "somebody is using the flashlight". Its false positive
     *    costs the wheel, every button and the lock face at once.
     *  - **Three throws inside a minute.** A real signal, and still honoured — but now it clears
     *    itself after a quiet minute instead of waiting to be found.
     *
     * On restores the old behaviour for anybody who would rather this app went silent than kept
     * trying. The master switch above is unaffected either way: that one is the user's, and this
     * one is only about the service's opinion of itself.
     */
    var standDownOnMash: Boolean
        get() = sp.getBoolean("stand_down_on_mash", false)
        set(v) = sp.edit().putBoolean("stand_down_on_mash", v).apply()

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

    /**
     * What the color feature did, and what the phone looked like a moment later.
     *
     * Its own ring rather than a share of the key log, because the two are written at completely
     * different rates — a key log is a dozen presses' worth of the last minute, and colour changes
     * a handful of times an hour. Mixed, the interesting one is always already gone.
     *
     * Each line carries the state that was *wanted* and the state read back afterwards, which is
     * the only question worth asking here: same values means the write landed and the system is
     * not acting on it; different values means something else is writing after this app; no line
     * at all means the rule was never applied, because the event never arrived.
     */
    fun colorLog(): List<String> =
        sp.getString("color_log", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun appendColorLog(line: String) {
        val kept = (listOf(line) + colorLog()).take(LOG_LINES)
        sp.edit().putString("color_log", kept.joinToString("\n")).apply()
    }

    fun clearColorLog() = sp.edit().remove("color_log").apply()

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
     * The last crash, as the old recorder stored it. **Read once, on the way out.**
     *
     * There were two crash recorders and they disagreed: this one, written from
     * [com.gios.lightcontrol.App], and [com.gios.lightcontrol.report.CrashLog], read by the report
     * sheet and installed too late to catch anything the service did. Everything reads the file
     * now. These stay only so that a trace already sitting on a phone survives the changeover —
     * `App.onCreate` hands it to `CrashLog.adopt` and clears it, once.
     */
    fun lastCrash(): String? = sp.getString("last_crash", null)

    /** No longer written to. See [lastCrash]. */
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
     * On — and off for the length of v3.89 only, which was a mistake worth recording. The two
     * volume settings were switched off together on one argument: a window drawn over other
     * people's apps is something to ask for rather than something to discover. That argument
     * turns out to belong to only one of them. This one *reports*; it takes nothing, and on a
     * phone whose alternative is no volume UI at all, off by default means a press changes the
     * level and nothing anywhere says so. That is not a safer default, it is a broken one, and it
     * read as the feature having stopped working. Needs the overlay appop either way, and nothing
     * about the keys themselves changes with this off — they were never consumed. See
     * `keys.VolumeHud`.
     *
     * The pref key is untouched, so anyone who switched it off by hand during v3.89 stays off.
     */
    var showVolume: Boolean
        get() = sp.getBoolean("volume_hud", true)
        set(v) = sp.edit().putBoolean("volume_hud", v).apply()

    /**
     * Whether tapping the volume strip opens the stream selector and pins what it chooses.
     *
     * **Off, from v3.89 and staying off.** This is the half of the pair the argument does apply
     * to: it is the one setting in the app that lets a volume key be *consumed*. Everything else
     * here reports. A default that quietly takes a working key is the shape of this codebase's
     * oldest mistake, so it is opt-in — turn it on and the ringer and alarm levels become
     * reachable, which Android and LightOS between them otherwise make impossible on this phone.
     */
    var volumePin: Boolean
        get() = sp.getBoolean("volume_pin", false)
        set(v) = sp.edit().putBoolean("volume_pin", v).apply()

    /**
     * Apps that take the volume keys for themselves, so a press in one is not a volume change.
     *
     * There is no way to ask whether the app in front swallowed a key — the accessibility filter
     * sees a press before anything acts on it, and nothing reports back — and two releases were
     * spent trying to infer it from whether the level moved. It cannot be inferred on this phone:
     * volume keys are handled upstream of the filter, so by the time this app is asked about the
     * key the level has already changed. A list is the honest answer, and it is the same answer
     * this codebase gives everywhere else it needs to know something about an app it cannot ask.
     *
     * BrightLibrary turns pages with them, so it is here out of the box. The default only applies
     * while nobody has edited the list: `getStringSet` is asked with a null default, so an empty
     * list the user made stays empty rather than springing back.
     */
    var volumeKeyApps: Set<String>
        get() = sp.getStringSet(VOLUME_KEY_APPS, null) ?: DEFAULT_VOLUME_KEY_APPS
        set(value) { sp.edit().putStringSet(VOLUME_KEY_APPS, value).apply() }

    fun toggleVolumeKeyApp(pkg: String): Boolean {
        val next = volumeKeyApps.toMutableSet()
        val added = next.add(pkg)
        if (!added) next.remove(pkg)
        volumeKeyApps = next
        return added
    }

    /**
     * Apps the strip is not drawn over at all.
     *
     * A second list, and deliberately not the same one as [volumeKeyApps], because the two answer
     * different questions and an app can be in either without being in the other. That list says
     * *this press was not a volume change* — it suppresses the key path only, and the broadcast
     * path is left alone on purpose, because an app that consumes a key produces no broadcast and
     * one that merely reads it really did move the volume. This list says something blunter:
     * **not here, whatever moved it.** It gates both paths.
     *
     * It exists because "does this app already show its own volume UI" is another question with no
     * API behind it. The built-in table knows about LightOS and the SDK tools, which is where the
     * problem was first found; it cannot know about a sideloaded audiobook player that draws its
     * own slider, or that a particular person does not want a strip over the dialer during a call.
     * Reported four separate times against four different apps — light-reports#74, #117, #135 and
     * #156 — which is the shape of a rule that has to be the user's rather than a table's.
     *
     * Empty out of the box. Everything the table already handles keeps working with this untouched.
     */
    fun volumeHudOffApps(): Set<String> =
        sp.getStringSet(VOLUME_HUD_OFF_APPS, emptySet()) ?: emptySet()

    fun toggleVolumeHudOff(pkg: String) {
        val next = volumeHudOffApps().toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        sp.edit().putStringSet(VOLUME_HUD_OFF_APPS, next).apply()
    }

    // ------------------------------------------------------------- the ringer, by network

    /**
     * Whether joining a Wi-Fi network may set the ringer.
     *
     * Off, and it needs two grants LightOS has no screen for, so it is inert until both are in
     * place. See `audio.WifiRinger` for what it does and `ui.WifiRingerScreen` for what it says
     * about what it cannot do.
     */
    var wifiRingerOn: Boolean
        get() = sp.getBoolean("wifi_ringer", false)
        set(v) = sp.edit().putBoolean("wifi_ringer", v).apply()

    /** Networks where the phone goes silent. */
    var wifiSilentSsids: Set<String>
        get() = sp.getStringSet(WIFI_SILENT, emptySet()).orEmpty()
        set(value) { sp.edit().putStringSet(WIFI_SILENT, value).apply() }

    /** Networks where the phone rings. */
    var wifiRingSsids: Set<String>
        get() = sp.getStringSet(WIFI_RING, emptySet()).orEmpty()
        set(value) { sp.edit().putStringSet(WIFI_RING, value).apply() }

    /**
     * Networks this phone has actually joined, so the settings screen has a list to rule on.
     *
     * There is no way to enumerate saved networks from an unprivileged app, and a scan lists what
     * is in the air rather than what you use. So the list is built by remembering: every network
     * the phone joins while this app is running gets a line, whether or not the feature is on.
     * Capped, oldest-first — a `StringSet` has no order, so the cap is a size and the newest
     * arrival wins a tie by being kept.
     */
    var wifiSeenSsids: Set<String>
        get() = sp.getStringSet(WIFI_SEEN, emptySet()).orEmpty()
        set(value) { sp.edit().putStringSet(WIFI_SEEN, value).apply() }

    fun noteWifiSeen(ssid: String) {
        if (ssid.isBlank()) return
        val current = wifiSeenSsids
        if (ssid in current) return
        val next = current.toMutableSet()
        next.add(ssid)
        // Never drop one that has a rule on it: those are the ones somebody chose.
        val ruled = wifiSilentSsids + wifiRingSsids
        while (next.size > WIFI_SEEN_MAX) {
            val drop = next.firstOrNull { it != ssid && it !in ruled } ?: break
            next.remove(drop)
        }
        wifiSeenSsids = next
    }

    /** The rule on one network, or null for "no opinion — leave the ringer alone". */
    fun wifiRule(ssid: String): RingerRule? = when (ssid) {
        in wifiSilentSsids -> RingerRule.Silent
        in wifiRingSsids -> RingerRule.Ring
        else -> null
    }

    /** Tapping a network walks its rule: nothing, then silent, then ring, then nothing again. */
    fun cycleWifiRule(ssid: String): RingerRule? {
        val next = when (wifiRule(ssid)) {
            null -> RingerRule.Silent
            RingerRule.Silent -> RingerRule.Ring
            RingerRule.Ring -> null
        }
        wifiSilentSsids = if (next == RingerRule.Silent) {
            wifiSilentSsids + ssid
        } else {
            wifiSilentSsids - ssid
        }
        wifiRingSsids = if (next == RingerRule.Ring) wifiRingSsids + ssid else wifiRingSsids - ssid
        return next
    }

    fun clearWifiRules() {
        sp.edit().remove(WIFI_SILENT).remove(WIFI_RING).remove(WIFI_SEEN)
            .remove(WIFI_SILENCED).remove(WIFI_OVERRIDDEN).apply()
    }

    /**
     * Whether leaving a network this app silenced puts the ringer back.
     *
     * On. Silence that follows you off the network that asked for it is a missed call, and it is
     * the failure people never trace back to a setting they turned on weeks ago. Bounded by
     * [wifiRingerSilencedFor]: only a silence this app applied is ever undone, so a phone somebody
     * muted by hand stays muted.
     */
    var wifiRingerRestore: Boolean
        get() = sp.getBoolean("wifi_ringer_restore", true)
        set(v) = sp.edit().putBoolean("wifi_ringer_restore", v).apply()

    /**
     * The network this app silenced for, or blank.
     *
     * The whole of "was it us". Cleared when the ringer comes back for any reason, including
     * somebody turning it up themselves — see `audio.WifiRinger`, which watches for that so it
     * never argues with a person.
     */
    var wifiRingerSilencedFor: String
        get() = sp.getString(WIFI_SILENCED, "").orEmpty()
        set(value) { sp.edit().putString(WIFI_SILENCED, value).apply() }

    /**
     * The network the user overrode the rule on, or blank.
     *
     * Set when the ringer is turned up by hand on a network marked silent, and cleared the moment
     * the phone is somewhere else. Without it the rule reasserts itself on the next capabilities
     * change — and there is always a next one — so turning the ringer up would appear not to work.
     */
    var wifiRingerOverriddenFor: String
        get() = sp.getString(WIFI_OVERRIDDEN, "").orEmpty()
        set(value) { sp.edit().putString(WIFI_OVERRIDDEN, value).apply() }

    /** One line for the settings screen: what this last did, and when. */
    var wifiRingerLast: String
        get() = sp.getString("wifi_ringer_last", "").orEmpty()
        set(value) { sp.edit().putString("wifi_ringer_last", value).apply() }

    /** How far one notch drags the screen, in dp, when a turn is scrolling by swipe. */
    var swipeDp: Int
        get() = sp.getInt("swipe_dp", 64)
        set(v) = sp.edit().putInt("swipe_dp", v.coerceIn(24, 200)).apply()

    // ------------------------------------------------------------------ recents

    /**
     * The switcher's order, kept across a restart of the service.
     *
     * It has to be stored, and the reason is the update: every release of this app rebinds the
     * accessibility service, which is the only thing that knows which apps you have been in. An
     * order held in memory is empty for the first few minutes after every update — the exact
     * minutes somebody installs a release and tries the gesture — and an empty list looked
     * identical to a broken one.
     *
     * A string of package names, most recent first. Written on each app change, which is a
     * handful of times a minute at worst.
     */
    fun recentApps(): List<String> = sp.getString(RECENTS, null)
        ?.split('\n')
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    fun setRecentApps(list: List<String>) {
        sp.edit().putString(RECENTS, list.joinToString("\n")).apply()
    }

    /**
     * The app that was in front when the service last saw a window change, and when.
     *
     * For the one state nothing else can recover: the service has just rebound after an update,
     * so it has seen no window-state event, and something is already on screen. Per-app color is
     * driven from that package name — with no name, the rule cannot be applied, and the app sat
     * in whatever mode the phone happened to be in until it was force-closed and reopened. Which
     * is exactly the bug report: "each update my apps go back and forth as to whether the colors
     * work, and force-closing brings them back."
     *
     * Stamped, because a guess is only worth making while it is fresh.
     */
    fun lastFront(): Pair<String, Long>? {
        val pkg = sp.getString(LAST_FRONT, null)?.takeIf { it.isNotBlank() } ?: return null
        return pkg to sp.getLong(LAST_FRONT_AT, 0L)
    }

    fun setLastFront(pkg: String, at: Long) {
        sp.edit().putString(LAST_FRONT, pkg).putLong(LAST_FRONT_AT, at).apply()
    }

    // ------------------------------------------------------------------ resume apps

    /**
     * The apps [Action.Resume] is allowed to take you back to.
     *
     * A list rather than "the last app you were in", because the home button is the one key on
     * the phone whose wrong behavior is not an annoyance. Opt-in per app means every press you
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
     * The background recipe as JSON — scale mode, crop offsets and the filter stack — or null for
     * plain black. The photo itself is a file; see [com.gios.lightcontrol.lock.LockBackground].
     *
     * A recipe rather than a rendered image. The whole point of the editor is that the same photo
     * dithered at 8× and dithered at 1× are different backgrounds, and keeping the instructions
     * means the result can be re-rendered when the screen, the panel or the pipeline changes.
     */
    var lockBackground: String?
        get() = sp.getString("lock_bg", null)
        set(v) = sp.edit().putString("lock_bg", v).apply()

    /**
     * When the background last changed.
     *
     * The lock face keeps one rendered bitmap and re-renders only when this moves. Rendering is
     * three passes over a million pixels; doing it on every sleep for a picture nobody has touched
     * was measurable, and doing it never would mean an edit that does not show up until reboot.
     */
    var lockBackgroundStamp: Long
        get() = sp.getLong("lock_bg_stamp", 0L)
        set(v) = sp.edit().putLong("lock_bg_stamp", v).apply()

    /**
     * Whether the face explains itself at the bottom.
     *
     * Off, because the explanation is for the first week. "Press the power button" is worth saying
     * once to someone who has just watched two versions of this fail to read their thumb; after
     * that it is two lines of furniture on a screen whose whole argument is that there is nothing
     * on it.
     */
    var lockPrompt: Boolean
        get() = sp.getBoolean("lock_prompt", false)
        set(v) = sp.edit().putBoolean("lock_prompt", v).apply()

    /** Whether the face lists what is in the shade. Needs the notification listener grant. */
    var lockNotes: Boolean
        get() = sp.getBoolean("lock_notes", true)
        set(v) = sp.edit().putBoolean("lock_notes", v).apply()

    /**
     * Whether the face carries what is playing, with controls.
     *
     * On. LightOS draws transport controls on its own lock screen for its own player and for
     * nothing else, so with the Light face up a sideloaded player had no controls anywhere -- and
     * it could not add its own, because an app window sits at layer 11 and this face sits at 31.
     * Read off the platform media session, so it is whatever is playing rather than one named app.
     * Shares the notification listener grant with [lockNotes]; without it the row is simply absent.
     */
    var lockMedia: Boolean
        get() = sp.getBoolean("lock_media", true)
        set(v) = sp.edit().putBoolean("lock_media", v).apply()

    /**
     * Whether a ringing call gets a card on the face, with answer and decline on it.
     *
     * On, and it is a fix rather than a feature. The face is a window at layer 31, so it paints
     * over the dialer's full-screen incoming-call activity -- with this off, a call that arrives
     * while the phone is locked rings behind a clock. The card is what the face shows instead, and
     * once the call is answered the face stands down entirely so LightOS's own in-call screen
     * (mute, speaker, keypad, end) is the thing you are looking at.
     *
     * Off means the face gets out of the way for the whole call instead. Nothing is lost either
     * way; what must never happen is a ringing phone you cannot answer.
     */
    var lockCalls: Boolean
        get() = sp.getBoolean("lock_calls", true)
        set(v) = sp.edit().putBoolean("lock_calls", v).apply()

    /**
     * Whether a call routed to the phone's own speaker is put to maximum volume.
     *
     * On. LightOS has no volume UI at all, so the call stream sits wherever it was last left and
     * there is nothing on screen that would tell you. Applied once per speaker route, never
     * re-asserted, so lowering it afterwards is respected -- see
     * [com.gios.lightcontrol.keys.CallAudio], which also explains why maximum is the ceiling for
     * any app and why a dialer of our own would not raise it.
     */
    var callBoost: Boolean
        get() = sp.getBoolean("call_boost", true)
        set(v) = sp.edit().putBoolean("call_boost", v).apply()

    /**
     * Whether unlocking holds the face open until a deliberate press-and-hold, instead of
     * launching the resume app the instant the fingerprint authenticates.
     *
     * On, because the whole point of the face is the notifications on it, and an unlock that opens
     * an app in the same beat is one nobody gets to read. With this on, the thumb still unlocks the
     * phone -- but the face stays up, armed, and a one-second hold anywhere on it is what actually
     * goes in. A swipe up still reaches the keypad. The app cannot time the power-button sensor, so
     * the hold is read on the touchscreen, not the button.
     */
    var lockHoldToEnter: Boolean
        get() = sp.getBoolean("lock_hold_to_enter", true)
        set(v) = sp.edit().putBoolean("lock_hold_to_enter", v).apply()

    /**
     * Whether the face lists notifications an app has marked as permanent.
     *
     * Off, and that is the same answer the code gave before this became a setting -- the filter
     * has always dropped `FLAG_ONGOING_EVENT` and `FLAG_FOREGROUND_SERVICE`. What changed is that
     * `FLAG_NO_CLEAR` is now read as permanent too, which is the flag that had LightOS's own
     * always-running notice sitting on the face with nothing that would remove it: a swipe asks
     * the platform to cancel it, the platform refuses an un-clearable notification by simply not
     * removing it, and the row came straight back looking like the gesture had failed.
     *
     * On is for the case where the permanent notification is the point -- a recording in progress,
     * a download, a navigation. Those are the notifications the shade keeps on purpose, and
     * somebody who wants them on the lock face should be able to have them.
     */
    var lockPersistent: Boolean
        get() = sp.getBoolean("lock_persistent", false)
        set(v) = sp.edit().putBoolean("lock_persistent", v).apply()

    /**
     * Packages whose notifications this app never shows -- on the lock face **or** as a banner.
     *
     * Per app rather than per notification, because the thing being silenced is a *source*: an app
     * that posts an unwanted notice posts it again. Nothing is cancelled and nothing is hidden
     * anywhere else -- the shade, Glance and the app itself are untouched, and this only decides
     * what this app draws.
     *
     * One list for both surfaces, deliberately. Two lists would mean an app you had already said
     * you did not want to see could still interrupt you, and the second list is one nobody would
     * think to go and look at. The key is still `lock_hidden_apps` because it is the same setting
     * it always was and renaming it would silently forget what everyone had chosen; the settings
     * screen it lives on moved to Notifications, which is where a shared rule belongs.
     */
    fun lockHiddenApps(): Set<String> = sp.getStringSet(LOCK_HIDDEN, emptySet()) ?: emptySet()

    fun toggleLockHidden(pkg: String) {
        val next = lockHiddenApps().toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        sp.edit().putStringSet(LOCK_HIDDEN, next).apply()
    }

    // ---------------------------------------------------------------------- banners

    /**
     * Whether a notification from any app puts a box over whatever the phone is showing.
     *
     * **Off until it is switched on**, and for the same reason the lock face and the edge strips
     * are: everything else in this app changes what a *key* does, and a key not taken is a key the
     * app still gets. A window that appears over what you were reading is a larger promise than a
     * remapped button, and it should be one the user made rather than one an update made for them.
     *
     * Reads the shade through the listener the lock face already needs ([lockNotes]'s grant), so
     * there is nothing new to grant and nothing new running. Without that grant this draws nothing,
     * which is why [com.gios.lightcontrol.notify.AlertHandoff.owned] tests both.
     */
    var banner: Boolean
        get() = sp.getBoolean("banner", false)
        set(v) = sp.edit().putBoolean("banner", v).apply()

    /**
     * Whether a notification on a sleeping phone turns the panel on.
     *
     * On, because a box nobody is shown is a box that did nothing -- and the whole reason to have
     * one rather than a shade row is being told at the moment it happens.
     *
     * **No longer only about banners.** It used to be read only when [banner] was on, which meant
     * a phone with the Light lock face and no banners never lit up for anything: a message arrived,
     * the shade took it, and you found out whenever you next pressed the power button. That is not
     * what a lock face is for. With banners off this now wakes the panel and the face comes up
     * carrying the notification as a row -- which is the same landing the banner already arranged
     * for, minus the box over the top of it.
     *
     * It does nothing at all unless there is something to land on: with [banner] off *and*
     * [lockScreen] off, the phone would wake to LightOS's own lock screen, which is exactly what
     * it would have shown when picked up anyway.
     *
     * The wake is a wake lock and never an activity; see
     * [com.gios.lightcontrol.notify.BannerWake] for the fingerprint sensor that decides it.
     */
    var bannerWake: Boolean
        get() = sp.getBoolean("banner_wake", true)
        set(v) = sp.edit().putBoolean("banner_wake", v).apply()

    /**
     * How long a banner stays, in milliseconds.
     *
     * 4.5s is BrightChat's number and it is the right one: long enough to read two lines, short
     * enough not to sit in front of what you were doing. Settable because "long enough to read"
     * is not the same length for everybody, and this is the one number on the feature where that
     * is true.
     */
    var bannerDwellMs: Long
        get() = sp.getLong("banner_dwell", 4_500L)
        set(v) = sp.edit().putLong("banner_dwell", v).apply()

    /**
     * When the other apps were last told who owns the on-screen box.
     *
     * Stored only so the settings row can say whether the handoff has actually gone out. It is not
     * read to decide anything: the broadcast is sent again on every launch, on the grant landing
     * and on boot, because a handoff that depends on one message having arrived is one that stays
     * wrong after the message a phone happened to miss.
     */
    var handoffToldAt: Long
        get() = sp.getLong("handoff_told_at", 0L)
        set(v) = sp.edit().putLong("handoff_told_at", v).apply()

    // ---------------------------------------------------------------- edge gestures

    /**
     * The strip down the **left** edge, which goes back.
     *
     * **On.** It shipped off, on the reasoning that this is the one feature here which takes a
     * *touch* rather than a key -- a key this service declines is a key the app still gets, and a
     * touch that lands on the strip cannot be handed back. So it was a decision to be made rather
     * than a default to be discovered.
     *
     * That reasoning was right about the cost and wrong about the conclusion. **A phone with no
     * back button is broken in a way that a phone with a 14 dp strip down one edge is not.** An app
     * that pushes a screen and draws no arrow of its own is a dead end, and somebody who has just
     * sideloaded their first app has no reason to know that the way out of it is three screens deep
     * in a settings app they have not opened. A default nobody discovers is a feature nobody has.
     *
     * What keeps it honest is that the cost is small, visible and reversible: 14 dp, adjustable;
     * excluded per app; off in one tap on its own screen; and gone entirely with the EVERYTHING OFF
     * switch at the top of the app. The **right** edge stays off, because the recents list is
     * already reachable by a double press of home -- it adds convenience rather than repairing an
     * absence.
     *
     * Turning it off writes `false`, so this default only decides for a phone that has never had an
     * opinion. Nobody's choice is overridden by the change.
     *
     * See [com.gios.lightcontrol.keys.EdgeSwipe] for what it costs precisely.
     */
    var leftEdgeOn: Boolean
        get() = sp.getBoolean("back_swipe", true)
        set(v) = sp.edit().putBoolean("back_swipe", v).apply()

    /**
     * The strip down the **right** edge, which opens the app switcher.
     *
     * The same gesture pointing the other way, and **off** -- which is now a difference from the
     * left edge rather than a matching default. The left edge repairs an absence: there is no back
     * button on this phone at all. This one adds convenience to something that already works, since
     * a double press of home opens the same window. Convenience is worth opting into; an absence is
     * worth filling.
     */
    var rightEdgeOn: Boolean
        get() = sp.getBoolean("switcher_swipe", false)
        set(v) = sp.edit().putBoolean("switcher_swipe", v).apply()

    fun edgeOn(side: EdgeSide): Boolean =
        if (side == EdgeSide.Left) leftEdgeOn else rightEdgeOn

    /**
     * What one edge's short or long swipe does.
     *
     * Stored exactly like a button binding and read from the same [Action] vocabulary, so anything
     * a button can be bound to an edge can be bound to as well -- including opening an app.
     */
    fun edgeAction(side: EdgeSide, length: EdgeLength): Action =
        Action.parse(sp.getString(edgeKey(side, length), null))
            ?: Action.defaultEdge(side, length)

    fun setEdgeAction(side: EdgeSide, length: EdgeLength, action: Action) {
        sp.edit().putString(edgeKey(side, length), action.store()).apply()
    }

    /** True if this edge binding is still whatever the app shipped with. */
    fun isEdgeDefault(side: EdgeSide, length: EdgeLength): Boolean =
        sp.getString(edgeKey(side, length), null) == null

    private fun edgeKey(side: EdgeSide, length: EdgeLength) = "edge:${side.name}:${length.name}"

    /**
     * How far across a long swipe has to reach, in dp, or 0 for an edge with no long swipe.
     *
     * 150 dp -- roughly a third of this panel, which is far enough that no ordinary short swipe
     * reaches it by accident and near enough that a thumb can still get there in one motion. One
     * number for both edges, like the others here.
     *
     * The ceiling is applied where the screen width is known, in
     * [com.gios.lightcontrol.keys.EdgeSwipe]: a threshold past the edge of the panel is a gesture
     * nobody can complete, and this screen offers distances without knowing how wide the panel is.
     * The floor is applied in the gesture, which will not let the long threshold sit at or below the
     * short one -- that would make the short binding unreachable.
     */
    var edgeLongDp: Int
        get() = sp.getInt("edge_long", 150).coerceIn(0, 400)
        set(v) = sp.edit().putInt("edge_long", v.coerceIn(0, 400)).apply()

    /**
     * How wide a strip is, in dp. The entire cost of these gestures is this number.
     *
     * 14 dp by default -- about 3 mm on this panel, narrow enough that nothing is aimed at it and
     * wide enough that a thumb starting at the edge lands on it. Adjustable because the apps where
     * an edge matters vary, and because a thumb varies.
     *
     * One number for both edges. Two would be two numbers to reason about for one decision, and
     * nobody wants their left edge to be a different size from their right.
     */
    var edgeWidthDp: Int
        get() = sp.getInt("back_swipe_width", 14).coerceIn(6, 40)
        set(v) = sp.edit().putInt("back_swipe_width", v.coerceIn(6, 40)).apply()

    /**
     * How much of the top of the screen the strips leave alone, in dp.
     *
     * **The one place an edge strip and an app want the same pixels.** Almost every screen on this
     * phone puts a back arrow in the top-left corner, which is the corner the left strip runs
     * through — so reaching for the arrow puts a thumb on the strip, and a thumb that slides even
     * slightly inwards on its way down is a swipe rather than a tap. Both go back, so it looked
     * harmless; it is not. The same slip on a screen with no arrow, or on the *right* edge where
     * the same corner is a menu, performs a gesture nobody asked for.
     *
     * 92 dp by default, which is a status bar and a title bar on this panel. Adjustable because an
     * app that draws no bar at all wants none of it, and 0 is a real answer -- it is the behaviour
     * before this setting existed.
     *
     * Only the *top*. A dead zone at the bottom would be the same argument and is deliberately not
     * offered: nothing on this phone puts a control in the bottom corners, and every dp spent here
     * is a dp of edge the gesture no longer has.
     */
    var edgeTopDeadDp: Int
        get() = sp.getInt("edge_top_dead", 92).coerceIn(0, 200)
        set(v) = sp.edit().putInt("edge_top_dead", v.coerceIn(0, 200)).apply()

    /**
     * How far across the drag has to go before lifting acts, in dp.
     *
     * 48 dp. Short enough to be one motion of a thumb, long enough that a tap on the edge is not
     * a navigation. Crossing it arms the gesture rather than firing it -- see [BackGesture].
     */
    var edgeTriggerDp: Int
        get() = sp.getInt("back_swipe_trigger", 48).coerceIn(24, 120)
        set(v) = sp.edit().putInt("back_swipe_trigger", v.coerceIn(24, 120)).apply()

    /**
     * Whether the small box appears at the thumb while the gesture is being made.
     *
     * On. A gesture with no feedback is indistinguishable from a gesture that is not working, and
     * these have an armed state that the user needs to be able to see before they commit to it.
     */
    var edgeIndicator: Boolean
        get() = sp.getBoolean("back_swipe_hud", true)
        set(v) = sp.edit().putBoolean("back_swipe_hud", v).apply()

    /**
     * Whether crossing a threshold, and firing, are felt as well as seen.
     *
     * On. LightOS's own back gesture buzzes when it arms, and a gesture on the same screen that
     * does not is read as the gesture not having worked — reported twice, as "no haptic feedback
     * when using gesture navigation" and as an inconsistency with the rest of the phone
     * (light-reports#124, #133). A setting rather than a constant because a phone kept in a
     * pocket-quiet state is a real preference, and because the indicator has one.
     *
     * The buzz goes through `View.performHapticFeedback`, so the phone's own haptics switch is
     * still the outer one: off there means nothing here, whatever this says.
     */
    var edgeHaptics: Boolean
        get() = sp.getBoolean("back_swipe_haptics", true)
        set(v) = sp.edit().putBoolean("back_swipe_haptics", v).apply()

    /**
     * Packages both strips stay down for.
     *
     * An exclusion list rather than an inclusion one, because the gestures are meant to be a
     * property of the phone -- something you can rely on being there. The apps that need excluding
     * are the ones whose edges are controls, and those are known to the user and not to this app.
     * Light's own tools are excluded by the built-in table instead; see [Policy.edgeSwipeAllowed].
     *
     * One list for both edges. An app that draws its own controls at the screen edge usually does
     * it at both, and a per-edge list would be twice the rows to say the same thing.
     */
    fun edgeSwipeOffApps(): Set<String> = sp.getStringSet(BACK_OFF_APPS, emptySet()) ?: emptySet()

    fun toggleEdgeSwipeOff(pkg: String) {
        val next = edgeSwipeOffApps().toMutableSet()
        if (!next.add(pkg)) next.remove(pkg)
        sp.edit().putStringSet(BACK_OFF_APPS, next).apply()
    }

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

    // ---------------------------------------------------------------- per-app color

    /**
     * Whether the service drives the daltonizer at all. Off means color rules are ignored and
     * the phone keeps whatever the daltonizer was set to — the safe default, because forcing the
     * setting needs a grant most phones will not have until [adb/AdbManager] or a computer sets
     * it.
     */
    var colorAutoSwitch: Boolean
        get() = sp.getBoolean("color_auto", false)
        set(v) = sp.edit().putBoolean("color_auto", v).apply()

    /**
     * The rule for [pkg]: what the user set, or the built-in preset when they have set nothing.
     *
     * The same shape as [Policy.ruleFor] for the wheel, and for the same reason — an explicit
     * choice wins, and AUTO resolves through a table rather than flatly meaning "mono". Without
     * the table every app on the phone shipped monochrome until somebody found this screen, and
     * for the apps in [Policy.colorPresets] that is not a default, it is the app not working:
     * Roll's entire output is color and it was framing and shooting through a grey filter.
     */
    fun colorRuleFor(pkg: String): ColorRule =
        storedColorRule(pkg) ?: Policy.builtInColorRuleFor(pkg)

    /**
     * What the user set for [pkg], and nothing else. Null means they have set nothing.
     *
     * Split out from [colorRuleFor] because "no choice made" and "the choice is Default" stopped
     * being the same answer once an app could ask for itself. [colorRuleFor] flattens the two, so
     * anything resolving through the request or the manifest has to ask this instead — otherwise
     * every app on the phone resolves to an explicit AUTO and nothing below the first step is ever
     * consulted.
     */
    fun storedColorRule(pkg: String): ColorRule? = runCatching {
        sp.getString(colorKey(pkg), null)?.let { ColorRule.valueOf(it) }
    }.getOrNull()

    fun setColorRule(pkg: String, rule: ColorRule) {
        sp.edit().apply {
            if (rule == ColorRule.Default) remove(colorKey(pkg)) else putString(colorKey(pkg), rule.name)
        }.apply()
    }

    /** Every package with an explicit color rule, for the settings list. */
    fun colorOverrides(): Map<String, ColorRule> = sp.all.keys
        .filter { it.startsWith(COLOR_PREFIX) }
        .associate { it.removePrefix(COLOR_PREFIX) to colorRuleFor(it.removePrefix(COLOR_PREFIX)) }
        .filterValues { it != ColorRule.Default }

    /**
     * The daltonizer state to return to when no rule applies, captured once so a phone that was
     * color to begin with is not left mono by this app. -1 for the enabled flag means "not yet
     * captured". Written by the service the first time it reads the live setting.
     */
    var colorBaselineEnabled: Int
        get() = sp.getInt("color_base_enabled", -1)
        set(v) = sp.edit().putInt("color_base_enabled", v).apply()

    var colorBaselineMode: Int
        get() = sp.getInt("color_base_mode", 0)
        set(v) = sp.edit().putInt("color_base_mode", v).apply()

    private fun colorKey(pkg: String) = COLOR_PREFIX + pkg

    // ---------------------------------------------------------------- first run

    /** Whether the intro guide has been dismissed. False means the app opens on it. */
    var introSeen: Boolean
        get() = sp.getBoolean("intro_seen", false)
        set(v) = sp.edit().putBoolean("intro_seen", v).apply()

    // ---------------------------------------------------------------- self-adb

    /** Last host used for the on-device adb connection. Loopback is right almost always. */
    var adbHost: String
        get() = sp.getString("adb_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = sp.edit().putString("adb_host", v).apply()

    var adbPort: String
        get() = sp.getString("adb_port", "") ?: ""
        set(v) = sp.edit().putString("adb_port", v).apply()

    /**
     * A grant request that arrived before there was a connection to run it with.
     *
     * ### Why this is stored at all
     *
     * The request screen tells the user "this request will still be here" and then sends them to
     * ADB setup — where the request was dropped on the floor, leaving them looking at GRANT ALL,
     * which is this app's own setup and not the thing they were asked to approve. The one action
     * on the screen was the one nobody wanted.
     *
     * Held here rather than in the navigation state because setup is a trip through *Settings*, and
     * this process does not reliably survive it: the pairing helper is an accessibility service,
     * and coming back from Accessibility with it switched on is the point at which everything
     * in memory has already gone.
     *
     * Lines are newline-joined, which is safe because [GrantRequest] refuses a line with a newline
     * in it long before it gets here — a request is a list of single-line commands or it is a
     * refusal.
     */
    var pendingGrantPkg: String
        get() = sp.getString(PENDING_PKG, "") ?: ""
        set(v) = sp.edit().putString(PENDING_PKG, v).apply()

    var pendingGrantLines: List<String>
        get() = (sp.getString(PENDING_LINES, "") ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
        set(v) = sp.edit().putString(PENDING_LINES, v.joinToString("\n")).apply()

    /** When it was set aside, so the screen can say how old it is rather than guess. */
    var pendingGrantAt: Long
        get() = sp.getLong(PENDING_AT, 0L)
        set(v) = sp.edit().putLong(PENDING_AT, v).apply()

    /** Set the three together; nothing useful can be done with one of them. */
    fun holdGrantRequest(pkg: String, lines: List<String>) {
        sp.edit()
            .putString(PENDING_PKG, pkg)
            .putString(PENDING_LINES, lines.joinToString("\n"))
            .putLong(PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearGrantRequest() {
        sp.edit().remove(PENDING_PKG).remove(PENDING_LINES).remove(PENDING_AT).apply()
    }

    /**
     * Whether a failure the app noticed itself is sent without asking.
     *
     * On by default, and that is the considered choice rather than the lazy one. The alternative —
     * a sheet offering to report it — appears while somebody is in the middle of the thing that
     * just failed, and one tap outside it discards the only description of what went wrong that
     * anybody will ever have. Several evenings of this were diagnosed by reading logs over a
     * shoulder because the offer had been dismissed.
     *
     * A user-reported glitch still asks, because it needs the user: which symptom, in their words.
     * A failure the app detected has already written its own account, and a dialog on top of that
     * adds only a way to lose it.
     */
    var autoSendFailures: Boolean
        get() = sp.getBoolean(AUTO_SEND, true)
        set(v) = sp.edit().putBoolean(AUTO_SEND, v).apply()

    /**
     * What the last automatic pairing attempt did, one line per step, oldest first.
     *
     * ### Why this is on disk
     *
     * The attempt spans a trip through Settings: the reader is armed here, the code is read over
     * there, and the pairing, connect and grants all happen while this app is in the background. Its
     * progress lived in memory on an object, which survives a recomposition and not much else — and
     * the one screen that shows it is the screen nobody is looking at while it runs.
     *
     * So hours went by tonight with plenty of reports about commands failing and **not one** about
     * the pairing itself, which is the step everything else depends on. A trail on disk is read back
     * on the next launch, whatever happened in between.
     */
    fun pairTrail(): List<String> =
        (sp.getString(PAIR_TRAIL, "") ?: "").split('\n').filter { it.isNotBlank() }

    fun notePairStep(line: String) {
        val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val kept = (pairTrail() + "$at  $line").takeLast(PAIR_TRAIL_MAX)
        sp.edit().putString(PAIR_TRAIL, kept.joinToString("\n")).apply()
    }

    fun clearPairTrail() {
        sp.edit().remove(PAIR_TRAIL).apply()
    }

    private fun appKey(pkg: String) = APP_PREFIX + pkg

    private companion object {
        const val PAIR_TRAIL = "pairTrail"
        const val PAIR_TRAIL_MAX = 24
        const val AUTO_SEND = "autoSendFailures"
        const val PENDING_PKG = "pendingGrantPkg"
        const val PENDING_LINES = "pendingGrantLines"
        const val PENDING_AT = "pendingGrantAt"
        const val HOTSPOT_AUTO = "hotspotAuto"
        const val HOTSPOT_TRIGGERS = "hotspotTriggers"
        const val HOTSPOT_TRUSTED = "hotspotTrustedSsids"
        /** Stamped once the two legacy double-tap booleans have been read. */
        const val DOUBLE_MIGRATED = "double_taps_migrated"

        const val HOTSPOT_SSID = "hotspotSsid"
        const val VOLUME_KEY_APPS = "volumeKeyApps"
        const val VOLUME_HUD_OFF_APPS = "volume_hud_off_apps"

        /** BrightLibrary turns pages with the volume keys. See [volumeKeyApps]. */
        val DEFAULT_VOLUME_KEY_APPS = setOf("com.lightfastread")

        const val WIFI_SILENT = "wifiSilentSsids"
        const val WIFI_RING = "wifiRingSsids"
        const val WIFI_SEEN = "wifiSeenSsids"
        const val WIFI_SILENCED = "wifiRingerSilencedFor"
        const val WIFI_OVERRIDDEN = "wifiRingerOverriddenFor"

        /** Enough for every network a person actually uses, and not a log. */
        const val WIFI_SEEN_MAX = 40
        const val HOTSPOT_PASSWORD = "hotspotPassword"

        const val APP_PREFIX = "app:"
        const val COLOR_PREFIX = "color:"

        const val RECENTS = "recent_apps"
        const val LAST_FRONT = "last_front"
        const val LAST_FRONT_AT = "last_front_at"

        const val RESUME_APPS = "resume_apps"
        const val RESUME_FALLBACK = "resume_fallback"

        const val BACK_OFF_APPS = "back_swipe_off"
        const val LOCK_HIDDEN = "lock_hidden_apps"

        /** Lines of key log kept. A dozen is two or three presses' worth of story. */
        const val LOG_LINES = 12

        /** How much of a stack trace to keep. Enough for the cause and the top frames. */
        const val CRASH_CHARS = 1600
    }
}

/** Turning settings plus a package name into behavior, defaults and all. */
object Policy {

    /**
     * Windows that appear over an app without replacing it. The notification shade, this
     * app's own readout overlay, and Edge Gestures all raise window-state events, and
     * treating any of them as "the app in front" would swap the key mapping — and the
     * color — mid-turn.
     */
    private val transientPackages = setOf(
        "com.gios.lightcontrol",
        "com.android.systemui",
        // MediaProvider's own dialogs. The album's *delete* confirmation is one of them, and it
        // raises a window-state event with this package on it — so deleting a photograph inside a
        // colour tool read as "a new app is in front", fired Default, and put the album back to
        // mono until it was closed and reopened. It has no launcher entry either, so the general
        // rule below catches it too; named as well, because a name costs nothing and the log line
        // is easier to read for it.
        "com.android.providers.media.module",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Edge Gestures floats invisible swipe zones over every app. Its window-state
        // events were being read as a real app arriving, and with no color rule that
        // fired Default — dropping the panel back to monochrome under the app the user
        // was actually looking at.
        "com.ss.edgegestures",
    )

    /**
     * Whether a window-state event is something floating over the front app rather than a new
     * app arriving.
     *
     * A keyboard is the case this was widened for. A soft keyboard raises a window-state event
     * carrying its own package, so the front app looked like it had changed to the keyboard —
     * which has no color rule, so [ColorRule.Default] fired and the panel dropped back to
     * monochrome the moment anyone started typing. The keyboard is a window over the app, not
     * the app, and this says so.
     *
     * [isInputMethodPackage] comes from the installed IME list rather than a list of package
     * names here, so it holds for a keyboard nobody has written yet. [classIsActivity] is the
     * escape hatch: an IME package can also ship an ordinary settings activity — BrightThumb
     * does — and *that* really is a new app in front, so it is left alone.
     */
    fun isTransientWindow(
        pkg: String,
        isInputMethodPackage: Boolean,
        classIsActivity: Boolean,
        /**
         * Whether the package can be opened from a launcher — a launcher entry, or a home one.
         *
         * The general form of the two bugs the named list above was patched for. A package with no
         * front door is not an app you switched to: it is a dialog, a permission prompt, a system
         * provider putting a confirmation over whatever you were already in. Treating one as the
         * front app applies *its* rules — and its rule is Default, which restores a baseline that
         * on this phone is monochrome.
         *
         * Home counts, and has to: LightOS declares `CATEGORY_HOME` and no `CATEGORY_LAUNCHER`, so
         * a test that only asked about launcher entries would call the phone's own shell transient
         * and stop tracking the one package every key rule here depends on.
         */
        hasFrontDoor: Boolean = true,
    ): Boolean = pkg in transientPackages ||
        (isInputMethodPackage && !classIsActivity) ||
        !hasFrontDoor

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
     * Who the **edge strips** are refused in front of. Its own list, and the reason it is not
     * [handsOffPrefixes] is the whole of this fix: that list answers "whose keys are not ours to
     * reinterpret", and a package prefix is a poor guess at ownership in the first place.
     *
     * What is here, and why each one already has a way back:
     *
     *  - `com.lightos` -- LightOS itself, which has a gesture-navigation switch in its own
     *    settings. A strip over the top would be two gestures on one edge and ours is the weaker.
     *  - `com.thelightphone.` -- the light-sdk namespace. An SDK tool subclasses `LightScreen` and
     *    navigates with `navigateTo`, so **the SDK draws its own back button and the Android back
     *    stack is not what it moves through**. `GLOBAL_ACTION_BACK` there would cost an edge and
     *    do nothing.
     *  - The keyboard, SystemUI, the AOSP launcher and camera, unchanged.
     *
     * **`com.lightphone.` is deliberately absent, and that was the bug.** No software of Light's
     * ships under it -- Light's tools are all inside `com.lightos` and its keyboard is
     * `app.lightphonekeyboard`. Every package known under it is an ordinary sideloaded APK, which
     * is the exact population this gesture exists for: BrightMusic (`com.lightphone.spotify`, the
     * phono fork, already excepted by hand in [scrollAwarePrefixes] for the same reason), and
     * fenleon's Audiobooks, Chats and Passes. All four were silently refused a back gesture on the
     * strength of a Light-looking id.
     */
    private val edgeRefusedPrefixes = listOf(
        "com.lightos",
        "com.thelightphone.",
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
     * buttons would remove behavior that works.
     */
    /**
     * Apps that own the **whole** wheel — its turns and its press.
     *
     * [scrollAwarePrefixes] is not enough for these. That rule passes turns through but keeps the
     * click for this service, and the click's default binding is the torch, so an app that uses
     * the press as a control of its own never sees it: the torch comes on instead and the key is
     * consumed. Nothing the app can do about that from its side — this service decides first.
     *
     * So these resolve to [AppRule.Off] outright, which is the same hands-off treatment Light's
     * own tools get: every key goes to the app untouched, and the turn mode does not apply either.
     * The cost is that the torch and the camera key do nothing while such an app is in front,
     * which is the correct trade when the app is a tape recorder and the wheel is its transport.
     *
     * Checked before everything below, because `com.gios.` in the scroll-aware list would
     * otherwise claim these first and hand back the weaker rule.
     */
    private val ownsWheelPrefixes = listOf(
        // BrightRecorder: turning the wheel scrubs the tape, pressing it plays and stops.
        "com.gios.brightrecorder",
        // Roll: turning the wheel is the filter dial, and the click is the only gesture that
        // unlocks it. Under ScrollThrough the turns arrived and the click did not, so the dial
        // could be locked and then never unlocked -- it told you to click the wheel for a click
        // that was being spent on the torch before Roll was dispatched the key. A camera is also
        // the one app where losing the torch costs nothing: it has its own flash control, and it
        // handles the camera button itself.
        "com.gios.lightcamera",
    )

    private val scrollAwarePrefixes = listOf(
        "com.gios.",
        "com.lightfastread",
        "com.lightrss.reader",
        // Giovanni's phono fork ships under a Light-looking id, so it would otherwise be
        // caught by the hands-off list below and lose its button bindings for no reason.
        "com.lightphone.spotify",
    )

    /**
     * Apps that are color unless the user says otherwise.
     *
     * Kept as whole package ids, not prefixes. `com.gios.` covers every Light tool including the
     * ones that are deliberately mono — a notebook has nothing to show in color and the point of
     * this phone is that it does not — so claiming the prefix would quietly undo the feature it
     * is meant to serve. Each entry earns its place:
     *
     * - **Roll** and **BrightChat** are [ColorRule.Passthrough], not Color. Both grant themselves
     *   `WRITE_SECURE_SETTINGS` and drive the daltonizer directly, so a rule here is a second
     *   writer fighting the first — and the app that knows whether this particular screen wants
     *   colour is the app, not this one. Passthrough is how they end up in colour: by not being
     *   interfered with.
     * - **The stock camera** is [ColorRule.Color], because it has no such grant and cannot ask.
     *   A viewfinder in greyscale misrepresents a photo that comes out colour regardless.
     * - **BrightNotebook** is [ColorRule.Color] even though it holds the grant, because what it
     *   asks for is not per-screen: it holds the whole app in colour while it is in front. Two
     *   writers that want the same thing are not a fight, and stating it here is what makes it work
     *   on a phone where the notebook itself was never granted anything.
     * - **BrightMusic** is [ColorRule.Color] for the same reason, from its v0.59. Before that it
     *   held colour per cover and restored greyscale between them, which this app answered by
     *   re-asserting colour — the two took turns on every scroll. It states one thing now, and so
     *   does this.
     *
     * To add one, put the id here rather than telling people to set it by hand — a preset that
     * ships is a preset that works on a phone nobody has configured. Prefer Passthrough for
     * anything that holds the grant itself.
     */
    val colorPresets: Map<String, ColorRule> = mapOf(
        // Roll: grants itself WRITE_SECURE_SETTINGS and sets its own colour.
        "com.gios.lightcamera" to ColorRule.Passthrough,
        // BrightChat: same, and it wants mono on some of its own screens.
        "com.gios.lightchat" to ColorRule.Passthrough,
        // The stock LightOS camera, which holds no grant and cannot speak for itself.
        "com.android.camera2" to ColorRule.Color,
        // BrightNotebook: [ColorRule.Color], and the exception to the "prefer Passthrough for apps
        // that hold the grant" rule above. It does hold the grant, and it uses it to hold the whole
        // app in colour while it is in front — days, planner and photographs alike — so the two
        // writers are not in an argument, they agree. Stating it here is what makes the notebook
        // work on a phone where *it* was never granted anything: with no rule it resolved to
        // [ColorRule.Default], and Default is an opinion, so every window it raised was repainted
        // mono over the top of its own request.
        "com.gios.lightnotebook" to ColorRule.Color,
        // BrightMusic: same shape as the notebook from v0.59 on. It used to hold colour only around
        // a cover and write greyscale back on the way out of one, which is the flicker this table
        // warns about — every release was answered by this app re-asserting colour, on every scroll.
        // It now states colour for as long as it is in front, and this states the same thing.
        "com.lightphone.spotify" to ColorRule.Color,
        // LightOS itself, and the strongest case in this table for [ColorRule.Passthrough]: it is
        // the daltonizer's actual owner, and it has a colour setting *per tool* of its own —
        // camera, album and directions in colour, the rest of the layer grey. Every tool it draws
        // is the same package, so this app cannot tell those screens apart; `Default` therefore
        // meant one answer for all of them, and the answer was the baseline. Which is mono. So
        // every window LightOS raised wrote mono over the choice LightOS had just made, and the
        // log read `com.lightos DEFAULT want 1/0 got 1/0 ok` a dozen times over while somebody
        // wondered why the album would not stay in colour.
        //
        // Nothing here can improve on LightOS's own answer, so it is left to give it.
        "com.lightos" to ColorRule.Passthrough,
    )

    /**
     * The color rule for a package with no stored preference. [ColorRule.Default] for everything
     * not in [colorPresets] — that is what keeps the phone mono by default.
     */
    fun builtInColorRuleFor(pkg: String): ColorRule =
        colorPresets[pkg] ?: ColorRule.Default

    /**
     * The rule for the app in front, from the four places one can come from.
     *
     * The order *is* the behaviour, which is why it is a function of four arguments and not four
     * lookups inline at the call site. Every one of these steps was a bug at some point and the
     * fix each time was where it sits in this list:
     *
     *  1. **What the user set.** An explicit choice on the per-app list wins outright. Somebody who
     *     has told this phone that an app is mono did not mean "unless the app disagrees".
     *  2. **What the app is asking for**, over [com.gios.lightcontrol.color.ColorService]. Second
     *     rather than third because it is the only source that knows *which screen* the app is on,
     *     and above the preset table on purpose: an app that has been migrated onto the request
     *     path is very likely still carrying a [ColorRule.Passthrough] preset from when it wrote
     *     the settings itself, and reading the table first would mean a migrated app asks politely
     *     and is answered with the rule that says "ignore this app". That is a release where the
     *     screen goes grey and nothing in the log explains it.
     *  3. **What the app declares in its manifest.** One opinion for the whole app, from a package
     *     that has never been launched.
     *  4. **The preset table.** What this app knows about apps that cannot speak for themselves.
     *
     * Pure, so the order can be tested. See `ColorResolveTest`.
     */
    fun resolveColorRule(
        stored: ColorRule?,
        asked: ColorRule?,
        declared: ColorRule?,
        preset: ColorRule,
    ): ColorRule = stored ?: asked ?: declared ?: preset

    /**
     * What an app says about itself in its own manifest:
     *
     * ```xml
     * <meta-data android:name="com.gios.brightcontrol.color" android:value="color" />
     * ```
     *
     * `color` or `mono`; anything else, including the tag being absent, is null and falls through.
     *
     * ### Why read it rather than table it
     *
     * [colorPresets] is a list of other people's apps kept by hand in this file, which means every
     * new app is grey until somebody ships a BrightControl release naming it. A tag moves that
     * decision into the app, where it is made by whoever knows the answer, and it is true from the
     * moment the app is installed — before it has ever been opened, which is more than the request
     * path can promise.
     *
     * ### Why it is safe to believe
     *
     * A manifest value only ever describes the package that declares it: the package name is the
     * key this is read under, so there is no field in which an app could claim to be another one.
     * And what it can claim is one of two words. It is applied under the same foreground gate as
     * everything else, so the worst it can do is make its own screen colour, which is what
     * installing it already implied.
     *
     * A user override still beats it, which is the answer to "an app declares colour and I do not
     * want it to" — one tap on the per-app list.
     */
    fun declaredColorRule(pm: PackageManager, pkg: String): ColorRule? = runCatching {
        val meta = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
        when (meta?.getString(COLOR_META)?.trim()?.lowercase()) {
            "color", "colour" -> ColorRule.Color
            "mono", "monochrome" -> ColorRule.Mono
            else -> null
        }
    }.getOrNull()

    /**
     * The manifest key. Spelled `brightcontrol`, not `lightcontrol`: the package id is still
     * `com.gios.lightcontrol` for the reason every renamed app keeps its id — changing it is an
     * uninstall — but a name third-party developers are asked to type should be the name on the
     * app. Read as a string rather than a boolean so `mono` can exist at all.
     */
    const val COLOR_META = "com.gios.brightcontrol.color"

    /** The order a tap walks the colour states in, on the per-app list. */
    private fun colorCycleOrder(rule: ColorRule): ColorRule = when (rule) {
        ColorRule.Default -> ColorRule.Color
        ColorRule.Color -> ColorRule.Mono
        ColorRule.Mono -> ColorRule.Passthrough
        ColorRule.Passthrough -> ColorRule.Default
    }

    /**
     * What tapping a row on the per-app colour list should store, given what the app resolves to
     * now and what the preset table says.
     *
     * Two rules are being juggled and they collide. Landing back on the preset stores nothing, so
     * a later change to [colorPresets] still reaches the app. And AUTO — a stored nothing —
     * resolves *through* the preset table. For an app whose preset is [ColorRule.Passthrough],
     * those two meet: the step after PASS is AUTO, AUTO clears the override, and clearing the
     * override resolves straight back to PASS. The row redrew identical, so Roll and BrightChat
     * could not be moved off PASS however many times they were tapped — the whole list looked
     * frozen on exactly the two apps a person would want to try first.
     *
     * So the step is chosen by its outcome rather than by its name: any candidate that resolves
     * back to where the row already is gets skipped. Presets that are not Passthrough are
     * unaffected, because for them AUTO and the preset are different-looking states.
     *
     * Returns the value to store, where [ColorRule.Default] means "clear the override".
     */
    fun nextColorRule(resolved: ColorRule, builtIn: ColorRule): ColorRule {
        var candidate = colorCycleOrder(resolved)
        repeat(ColorRule.values().size) {
            val store = if (candidate == builtIn) ColorRule.Default else candidate
            val outcome = if (store == ColorRule.Default) builtIn else store
            if (outcome != resolved) return store
            candidate = colorCycleOrder(candidate)
        }
        return ColorRule.Default
    }

    fun ruleFor(prefs: Prefs, pkg: String): AppRule {
        val explicit = prefs.ruleFor(pkg)
        if (explicit != AppRule.Default) return explicit
        return builtInRuleFor(pkg)
    }

    /**
     * The built-in table, with no stored preference involved.
     *
     * Split out from [ruleFor] so the ordering below can be tested on the JVM — it is three
     * overlapping prefix lists and the order they are consulted in is the whole behavior, which
     * is exactly the kind of thing that looks obviously right and is not.
     */
    /**
     * Whether [pkg] implements the wheel's press as a control of its own.
     *
     * **Deliberately consulted from the built-in list alone, stored rules ignored — the same
     * first-claim treatment the camera key gets.** The failure this closes: a per-app rule of
     * ScrollThrough stored *before* an app started using its click — stored, back then, as the
     * only way to make its wheel turns work at all — beats the built-in table for ever, because
     * [ruleFor] prefers explicit rules. Every update after that carries the fix and none of them
     * apply it: the app tells you to click a wheel whose click this service is quietly spending
     * on the torch. Roll's dial lock was exactly that — "click to unlock" against a click that
     * never arrived, undiagnosable from the phone because the torch is silent and invisible in
     * daylight.
     *
     * A stored rule still governs everything else about the app — its turns, its camera key, its
     * colour. The one thing it can no longer do is eat the press of a wheel the app demonstrably
     * implements, which is the same one thing a stored rule was never able to do to a camera app's
     * camera button.
     */
    fun ownsWheelClick(pkg: String?): Boolean =
        pkg != null && ownsWheelPrefixes.any { pkg.startsWith(it) }

    fun builtInRuleFor(pkg: String): AppRule {
        // Whole-wheel apps first: they sit inside the scroll-aware prefixes and need the stronger
        // rule, not the weaker one. See [ownsWheelPrefixes].
        if (ownsWheelPrefixes.any { pkg.startsWith(it) }) return AppRule.Off
        // Scroll-aware next because it holds the more specific ids: one of them
        // sits inside a hands-off prefix, and being ours is the stronger fact.
        if (scrollAwarePrefixes.any { pkg.startsWith(it) }) return AppRule.ScrollThrough
        if (handsOffPrefixes.any { pkg.startsWith(it) }) return AppRule.Off
        return AppRule.Default
    }

    /**
     * Whether either edge strip stands in front of [pkg].
     *
     * Deliberately **not** [ruleFor]. The wheel rules answer a different question -- who
     * interprets a turn -- and one of their answers, [AppRule.Off], means "this app owns the whole
     * wheel", which says nothing at all about its edges. Roll and BrightRecorder are Off for the
     * wheel and both want a way back like anything else.
     *
     * Three refusals, and they are all about a gesture that already exists:
     *
     *  - **Light's own software and the SDK tools**, on [edgeRefusedPrefixes] -- which is *not*
     *    the wheel's hands-off list, because that list refuses a Light-looking package id and a
     *    Light-looking package id is not Light's software. Both of those already have a back
     *    button: LightOS has a gesture-navigation switch in its own settings, and an SDK tool has
     *    one drawn for it.
     *  - **The keyboard**, and anything else drawing over an app rather than replacing it, is
     *    never the front package by the time this is asked -- see [isTransientWindow] -- so the
     *    strip stays as the app underneath left it. Stated here because it is the reason no test
     *    for it is needed.
     *  - **Whatever the user has excluded.** The apps whose edges are controls.
     *
     * A null package -- nothing known in front, which happens for the first moment after the
     * service binds -- gets no strip. Guessing wrong here means eating the edge of a screen nobody
     * has identified.
     */
    fun edgeSwipeAllowed(prefs: Prefs, pkg: String?): Boolean {
        val p = pkg ?: return false
        if (p in prefs.edgeSwipeOffApps()) return false
        if (edgeSwipeRefusedByTable(p)) return false
        return true
    }

    /**
     * Whether the built-in table refuses [pkg] the strip, whatever the user has set.
     *
     * Split out for the settings list, which has to say ALWAYS OFF on those rows rather than
     * offering a switch that does nothing. Asked of the table rather than inferred from
     * [edgeSwipeAllowed] so the row keeps telling the truth if the table changes.
     */
    fun edgeSwipeRefusedByTable(pkg: String): Boolean =
        edgeRefusedPrefixes.any { pkg.startsWith(it) }

    /**
     * Whether the volume strip may be drawn while [pkg] is in front.
     *
     * The two rules that decide this used to sit inside the watcher, a hundred lines apart, and
     * the second of them was a package-prefix test written inline. They are here together because
     * the *order* between them is the whole answer, and an order cannot be reviewed when its two
     * halves are in different methods.
     *
     *  - **The user's list wins, and wins first.** [Prefs.volumeHudOffApps] is checked before the
     *    table and before the call exception, so an app on it never gets a strip — including
     *    LightOS's dialer, which the table would otherwise hand one to for the length of a call.
     *    That case is the report: someone who does not want a second volume overlay during a call
     *    had no way to say so.
     *  - **The table refuses Light's own screens**, which draw a volume UI of their own, so a
     *    strip over one is two overlays saying the same number.
     *  - **Except in a call**, because LightOS's dialer is the one Light screen with no volume UI
     *    at all, and it is in front for the whole call. Without this a call that is too quiet is a
     *    phone where the keys move a number nothing will show.
     *
     * A null package — nothing known in front, for the moment after the service binds — is allowed
     * the strip. The opposite of the edge strips, and for the opposite reason: guessing wrong here
     * hides a readout for one press, where guessing wrong there eats the edge of the screen.
     */
    fun volumeHudAllowed(pkg: String?, offApps: Set<String>, inCall: Boolean): Boolean {
        val p = pkg ?: return true
        if (p in offApps) return false
        if (inCall) return true
        return !volumeHudRefusedByTable(p)
    }

    /** Whether the built-in table refuses [pkg] the strip. Split out for the settings list. */
    fun volumeHudRefusedByTable(pkg: String): Boolean =
        volumeHudRefusedPrefixes.any { pkg.startsWith(it) }

    /**
     * Light's own software, which draws its own volume UI.
     *
     * `com.lightos` is the shell — dashboard and lock screen. The light-sdk namespace joined it at
     * LightOS v572, when Light gave SDK tools the same overlay.
     */
    private val volumeHudRefusedPrefixes = listOf("com.lightos", "com.thelightphone.")

    fun behaviorFor(prefs: Prefs, pkg: String?): Behavior {
        // LightOS's lock screen and dashboard are one activity, so they are one decision.
        if (pkg != null && pkg.startsWith("com.lightos")) {
            // Turns are still never reinterpreted here — doing something with them is what broke
            // it. The only choice is whether LightOS receives them at all: through, and it dims
            // the screen; or dropped on the floor, and its brightness ramp never runs.
            val turn = if (prefs.lightOsBrightness) TurnAction.PassThrough else TurnAction.Consume
            if (prefs.lightOsScreens) return Behavior(bareTurn = turn, buttonsActive = true)
            // Blocking turns is its own switch, so it applies even with the buttons left alone,
            // and so is the camera button — see [Behavior.cameraActive]. Hands-off for everything
            // else, which is what the table would have said anyway.
            // Only when a gesture names an app. See [Prefs.cameraNamesApp].
            val camera = prefs.cameraOnLightOs && prefs.cameraNamesApp()
            if (turn == TurnAction.Consume || camera) {
                return Behavior(bareTurn = turn, buttonsActive = false, cameraActive = camera)
            }
        }
        val rule = if (pkg == null) AppRule.Default else ruleFor(prefs, pkg)
        if (rule == AppRule.Off) {
            return Behavior(bareTurn = TurnAction.PassThrough, buttonsActive = false)
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
        return Behavior(bareTurn = bare, buttonsActive = true)
    }
}
