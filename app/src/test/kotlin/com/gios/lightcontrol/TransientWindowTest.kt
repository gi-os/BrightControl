package com.gios.lightcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which window-state events count as a new app in front.
 *
 * The colour switch rides on this. Anything treated as the front app has its colour rule
 * applied, so a window that merely floats over the app must not qualify — a keyboard opening
 * over a colour app was turning the panel back to monochrome mid-sentence.
 */
class TransientWindowTest {

    @Test
    fun `a keyboard opening over an app is not a new app in front`() {
        // The soft-input window reports the IME's package with a window class, not an activity.
        assertTrue(
            Policy.isTransientWindow(
                pkg = "app.lightphonekeyboard",
                isInputMethodPackage = true,
                classIsActivity = false,
            ),
        )
    }

    @Test
    fun `a keyboard's own settings screen is a real app in front`() {
        // BrightThumb is a keyboard and also an ordinary app with a settings activity. Opening
        // that activity should apply its rule like anything else.
        assertFalse(
            Policy.isTransientWindow(
                pkg = "com.gios.brightthumb",
                isInputMethodPackage = true,
                classIsActivity = true,
            ),
        )
    }

    @Test
    fun `the shade and our own overlay stay transient`() {
        assertTrue(
            Policy.isTransientWindow("com.android.systemui", isInputMethodPackage = false, classIsActivity = true),
        )
        assertTrue(
            Policy.isTransientWindow("com.gios.lightcontrol", isInputMethodPackage = false, classIsActivity = true),
        )
    }

    @Test
    fun `an ordinary app is a new app in front`() {
        assertFalse(
            Policy.isTransientWindow("com.gios.roll", isInputMethodPackage = false, classIsActivity = true),
        )
    }

    @Test
    fun `an ordinary app is still the front app when its class is unrecognised`() {
        // Plenty of apps report a dialog or a window class on a state change. Only a keyboard
        // package gets held back by that, or every dialog would drop the colour.
        assertFalse(
            Policy.isTransientWindow("com.gios.roll", isInputMethodPackage = false, classIsActivity = false),
        )
    }
}
