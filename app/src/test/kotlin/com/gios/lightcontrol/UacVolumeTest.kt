package com.gios.lightcontrol

import com.gios.lightcontrol.usb.UacVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic between "the adapter says its maximum is X" and "two bytes on the wire".
 *
 * Worth testing without a phone because every failure here is silent. A sign error writes a
 * plausible number to a real control and the adapter either ignores it or goes quiet, and from
 * the phone both look like the feature not working — which is also what success looks like if
 * you were not listening carefully. There is no exception to catch and no log line to read.
 */
class UacVolumeTest {

    @Test
    fun `zero is zero dB`() {
        assertEquals(0f, UacVolume.toDb(0), 0.001f)
        assertArrayEquals2(byteArrayOf(0x00, 0x00), UacVolume.encode(0))
    }

    @Test
    fun `volume is signed, which is the whole point`() {
        // −1 dB is 0xFF00 as an unsigned pattern. Read as unsigned it is 65280, which is +255 dB —
        // and writing that back is the bug this test exists for.
        val wire = byteArrayOf(0x00, 0xFF.toByte())
        assertEquals(-256, UacVolume.decode(wire))
        assertEquals(-1f, UacVolume.toDb(UacVolume.decode(wire)!!), 0.001f)
    }

    @Test
    fun `encode and decode round-trip across the range`() {
        for (units in intArrayOf(0, -1, -256, -6000, 256, 32767, -32767)) {
            val back = UacVolume.decode(UacVolume.encode(units))
            assertEquals("round-trip of $units", units, back)
        }
    }

    @Test
    fun `little-endian, not big`() {
        // 0x0100 is +1 dB. Low byte first on the wire.
        assertArrayEquals2(byteArrayOf(0x00, 0x01), UacVolume.encode(256))
    }

    @Test
    fun `a short reply decodes to nothing rather than to zero`() {
        // Zero would read as 0 dB — the one wrong answer indistinguishable from success.
        assertNull(UacVolume.decode(byteArrayOf(0x00)))
        assertNull(UacVolume.decode(ByteArray(0)))
        assertNull(UacVolume.decode(byteArrayOf(0x00, 0x00, 0x00), offset = 2))
    }

    @Test
    fun `one subrange, the common case`() {
        // count=1, MIN=-60 dB, MAX=0 dB, RES=0.5 dB
        val reply = shorts(1, -60 * 256, 0, 128)
        assertEquals(0, UacVolume.maxFromRange(reply, reply.size))
    }

    @Test
    fun `the loudest MAX wins across subranges`() {
        val reply = shorts(3, -9000, -3000, 128, -3000, -256, 64, -256, 0, 32)
        assertEquals(0, UacVolume.maxFromRange(reply, reply.size))
    }

    @Test
    fun `a truncated reply keeps the subranges that did arrive`() {
        // Two subranges promised, one and a half delivered.
        val full = shorts(2, -9000, -1280, 128, -1280, 0, 64)
        assertEquals(-1280, UacVolume.maxFromRange(full, 8))
    }

    @Test
    fun `no subranges is no answer`() {
        assertNull(UacVolume.maxFromRange(shorts(0), 2))
        assertNull(UacVolume.maxFromRange(byteArrayOf(0x01), 1))
    }

    @Test
    fun `silence is never offered as a level`() {
        val reply = shorts(1, -0x8000, -0x8000, 128)
        assertNull(UacVolume.maxFromRange(reply, reply.size))
        assertFalse(UacVolume.usableMax(UacVolume.SILENCE))
    }

    @Test
    fun `an absurdly quiet maximum is refused`() {
        // A device answering −80 dB to "how loud can you go" answered a different question, and
        // writing it would leave the phone quieter than this feature found it.
        assertFalse(UacVolume.usableMax(-80 * 256))
        assertFalse(UacVolume.usableMax(null))
        assertTrue(UacVolume.usableMax(0))
        assertTrue(UacVolume.usableMax(-6 * 256))
    }

    @Test
    fun `wValue and wIndex pack the way the spec says`() {
        // Control selector high, channel low.
        assertEquals(0x0200, UacVolume.wValue(UacVolume.CS_VOLUME, UacVolume.CH_MASTER))
        assertEquals(0x0201, UacVolume.wValue(UacVolume.CS_VOLUME, 1))
        // Unit id high, interface low.
        assertEquals(0x0200, UacVolume.wIndex(2, 0))
        assertEquals(0x0903, UacVolume.wIndex(9, 3))
    }

    @Test
    fun `formatting reads as decibels`() {
        assertEquals("0.0 dB", UacVolume.formatDb(0))
        assertEquals("−1.0 dB", UacVolume.formatDb(-256))
        assertEquals("−23.5 dB", UacVolume.formatDb((-23.5 * 256).toInt()))
    }

    /** Little-endian 16-bit words as the byte array a control transfer would carry. */
    private fun shorts(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { index, value ->
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun assertArrayEquals2(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        expected.indices.forEach { assertEquals("byte $it", expected[it], actual[it]) }
    }
}
