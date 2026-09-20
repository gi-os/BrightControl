package com.gios.lightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the preset system that needs no phone: the catalog and what each preset says it
 * will do.
 *
 * Applying one cannot be tested here — it writes SharedPreferences, and these tests run on a
 * plain JVM with no Android in sight. So what is checked is the part a person reads: a preset
 * that describes itself wrongly is worse than one that does nothing, because it is the
 * description they tap APPLY on.
 */
class PresetsTest {

    @Test
    fun `ids are unique and findable`() {
        val ids = Presets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(Presets.find(it)) }
        assertNull(Presets.find("no-such-preset"))
    }

    @Test
    fun `every preset says what it does, on either kind of phone`() {
        for (preset in Presets.all) {
            assertTrue(preset.label.isNotBlank())
            assertTrue(preset.sub.isNotBlank())
            for (launcher in Launcher.entries) {
                val lines = preset.lines(launcher, "Luma")
                assertTrue("${preset.id} on $launcher says nothing", lines.isNotEmpty())
                assertTrue(lines.none { it.isBlank() })
            }
        }
    }

    /**
     * The launcher answer is the only thing a preset is told, so it had better change something.
     * A preset that reads the same either way is one that has quietly stopped asking.
     */
    @Test
    fun `the developer's setup reads differently on a third-party launcher`() {
        val stock = Preset.DevsChoice.lines(Launcher.LightOs, "LightOS")
        val other = Preset.DevsChoice.lines(Launcher.ThirdParty, "Luma")
        assertTrue(stock != other)
        assertTrue(other.any { it.contains("Luma") })
        assertTrue(stock.none { it.contains("Luma") })
    }

    /** Factory settings describe the shipped defaults and are the same on any phone. */
    @Test
    fun `default is launcher-blind`() {
        assertEquals(
            Preset.Default.lines(Launcher.LightOs, "LightOS"),
            Preset.Default.lines(Launcher.ThirdParty, "Luma"),
        )
    }
}
