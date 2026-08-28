package com.gios.lightcontrol

import com.gios.lightcontrol.switcher.HomeApp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the switcher's pinned Home row goes.
 *
 * The rule is small and the cost of getting it wrong is not: this same answer decides which package
 * disappears from the recents list, so a wrong one here removes the wrong app *and* sends Home
 * somewhere nobody asked for. v3.97 shipped a version of it that resolved to LightOS for everybody
 * who had not re-bound their home button, which is the case these tests exist to pin down.
 */
class SwitcherHomeRowTest {

    private val LIGHTOS = "com.lightos"
    private val LUMA = "app.luma"

    @Test
    fun `a launcher bound to the tap wins outright`() {
        // The user has answered the question already. Nothing looks any further.
        val t = HomeApp.pick(Action.Launch(LUMA), LIGHTOS, listOf("app.other"))
        assertEquals(LUMA, t.pkg)
        assertEquals(Action.Launch(LUMA), t.action)
    }

    @Test
    fun `LightOS stays LightOS`() {
        // Not folded into DefaultHome. Arriving by this action is what makes LightOS a visit
        // rather than a landing, which is what hands the home button back while you are there.
        val t = HomeApp.pick(Action.LightOsHome, LIGHTOS, listOf(LUMA))
        assertEquals(LIGHTOS, t.pkg)
        assertEquals(Action.LightOsHome, t.action)
    }

    @Test
    fun `the role holding LightOS is not an answer when another launcher is installed`() {
        // The bug. The shipped tap is DefaultHome and LightOS holds the HOME role on every one of
        // these phones -- it has to, or it crash-loops -- so following the role sent Home to
        // LightOS for everybody using Luma. Launch, not DefaultHome: a CATEGORY_HOME intent would
        // go straight back to the role holder.
        val t = HomeApp.pick(Action.DefaultHome, LIGHTOS, listOf(LUMA))
        assertEquals(LUMA, t.pkg)
        assertEquals(Action.Launch(LUMA), t.action)
    }

    @Test
    fun `a real default launcher is followed rather than named`() {
        // If the role holder is your launcher, DefaultHome already reaches it, and following the
        // system beats pinning a package -- change the default and the row changes with it.
        val t = HomeApp.pick(Action.DefaultHome, LUMA, listOf(LUMA))
        assertEquals(LUMA, t.pkg)
        assertEquals(Action.DefaultHome, t.action)
    }

    @Test
    fun `two launchers is a question this cannot answer`() {
        // Falls back rather than guessing between them. Binding the tap is the way out, and it is
        // the only one that cannot be wrong about which launcher a person means.
        val t = HomeApp.pick(Action.DefaultHome, LIGHTOS, listOf(LUMA, "app.other"))
        assertEquals(LIGHTOS, t.pkg)
        assertEquals(Action.DefaultHome, t.action)
    }

    @Test
    fun `no other launcher leaves the system home alone`() {
        val t = HomeApp.pick(Action.DefaultHome, LIGHTOS, emptyList())
        assertEquals(LIGHTOS, t.pkg)
        assertEquals(Action.DefaultHome, t.action)
    }

    @Test
    fun `the resolver activity is not an app anybody meant`() {
        // "android" is the framework's disambiguation screen, which appears when there is no
        // default. Treated as no answer, so the launcher beside it is still found.
        val t = HomeApp.pick(Action.DefaultHome, "android", listOf(LUMA))
        assertEquals(LUMA, t.pkg)
        assertEquals(Action.Launch(LUMA), t.action)
    }

    @Test
    fun `a tap that is not a home at all still gets a way out`() {
        // The pinned row's promise is that the list always has an exit. It is not a second copy of
        // whatever the home button happens to be doing. Resume is the near miss: it goes home only
        // when there is nothing to resume, so it names no destination this row could promise.
        for (tap in listOf(Action.Back, Action.Torch, Action.Switcher, Action.PassThrough, Action.Resume, null)) {
            assertEquals(Action.Launch(LUMA), HomeApp.pick(tap, LIGHTOS, listOf(LUMA)).action)
            assertEquals(Action.DefaultHome, HomeApp.pick(tap, LIGHTOS, emptyList()).action)
        }
    }
}
