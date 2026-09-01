package com.gios.lightcontrol

import com.gios.lightcontrol.lock.LockWeatherEntry
import com.gios.lightcontrol.lock.WeatherText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTextTest {

    private val now = 1_756_700_000_000L

    private fun entry(
        updatedAt: Long = now - 10L * 60L * 1000L,
        tempC: Double = 22.2,
        hiC: Double = 27.2,
        loC: Double = 17.8,
        code: Int = 0,
        description: String = "Clear",
        precipPct: Int = 0,
    ) = LockWeatherEntry(updatedAt, tempC, hiC, loC, code, description, precipPct)

    // --- the conversion ---

    @Test
    fun `celsius becomes whole fahrenheit`() {
        assertEquals(72, WeatherText.fahrenheit(22.2))
        assertEquals(32, WeatherText.fahrenheit(0.0))
        assertEquals(14, WeatherText.fahrenheit(-10.0))
    }

    @Test
    fun `halves round up, not to even`() {
        // 21.5 C is exactly 70.7 F ... use one that lands on a half:
        // 20.833333 C -> 69.5 F must read 70, not 69.
        assertEquals(70, WeatherText.fahrenheit(125.0 / 6.0))
    }

    // --- the line ---

    @Test
    fun `a dry clear day reads as the spec wrote it`() {
        assertEquals("72° · CLEAR · H 81 L 64", WeatherText.label(entry(), now))
    }

    @Test
    fun `rain earns its place at forty percent`() {
        assertEquals(
            "72° · CLEAR · H 81 L 64 · RAIN 40%",
            WeatherText.label(entry(precipPct = 40), now),
        )
        assertEquals(
            "72° · CLEAR · H 81 L 64 · RAIN 60%",
            WeatherText.label(entry(precipPct = 60), now),
        )
    }

    @Test
    fun `thirty-nine percent is not worth the ink`() {
        assertEquals("72° · CLEAR · H 81 L 64", WeatherText.label(entry(precipPct = 39), now))
    }

    @Test
    fun `the description is uppercased, trimmed and collapsed`() {
        assertEquals(
            "72\u00B0 \u00B7 LIGHT RAIN \u00B7 H 81 L 64",
            WeatherText.label(entry(description = "  Light  rain "), now),
        )
    }

    // --- the staleness cutoff ---

    @Test
    fun `weather from just inside three hours still draws`() {
        val at = now - WeatherText.MAX_AGE_MS + 1_000L
        assertEquals("72° · CLEAR · H 81 L 64", WeatherText.label(entry(updatedAt = at), now))
    }

    @Test
    fun `weather older than three hours is no weather at all`() {
        assertNull(WeatherText.label(entry(updatedAt = now - WeatherText.MAX_AGE_MS - 1L), now))
    }

    @Test
    fun `a zero timestamp is nothing, not 1970's weather`() {
        assertNull(WeatherText.label(entry(updatedAt = 0L), now))
    }

    @Test
    fun `a blank description hides the line rather than drawing a gap`() {
        assertNull(WeatherText.label(entry(description = "   "), now))
    }
}
