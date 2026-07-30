package com.gios.lightcontrol

/** The buttons that can be bound. The wheel's *turns* are not here — see [TurnAction]. */
enum class Button {
    /** Pressing the wheel in. Doubles as the brightness modifier when turned while held. */
    WheelClick,

    /** The camera button. Its two scancodes are treated as one button. */
    Camera,

    VolumeUp,

    VolumeDown,

    /** The home button. Tap is yours, hold is Light's — see [Action.LightOsHome]. */
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
     * Whatever the system considers home — LightOS's dashboard, here.
     *
     * This exists because a key cannot be un-consumed. Once the service swallows the home
     * button's DOWN to see whether a hold follows, the original press is gone; "let it through
     * after all" is not a thing the framework offers. So the way back to Light's home is an
     * action that fires it deliberately, which also makes it bindable anywhere else.
     */
    data object LightOsHome : Action

    /** True if this action means the service should swallow the key. */
    val consumes: Boolean get() = this != PassThrough

    /** True if pressing actually does something. */
    val acts: Boolean get() = this != PassThrough && this != None

    fun store(): String = when (this) {
        PassThrough -> "pass"
        None -> "none"
        Torch -> "torch"
        OpenCamera -> "camera"
        is Launch -> "launch:$pkg"
        LightOsHome -> "home"
    }

    companion object {
        fun parse(raw: String?): Action? = when {
            raw == null -> null
            raw == "pass" -> PassThrough
            raw == "none" -> None
            raw == "torch" -> Torch
            raw == "camera" -> OpenCamera
            raw == "home" -> LightOsHome
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
            // Both gestures on the home button start out as Light's home, so out of the box the
            // button does exactly what it did before. Bind the tap to your own home and the hold
            // is already the way back to theirs — which is the whole point of the pairing, and
            // why the tap cannot be left as PassThrough: the service has to swallow the DOWN to
            // see whether a hold is coming, so a tap that "passes through" would pass nothing.
            button == Button.Home -> LightOsHome
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
