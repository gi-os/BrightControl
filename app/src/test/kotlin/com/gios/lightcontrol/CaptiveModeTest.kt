package com.gios.lightcontrol

import com.gios.lightcontrol.portal.CaptiveMode
import com.gios.lightcontrol.portal.PortalRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The captive-portal mode as text and as a command.
 *
 * The reading of these values is the whole feature: a wrong answer here either leaves the phone
 * routing around a hotel network it is standing on, or tells the login screen to trust a
 * validated flag the platform never earned.
 */
class CaptiveModeTest {

    @Test fun `unset is the platform default, which is on`() {
        assertEquals("On (default)", CaptiveMode.label(null))
        assertEquals("On (default)", CaptiveMode.label(""))
        // `settings get` prints the literal word for an unset key, and it reaches here as text.
        assertEquals("On (default)", CaptiveMode.label("null"))
    }

    @Test fun `the three modes read out by name`() {
        assertEquals("Off", CaptiveMode.label("0"))
        assertEquals("On (default)", CaptiveMode.label("1"))
        assertEquals("Avoid", CaptiveMode.label("2"))
        assertEquals("Unknown (7)", CaptiveMode.label("7"))
    }

    @Test fun `the older detection switch wins when it is off`() {
        assertEquals("Off", CaptiveMode.label("1", "0"))
        assertEquals("On (default)", CaptiveMode.label("1", "1"))
        assertEquals("Off", CaptiveMode.label(null, "0"))
    }

    @Test fun `the command is the line a computer would run`() {
        assertEquals("settings put global captive_portal_mode 0", CaptiveMode.command(CaptiveMode.IGNORE))
        assertEquals("settings put global captive_portal_mode 1", CaptiveMode.command(CaptiveMode.PROMPT))
    }

    @Test fun `with detection off the login screen starts at a plain page`() {
        // The 204 endpoint proves a gate is shut and draws nothing. With no system portal URL and
        // no detection, an ordinary page is the only thing a portal can interrupt.
        assertEquals(PortalRoute.PLAIN_URL, PortalRoute.startUrl(null, detectionOn = false))
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl(null, detectionOn = true))
    }

    @Test fun `the system's own url still wins either way`() {
        val url = "http://gateway.example/login"
        assertEquals(url, PortalRoute.startUrl(url, detectionOn = false))
        assertEquals(url, PortalRoute.startUrl(url, detectionOn = true))
    }
}
