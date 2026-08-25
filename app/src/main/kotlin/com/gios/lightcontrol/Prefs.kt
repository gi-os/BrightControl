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
     * Whether pressing home twice, quickly, opens the app switcher.
     *
     * Note what this does *not* do: it does not delay the home button. The usual way to read a
     * double press is to hold the first one back until its partner could have arrived, and on
     * the one key a phone cannot do without that would mean paying a third of a second on every
     * press for a gesture used a dozen times a day. So the first press goes home immediately,
     * exactly as it always did, and the second one — inside [ControlService.HOME_DOUBLE_MS] —
     * opens the switcher over the top of wherever the first one landed. The cost is a glimpse of
     * home on the way to the list, which is the correct thing to spend, because the alternative
     * is a home button that feels slow.
     */
    var homeDoubleSwitcher: Boolean
        get() = sp.getBoolean("home_double_switcher", true)
        set(v) = sp.edit().putBoolean("home_double_switcher", v).apply()

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
    fun colorRuleFor(pkg: String): ColorRule {
        val stored = runCatching {
            sp.getString(colorKey(pkg), null)?.let { ColorRule.valueOf(it) }
        }.getOrNull()
        return stored ?: Policy.builtInColorRuleFor(pkg)
    }

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

    private fun appKey(pkg: String) = APP_PREFIX + pkg

    private companion object {
        const val HOTSPOT_AUTO = "hotspotAuto"
        const val HOTSPOT_TRIGGERS = "hotspotTriggers"
        const val HOTSPOT_TRUSTED = "hotspotTrustedSsids"
        const val HOTSPOT_SSID = "hotspotSsid"
        const val HOTSPOT_PASSWORD = "hotspotPassword"

        const val APP_PREFIX = "app:"
        const val COLOR_PREFIX = "color:"

        const val RESUME_APPS = "resume_apps"
        const val RESUME_FALLBACK = "resume_fallback"

        /** Lines of key log kept. A dozen is two or three presses' worth of story. */
        const val LOG_LINES = 12

        /** How much of a stack trace to keep. Enough for the cause and the top frames. */
        const val CRASH_CHARS = 1600
    }
}

/** Turning settings plus a package name into behavior, defaults and all. */
object Policy {

    /**
     * Windows that appear over an app without replacing it. The notification shade and this
     * app's own readout overlay both raise window-state events, and treating either as "the
     * app in front" would swap the key mapping — and the color — mid-turn.
     */
    private val transientPackages = setOf(
        "com.gios.lightcontrol",
        "com.android.systemui",
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
    ): Boolean = pkg in transientPackages || (isInputMethodPackage && !classIsActivity)

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
    )

    /**
     * The color rule for a package with no stored preference. [ColorRule.Default] for everything
     * not in [colorPresets] — that is what keeps the phone mono by default.
     */
    fun builtInColorRuleFor(pkg: String): ColorRule =
        colorPresets[pkg] ?: ColorRule.Default

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

    fun behaviorFor(prefs: Prefs, pkg: String?): Behavior {
        // LightOS's lock screen and dashboard are one activity, so they are one decision.
        if (pkg != null && pkg.startsWith("com.lightos")) {
            // Turns are still never reinterpreted here — doing something with them is what broke
            // it. The only choice is whether LightOS receives them at all: through, and it dims
            // the screen; or dropped on the floor, and its brightness ramp never runs.
            val turn = if (prefs.lightOsBrightness) TurnAction.PassThrough else TurnAction.Consume
            if (prefs.lightOsScreens) return Behavior(bareTurn = turn, buttonsActive = true)
            // Blocking turns is its own switch, so it applies even with the buttons left alone.
            // Hands-off for everything else, which is what the table would have said anyway.
            if (turn == TurnAction.Consume) {
                return Behavior(bareTurn = turn, buttonsActive = false)
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
