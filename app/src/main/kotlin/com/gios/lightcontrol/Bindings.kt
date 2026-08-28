package com.gios.lightcontrol

/** The buttons that can be bound. The wheel's *turns* are not here — see [TurnAction]. */
enum class Button {
    /** Pressing the wheel in. Doubles as the brightness modifier when turned while held. */
    WheelClick,

    /** The camera button. Its two scancodes are treated as one button. */
    Camera,

    VolumeUp,

    VolumeDown,

    /**
     * The home button. Tap is yours, hold opens LightOS — see [Action.LightOsHome].
     *
     * The only button whose *unbound* state is load-bearing: a phone you cannot get home on is
     * broken in a way a dead flashlight isn't, so the service treats this one specially at every
     * step. See `ControlService.onHome`.
     */
    Home,
    ;

    val label: String
        get() = when (this) {
            WheelClick -> "Wheel click"
            Camera -> "Camera button"
            VolumeUp -> "Volume up"
            VolumeDown -> "Volume down"
            Home -> "Home button"
        }
}

/**
 * Short press, long press, or two quick presses.
 *
 * Three gestures on every button, each one a full [Action] chosen from the same picker. The
 * double tap was two hard-wired special cases before this — the wheel's, which switched what a
 * turn meant, and home's, which opened the switcher — and neither was bindable, listed, or
 * available on the other three buttons. They are ordinary bindings now, and they are still what
 * those two buttons ship with.
 */
enum class Gesture {
    Tap,
    Hold,
    DoubleTap,
    ;

    val label: String
        get() = when (this) {
            Tap -> "Tap"
            Hold -> "Hold"
            DoubleTap -> "Double tap"
        }
}

/** Which edge of the screen a swipe starts from. */
enum class EdgeSide {
    Left,
    Right,
    ;

    /**
     * The sign of a useful stroke along x, so one gesture class serves both edges. A left-edge
     * stroke travels right and a right-edge stroke travels left; measuring distance in the stroke's
     * own direction is what makes every rule read the same for both.
     */
    val dirX: Int get() = if (this == Left) 1 else -1

    val label: String get() = if (this == Left) "Left edge" else "Right edge"
}

/**
 * The mark the edge indicator draws.
 *
 * Three, and no more: the two gestures anybody will actually bind to an edge have a shape people
 * already know, and everything else gets a mark that says only "this will happen". A wrong-looking
 * icon is worse than a neutral one on a screen read at arm's length mid-drag.
 */
enum class EdgeGlyph {
    /** Pointing the way the screen you are going back to comes from. */
    Chevron,

    /** Two overlapping outlines: a list of apps, with no direction implied. */
    Cards,

    /** A filled square. Something will happen; the word beside it says what. */
    Mark,
}

/**
 * A short swipe versus a long one.
 *
 * The edge equivalent of [Gesture], and deliberately the same shape: two bindings per edge, each
 * one a full [Action], picked from the same screen the buttons use. A long swipe is the only second
 * gesture an edge has — there is nowhere to hold, because a finger resting on a strip is
 * indistinguishable from a finger that has not started yet.
 */
enum class EdgeLength {
    Short,
    Long,
    ;

    val label: String get() = if (this == Short) "Short swipe" else "Long swipe"
}

/**
 * One thing an [Action] can be bound to: a press of a button, or a swipe from an edge.
 *
 * Introduced so the picker is one screen rather than two. The buttons had it first and the edges
 * arrived second, and a second picker would have been a second list of actions to keep in step with
 * the first — which is exactly how a phone ends up able to bind an app to a button and not to a
 * gesture for no reason anybody chose.
 */
sealed interface BindSlot {

    data class Key(val button: Button, val gesture: Gesture) : BindSlot

    data class Edge(val side: EdgeSide, val length: EdgeLength) : BindSlot

    /** The heading on the picker. */
    val title: String
        get() = when (this) {
            is Key -> button.label
            is Edge -> side.label
        }

    /** The line under it. */
    val sub: String
        get() = when (this) {
            is Key -> gesture.label.lowercase()
            is Edge -> length.label.lowercase()
        }
}

/**
 * What a press does.
 *
 * [PassThrough] is not "nothing" — it is the one value that means *don't consume the key*, so
 * the app in front still gets it. [None] consumes and does nothing, which is how you make a
 * button inert. The distinction matters on the volume keys, where the wrong one silently
 * takes away volume control.
 */
sealed interface Action {

    data object PassThrough : Action

    data object None : Action

    data object Torch : Action

