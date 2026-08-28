package com.gios.lightcontrol

import com.gios.lightcontrol.switcher.HomeApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which app the switcher's pinned Home row opens.
 *
 * The rule is a stored choice now, and these tests are mostly here to keep it one. Two releases
 * were spent deducing this instead — v3.97 from the home button's tap binding, v3.98 from "the one
 * launcher that is not LightOS" — and both gave somebody the wrong app, silently, because the
 * answer also decides which package disappears from the recents.
 */
class SwitcherHomeRowTest {

    private val LIGHTOS = "com.lightos"
    private val LUMA = "app.luma"

    @Test
    fun `the chosen app is the answer`() {
        val t = HomeApp.pick(LUMA, chosenInstalled = true, systemHome = LIGHTOS)
        assertEquals(LUMA, t.pkg)
        assertEquals(Action.Launch(LUMA), t.action)
    }

    @Test
    fun `nothing chosen means the system's home`() {
        for (empty in listOf("", null)) {
            val t = HomeApp.pick(empty, chosenInstalled = false, systemHome = LIGHTOS)
            assertEquals(LIGHTOS, t.pkg)
            assertEquals(Action.DefaultHome, t.action)
        }
    }

    @Test
    fun `an uninstalled choice falls back rather than opening nothing`() {
        // The setting keeps the package -- it is not validated on write, because an app can go
        // away after it is picked. So the row has to answer for that here, or an uninstall leaves
        // a dead entry pinned to the bottom of the switcher.
        val t = HomeApp.pick(LUMA, chosenInstalled = false, systemHome = LIGHTOS)
        assertEquals(LIGHTOS, t.pkg)
        assertEquals(Action.DefaultHome, t.action)
    }

    @Test
    fun `picking LightOS gets LightOS's own action`() {
        // Not a launch. Arriving by LightOsHome is what makes it a *visit* -- the state where the
        // home button belongs to LightOS so you can walk through its menu.
        val t = HomeApp.pick(LIGHTOS, chosenInstalled = true, systemHome = LIGHTOS)
        assertEquals(LIGHTOS, t.pkg)
        assertEquals(Action.LightOsHome, t.action)
    }

    @Test
    fun `the resolver activity is not an app anybody meant`() {
        // "android" is the framework's disambiguation screen, shown when there is no default. It
        // is not something to hide from the recents or open an App info page for.
        assertNull(HomeApp.pick("", chosenInstalled = false, systemHome = "android").pkg)
        assertNull(HomeApp.pick("", chosenInstalled = false, systemHome = null).pkg)
        assertNull(HomeApp.pick("", chosenInstalled = false, systemHome = "  ").pkg)
    }

    @Test
    fun `a home with no package still acts`() {
        // pkg null costs the row its App info hold and hides nothing from the recents. It must
        // still open something: a pinned row that does nothing is worse than no pinned row.
        assertEquals(Action.DefaultHome, HomeApp.pick(null, false, null).action)
    }
}
