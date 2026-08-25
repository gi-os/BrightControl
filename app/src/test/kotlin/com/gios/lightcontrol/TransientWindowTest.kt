package com.gios.lightcontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which window-state events count as a new app in front.
 *
 * The color switch rides on this. Anything treated as the front app has its color rule
 * applied, so a window that merely floats over the app must not qualify — a keyboard opening
 * over a color app was turning the panel back to monochrome mid-sentence.
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
    fun `an edge-gesture overlay is not a new app in front`() {
        // Edge Gestures floats invisible swipe zones over every app; its window-state
        // events must not count as the front app, or they reset the panel to mono under
        // whatever app actually has focus.
        assertTrue(
            Policy.isTransientWindow(
                pkg = "com.ss.edgegestures",
                isInputMethodPackage = false,
                classIsActivity = false,
            ),
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
        // package gets held back by that, or every dialog would drop the color.
        assertFalse(
            Policy.isTransientWindow("com.gios.roll", isInputMethodPackage = false, classIsActivity = false),
        )
    }
}

/**
 * The general rule under the named list: a package with no way in is not an app you switched to.
 *
 * Both of the bugs the list was patched for were this — an overlay app and then MediaProvider's
 * delete confirmation, each read as a new front app, each firing Default, each putting a colour
 * screen back to monochrome under the user.
 */
class FrontDoorTest {

    @Test
    fun `a system dialog with no launcher entry is not the app in front`() {
        assertTrue(
            Policy.isTransientWindow(
                pkg = "com.android.providers.media.module",
                isInputMethodPackage = false,
                classIsActivity = true,
                hasFrontDoor = false,
            ),
        )
    }

    @Test
    fun `an ordinary app with a launcher entry is the app in front`() {
        assertFalse(
            Policy.isTransientWindow(
                pkg = "com.gios.roll",
                isInputMethodPackage = false,
                classIsActivity = true,
                hasFrontDoor = true,
            ),
        )
    }

    @Test
    fun `a launcher counts as having a front door`() {
        // LightOS declares CATEGORY_HOME and no CATEGORY_LAUNCHER. Reading that as "no front door"
        // would stop this service tracking the phone's own shell, which every key rule needs.
        assertFalse(
            Policy.isTransientWindow(
                pkg = "com.lightos",
                isInputMethodPackage = false,
                classIsActivity = true,
                hasFrontDoor = true,
            ),
        )
    }
}