    /** The camera, resolved the way the home screen's camera key resolves it. */
    data object OpenCamera : Action

    data class Launch(val pkg: String) : Action

    /**
     * Back to the app the screen went off on — if it is one you chose — and otherwise home.
     *
     * The gap this fills: using a remote, or a recipe, or a map, is minutes of picking the phone
     * up, doing one thing, and putting it down. The screen times out between every one of those
     * and LightOS is what comes back, so a two-second task costs finding the app again.
     *
     * The obvious fix — have the app relaunch itself when the screen comes on — cannot work on
     * Android 14. A backgrounded app is cached, a cached app is frozen, and context-registered
     * broadcasts to a frozen app are *queued until it is unfrozen*: `ACTION_SCREEN_ON` arrives
     * only once something has already brought the app forward, which is the thing it was meant
     * to do. This service has no such problem. An accessibility service is bound by the system,
     * so its process is never cached and never frozen, and it is already watching which app is
     * in front.
     *
     * Which apps qualify is a list you pick, not "whatever you were last in". A home button that
     * sometimes goes home and sometimes returns you to Settings is a home button you cannot
     * trust, and this codebase's rule about that key is that it degrades into *uninstalled*, not
     * into surprising. With nothing chosen, or nothing to go back to, this is exactly
     * [DefaultHome].
     */
    data object Resume : Action

    /**
     * Home — whichever launcher is set as default.
     *
     * A plain `CATEGORY_HOME` intent, so it follows the system's own choice rather than naming
     * anything. If that choice is LightOS, this and [LightOsHome] do the same thing.
     */
    data object DefaultHome : Action

    /**
     * LightOS's dashboard specifically, named rather than resolved — the point of it is to reach
     * Light's home *when it isn't* the default any more.
     *
     * Both of these exist because a key cannot be un-consumed. Once the service swallows the home
     * button's DOWN to see whether a hold follows, the original press is gone; "let it through
     * after all" is not a thing the framework offers. So home is something to fire deliberately,
     * which also makes both bindable anywhere else.
     */
    data object LightOsHome : Action

    /**
     * Go back — `GLOBAL_ACTION_BACK`.
     *
     * Bindable anywhere now rather than being the left edge's private behaviour. It is the one
     * action on this phone with no hardware to reach it: LightOS removed the navigation bar, so a
     * sideloaded app that pushes a screen has no way out of it at all.
     *
     * Note what the global action's return value means, which is less than it looks: true says the
     * action was dispatched, not that anything went back. An app on its first screen accepts a back
     * and does nothing with it, and from out here that is indistinguishable from working.
     */
    data object Back : Action

    /**
     * Open the app switcher — this app's own list, the same window a double press of home puts up.
     *
     * One window, one list, one place it is built. A gesture that assembled its own would be a
     * second answer to "which apps" and a second way to fail.
     */
    data object Switcher : Action

    /**
     * The system's own settings, which LightOS ships no way to reach.
     *
     * `ACTION_SETTINGS`, resolved rather than named, so it lands wherever this build puts it.
     * The one action here that starts an activity, which is why it is in [needsActivityStart]
     * and [picksDestination] alongside [Launch].
     */
    data object OpenSettings : Action

    /** The notification shade — `GLOBAL_ACTION_NOTIFICATIONS`. */
    data object Shade : Action

    /** The quick settings panel — `GLOBAL_ACTION_QUICK_SETTINGS`. */
    data object QuickSettings : Action

    /** A screenshot, saved wherever the system saves them. Android 11 and up. */
    data object Screenshot : Action

    /** Lock the phone now, as the power button would. Android 9 and up. */
    data object LockNow : Action

    /** The power menu — `GLOBAL_ACTION_POWER_DIALOG`. */
    data object PowerMenu : Action

    /**
     * Flip the app in front between colour and monochrome, and remember it.
     *
     * Not a live override of the daltonizer: this writes the front app's colour rule and lets
     * the rule engine assert it, because the engine re-states the screen on every window change
     * and a second writer is exactly the bug this codebase already paid for once. So the flip
     * sticks — go back to the app tomorrow and it is still the way you left it.
     */
    data object ColorFlip : Action

    /** Flip what turning the wheel does, between brightness and scrolling. */
    data object SwitchTurn : Action

    /** Put this app's lock face up over whatever is on screen. */
    data object ShowLock : Action

    /** Raise or drop the hotspot, using the network name and password already saved. */
    data object Hotspot : Action

