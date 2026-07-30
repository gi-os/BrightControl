package com.gios.lightcontrol.keys

import android.view.KeyEvent

/** The physical controls the LPIII sends to whichever app has focus. */
enum class LightKey {
    /** Wheel turned towards the top of the phone. */
    WheelUp,

    /** Wheel turned towards the bottom of the phone. */
    WheelDown,

    /** Wheel pressed in — the flashlight press on the home screen. */
    WheelClick,

    /** Camera button, second stage. This is the shutter. */
    Camera,

    /** Camera button, first stage. Arrives paired with [Camera], order not guaranteed. */
    Focus,
}

/**
 * Recognising the LPIII's wheel and camera button.
 *
 * The wheel is not a rotary encoder. It is a `Pixart pat9126ja` optical sensor on
 * `/dev/input/event4` that emits one discrete DOWN+UP key pair per notch, roughly 35–60 ms
 * apart, so this is key handling and not `AXIS_SCROLL` / `onRotaryScrollEvent`.
 *
 * Light patched `/system/usr/keylayout/Generic.kl` — the layout every input device on the
 * phone loads — to relabel five scancodes:
 *
 * ```
 * key 19    WHEEL_CCW      # wheel up      (Pixart, was R)
 * key 20    WHEEL_CW       # wheel down    (Pixart, was T)
 * key 66    WHEEL_CLICK    # wheel press   (gpio-keys, was F8)
 * key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
 * key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
 * ```
 *
 * Nothing intercepts these in `PhoneWindowManager`; they are dispatched to the focused
 * window like any other key, which is why brightness and the flashlight are dead inside
 * every sideloaded app. Light's own tools implement the behaviour in their app layer, and
 * this app is that layer for everything else.
 *
 * `WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` are not AOSP keycodes; Light added them, so
 * their integer values are Light's to change. Hence two ways in, in order:
 *
 *  1. Resolve the label to a keycode at runtime. [KeyEvent.keyCodeFromString] reads the
 *     same native label table the keylayout parser uses, so Light's additions resolve.
 *  2. Fall back to the raw Linux scancode, which is fixed by the hardware. Scancode 19 is
 *     also `r` on a Bluetooth keyboard, so that path is gated on the device name.
 */
object LightKeys {

    // Linux scancodes, from `getevent -pl`. These are hardware, not software.
    private const val SCAN_WHEEL_UP = 19 // KEY_R
    private const val SCAN_WHEEL_DOWN = 20 // KEY_T
    private const val SCAN_WHEEL_CLICK = 66 // KEY_F8
    private const val SCAN_FOCUS = 80 // KEY_KP2
    private const val SCAN_CAMERA = 27 // KEY_RIGHTBRACE

    /** The only two devices these scancodes may be trusted from. */
    private val trustedDevices = setOf("Pixart pat9126ja", "gpio-keys")

    private val byScanCode = mapOf(
        SCAN_WHEEL_UP to LightKey.WheelUp,
        SCAN_WHEEL_DOWN to LightKey.WheelDown,
        SCAN_WHEEL_CLICK to LightKey.WheelClick,
        SCAN_FOCUS to LightKey.Focus,
        SCAN_CAMERA to LightKey.Camera,
    )

    private val byKeyCode: Map<Int, LightKey> = buildMap {
        putLabel("WHEEL_CCW", LightKey.WheelUp)
        putLabel("WHEEL_CW", LightKey.WheelDown)
        putLabel("WHEEL_CLICK", LightKey.WheelClick)
        putLabel("FOCUS", LightKey.Focus)
        putLabel("CAMERA", LightKey.Camera)
    }

    private fun MutableMap<Int, LightKey>.putLabel(label: String, key: LightKey) {
        val code = runCatching { KeyEvent.keyCodeFromString(label) }
            .getOrDefault(KeyEvent.KEYCODE_UNKNOWN)
        if (code != KeyEvent.KEYCODE_UNKNOWN) put(code, key)
    }

    /** Which control produced [event], or null if it wasn't one of ours. */
    fun of(event: KeyEvent): LightKey? {
        byKeyCode[event.keyCode]?.let { return it }
        // Either the labels moved or this build doesn't have them. Trust the scancode, but
        // only from the two devices that physically own these controls — otherwise a paired
        // keyboard's `r` would dim the screen.
        val device = event.device?.name ?: return null
        if (device in trustedDevices) return byScanCode[event.scanCode]
        return null
    }

    /** Whether this LightOS build labels the wheel at all, for the settings readout. */
    fun wheelLabelsPresent(): Boolean = byKeyCode.containsValue(LightKey.WheelUp)

    /** A readable name for a keycode, so a settings screen can show what it saw. */
    fun describe(key: LightKey): String = when (key) {
        LightKey.WheelUp -> "Wheel up"
        LightKey.WheelDown -> "Wheel down"
        LightKey.WheelClick -> "Wheel click"
        LightKey.Camera -> "Camera button"
        LightKey.Focus -> "Camera button (focus)"
    }
}
