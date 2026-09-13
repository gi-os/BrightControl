package com.gios.lightcontrol

import com.gios.lightcontrol.keys.Layer
import com.gios.lightcontrol.keys.LayerSwitch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The layer toggle's direction, which is the whole of it: everything else is two calls that
 * already existed. A toggle that picks the wrong way is worse than the two separate actions it
 * replaces, because the two at least did what they said.
 */
class LayerSwitchTest {

    private val lightOs = "com.lightos"

    private fun to(front: String?) = LayerSwitch.to(front, lightOs)

    @Test
    fun `on LightOS it leaves for the launcher`() {
        assertEquals(Layer.Launcher, to("com.lightos"))
    }

    @Test
    fun `LightOS's other screens count as LightOS`() {
        // It is a prefix, not an equality: the dashboard, the dialer and the lock screen are all
        // separate packages under it, and standing on any of them is standing on Light.
        assertEquals(Layer.Launcher, to("com.lightos.dialer"))
        assertEquals(Layer.Launcher, to("com.lightos.settings"))
    }

    @Test
    fun `in any other app it brings LightOS over`() {
        assertEquals(Layer.LightOs, to("com.gios.lightcontrol"))
        assertEquals(Layer.LightOs, to("com.vandam.zero"))
    }

    @Test
    fun `another launcher is still the far side`() {
        // Luma is a launcher and not LightOS, and "I am on my Android home screen" is exactly the
        // moment the toggle is pressed to go the other way.
        assertEquals(Layer.LightOs, to("app.luma"))
    }

    @Test
    fun `an unknown front goes to LightOS`() {
        // A service rebound by an update has seen no window event yet. Being wrong here costs a
        // press that opens a dashboard already on screen; the other default would walk you out of
        // LightOS when you asked to go into it.
        assertEquals(Layer.LightOs, to(null))
    }

    @Test
    fun `a shorter name that LightOS's own starts with is not LightOS`() {
        assertEquals(Layer.LightOs, to("com.light"))
    }
}