    /**
     * Volume, one step, with the strip shown.
     *
     * `adjustSuggestedStreamVolume` rather than an injected key — injection is signature-only —
     * so this moves whatever stream the keys would have moved. Which means it does the right
     * thing during a call without knowing there is one.
     */
    data object VolumeUp : Action

    data object VolumeDown : Action

    /** Brightness, one notch, exactly as a wheel turn would move it. */
    data object BrightnessUp : Action

    data object BrightnessDown : Action

    /** True if this action means the service should swallow the key. */
    val consumes: Boolean get() = this != PassThrough

    /** True if pressing actually does something. */
    val acts: Boolean get() = this != PassThrough && this != None

    /**
     * True if performing this means starting an activity from a service.
     *
     * Worth knowing about in advance, because a background activity start is dropped
     * *silently* on Android 14 without the overlay appop — `startActivity` throws nothing and
     * returns nothing, so there is no way to notice afterwards. On the home button that
     * distinction is the difference between a binding that does nothing and a phone you can't
     * get home on, so the service refuses to swallow the key for one of these until the grant
     * is actually there. [DefaultHome] is deliberately absent: it goes through
     * `performGlobalAction`, which needs no grant and answers honestly.
     */
    val needsActivityStart: Boolean
        get() = this is Launch || this == LightOsHome || this == OpenCamera || this == Resume ||
            this == OpenSettings

    /**
     * True if this action names a destination of its own — somewhere that is *not* wherever a
     * plain home press would land.
     *
     * The home button is the one that cares. Shadow mode (`ControlService.shadowHome`) works by
     * consuming nothing and letting LightOS see the whole press, then firing the tap on top;
     * LightOS reads a home press as "back to the idle face", so anything the tap opens is racing
     * a launcher that was told to come forward. That is invisible when the tap *is* home and
     * ruinous when it isn't — you chose an app and got the idle face. So a tap that picks a
     * destination is a reason to take the key rather than shadow it.
     *
     * [DefaultHome] is absent on purpose: it agrees with what LightOS was going to do anyway.
     */
    val picksDestination: Boolean
        get() = this is Launch || this == Resume || this == LightOsHome || this == OpenSettings

    fun store(): String = when (this) {
        PassThrough -> "pass"
        None -> "none"
        Torch -> "torch"
        OpenCamera -> "camera"
        is Launch -> "launch:$pkg"
        DefaultHome -> "home"
        LightOsHome -> "lightoshome"
        Resume -> "resume"
        Back -> "back"
        Switcher -> "switcher"
        OpenSettings -> "settings"
        Shade -> "shade"
        QuickSettings -> "quicksettings"
        Screenshot -> "screenshot"
        LockNow -> "locknow"
        PowerMenu -> "powermenu"
        ColorFlip -> "colorflip"
        SwitchTurn -> "switchturn"
        ShowLock -> "showlock"
        Hotspot -> "hotspot"
        VolumeUp -> "volup"
        VolumeDown -> "voldown"
        BrightnessUp -> "brightup"
        BrightnessDown -> "brightdown"
    }

    /**
     * A word for the edge indicator, in the caps this phone's type is set in.
     *
     * Short because it has to fit in a box a thumb's width from the edge of a 3.9" panel, and
     * because it is read at arm's length mid-gesture rather than studied. [Launch] answers null:
     * only the service can turn a package id into an app name, so the caller substitutes one.
     */
    val edgeLabel: String?
        get() = when (this) {
            PassThrough, None -> "OFF"
            Torch -> "TORCH"
            OpenCamera -> "CAMERA"
            DefaultHome -> "HOME"
            LightOsHome -> "LIGHTOS"
            Resume -> "RESUME"
            Back -> "BACK"
            Switcher -> "APPS"
            OpenSettings -> "SETTINGS"
            Shade -> "SHADE"
            QuickSettings -> "QUICK"
            Screenshot -> "SHOT"
            LockNow -> "LOCK"
            PowerMenu -> "POWER"
            ColorFlip -> "COLOUR"
            SwitchTurn -> "TURN"
            ShowLock -> "FACE"
            Hotspot -> "HOTSPOT"
            VolumeUp -> "VOL +"
            VolumeDown -> "VOL -"
            BrightnessUp -> "BRIGHT +"
            BrightnessDown -> "BRIGHT -"
            is Launch -> null
        }

    /** Which mark the indicator draws for this action. */
    val glyph: EdgeGlyph
        get() = when (this) {
            Back -> EdgeGlyph.Chevron
            Switcher -> EdgeGlyph.Cards
            else -> EdgeGlyph.Mark
        }

