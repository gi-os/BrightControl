package com.gios.lightcontrol

import com.gios.lightcontrol.switcher.HomeApp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the switcher's pinned Home row does, given what the home button's tap is bound to.
 *
 * The rule is small and the cost of getting it wrong is not: this is also the answer that decides
 * which package disappears from the recents list, so a wrong action here removes the wrong app.
 */
class SwitcherHomeRowTest {

    @Test
    fun `a launcher bound to the tap is what Home means`() {
        // The case this feature exists for. Bind home's tap to Luma and Luma stops being an app in
        // the list, because it stopped being one the moment it became where the home button goes.
        assertEquals(Action.Launch("app.luma"), HomeApp.homeAction(Action.Launch("app.luma")))
    }

    @Test
    fun `LightOS stays LightOS`() {
        // Not folded into DefaultHome. Arriving by this action is what makes LightOS a visit
        // rather than a landing, which is what hands the home button back while you are there.
        assertEquals(Action.LightOsHome, HomeApp.homeAction(Action.LightOsHome))
    }

    @Test
    fun `a tap that is not a home at all still gets a way out`() {
        // The pinned row's promise is that the list always has an exit. It is not a second copy of
        // whatever the home button happens to be doing, so a tap bound elsewhere falls back to the
        // system's own home rather than to Back, the torch, or nothing.
        assertEquals(Action.DefaultHome, HomeApp.homeAction(Action.Back))
        assertEquals(Action.DefaultHome, HomeApp.homeAction(Action.Torch))
        assertEquals(Action.DefaultHome, HomeApp.homeAction(Action.Switcher))
        assertEquals(Action.DefaultHome, HomeApp.homeAction(Action.PassThrough))
        // Resume is the near miss: it goes home only when there is nothing to resume, so it names
        // no destination this row could promise. Home is home.
        assertEquals(Action.DefaultHome, HomeApp.homeAction(Action.Resume))
    }

    @Test
    fun `an unreadable binding is still a home`() {
        // Prefs is read inside a runCatching -- a service starting before its storage is ready
        // hands this a null, and a switcher with no way out of it is the one outcome that is not
        // allowed.
        assertEquals(Action.DefaultHome, HomeApp.homeAction(null))
    }
}
