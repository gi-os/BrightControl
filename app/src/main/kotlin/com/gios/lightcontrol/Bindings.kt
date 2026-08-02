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

/** Short press versus long press. */
enum class Gesture {
    Tap,
    Hold,
    ;

    val label: String get() = if (this == Tap) "Tap" else "Hold"
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
        get() = this is Launch || this == LightOsHome || this == OpenCamera || this == Resume

    fun store(): String = when (this) {
        PassThrough -> "pass"
        None -> "none"
        Torch -> "torch"
        OpenCamera -> "camera"
        is Launch -> "launch:$pkg"
        DefaultHome -> "home"
        LightOsHome -> "lightoshome"
        Resume -> "resume"
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
            raw.startsWith("launch:") -> Launch(raw.removePrefix("launch:"))
            else -> null
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
        fun default(button: Button, gesture: Gesture): Action = when {
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