    companion object {
        fun parse(raw: String?): Action? = when {
            raw == null -> null
            raw == "pass" -> PassThrough
            raw == "none" -> None
            raw == "torch" -> Torch
            raw == "camera" -> OpenCamera
            raw == "home" -> DefaultHome
            raw == "lightoshome" -> LightOsHome
            raw == "resume" -> Resume
            raw == "back" -> Back
            raw == "switcher" -> Switcher
            raw == "settings" -> OpenSettings
            raw == "shade" -> Shade
            raw == "quicksettings" -> QuickSettings
            raw == "screenshot" -> Screenshot
            raw == "locknow" -> LockNow
            raw == "powermenu" -> PowerMenu
            raw == "colorflip" -> ColorFlip
            raw == "switchturn" -> SwitchTurn
            raw == "showlock" -> ShowLock
            raw == "hotspot" -> Hotspot
            raw == "volup" -> VolumeUp
            raw == "voldown" -> VolumeDown
            raw == "brightup" -> BrightnessUp
            raw == "brightdown" -> BrightnessDown
            raw.startsWith("launch:") -> Launch(raw.removePrefix("launch:"))
            else -> null
        }

        /**
         * What an edge swipe does out of the box.
         *
         * The two edges mirror each other, which needs no opinion about which is which: a short
         * swipe from the left goes back and a long one opens the switcher, and the right edge is
         * the same pair the other way round. Every one of the four is bindable to anything the
         * buttons can be bound to.
         *
         * Note that this only decides what a *live* edge does. Both edges are off until switched
         * on -- see [Prefs.leftEdgeOn] -- because they are the only features in this app that take
         * a touch away from the app in front.
         */
        fun defaultEdge(side: EdgeSide, length: EdgeLength): Action = when {
            side == EdgeSide.Left && length == EdgeLength.Short -> Back
            side == EdgeSide.Left -> Switcher
            length == EdgeLength.Short -> Switcher
            else -> Back
        }

        /**
         * How the phone behaves out of the box: the click is the flashlight, the camera
         * button opens the camera, and holding either does nothing — because holding the
         * wheel is already the brightness gesture, and a hold that also launched something
         * would fire every time you adjusted the screen.
         *
         * The volume keys pass through. They are the one pair that already works, and
         * consuming them by default would be taking away a function to add one.
         */
        /**
         * Whether a press on [button] may be swallowed, given what its three gestures are bound to.
         *
         * Everywhere but the volume keys the answer is "if any gesture consumes" — timing a hold
         * means keeping the DOWN, and a key kept for one gesture is kept for all of them.
         *
         * **The volume keys answer only for the tap.** Their tap is a repeating, system-owned
         * function this service cannot reproduce and would not know when to stop; keeping the press
         * to time a hold takes volume control away to add a binding, which is the one trade this
         * codebase refuses. Until v3.96 they answered like everything else, so a hold bound on one
         * volume key made every press on that key vanish — the volume stopped changing, and the
         * strip dutifully reported the level that had not moved, on every press.
         */
        fun consumesPress(button: Button, tap: Action, hold: Action, dbl: Action): Boolean =
            when (button) {
                Button.VolumeUp, Button.VolumeDown -> tap.consumes
                else -> tap.consumes || hold.consumes || dbl.consumes
            }

        fun default(button: Button, gesture: Gesture): Action = when {
            // The two double taps this phone already had, now written as bindings like anything
            // else. Every other button ships without one, which is what keeps its tap immediate:
            // a button with no double bound never waits to see whether a second press is coming.
            gesture == Gesture.DoubleTap -> when (button) {
                Button.WheelClick -> SwitchTurn
                Button.Home -> Switcher
                Button.VolumeUp, Button.VolumeDown -> PassThrough
                else -> None
            }
            // Tap goes home; holding reaches LightOS's dashboard by name, which is the one
            // thing a sideloaded phone loses — install a launcher that can see your APKs and
            // Light's own home screen becomes unreachable. Binding the hold is what makes the
            // service swallow the press, so every guard in `ControlService.onHome` exists
            // because of this line.
            button == Button.Home ->
                if (gesture == Gesture.Hold) LightOsHome else DefaultHome
            gesture == Gesture.Hold -> when (button) {
                Button.VolumeUp, Button.VolumeDown -> PassThrough
                else -> None
            }
            button == Button.WheelClick -> Torch
            button == Button.Camera -> OpenCamera
            else -> PassThrough
        }
    }
}
