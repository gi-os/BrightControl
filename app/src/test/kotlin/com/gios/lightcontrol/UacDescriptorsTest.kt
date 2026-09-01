package com.gios.lightcontrol

import com.gios.lightcontrol.usb.UacDescriptors
import com.gios.lightcontrol.usb.UacVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor walk, against blobs shaped like the ones real adapters return.
 *
 * Two things are being protected here. The first is the walk itself: it advances by a length byte
 * read out of the data it is walking, so a malformed blob is an infinite loop or an index out of
 * bounds, and the data comes off a device this app did not make. The second is the candidate order,
 * which is what makes the feature work on a BADD device that describes no units at all — get that
 * wrong and the Apple adapter, the one case that was actually measured, silently stops working.
 */
class UacDescriptorsTest {

    @Test
    fun `a declared feature unit is found`() {
        val raw = blob(
            interfaceDescriptor(number = 1, cls = 0x01, subclass = 0x01, protocol = 0x20),
            csInterface(subtype = 0x01, payload = byteArrayOf(0x00, 0x02, 0x09, 0x00)),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x02, 0x01, 0x03)),
        )
        val controls = UacDescriptors.parse(raw)
        assertEquals(1, controls.size)
        val control = controls[0]
        assertEquals(1, control.interfaceNumber)
        assertEquals(UacVolume.PROTOCOL_UAC2, control.protocol)
        assertEquals(listOf(2), control.declaredUnits)
        assertTrue(control.declared)
        // Declared first, always: a device that described itself gets believed before any guess.
        assertEquals(2, control.candidateUnits().first())
    }

    @Test
    fun `a UAC3 feature unit uses the other subtype`() {
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x30),
            csInterface(subtype = 0x07, payload = byteArrayOf(0x05, 0x01, 0x00)),
        )
        val control = UacDescriptors.parse(raw).single()
        assertEquals(listOf(5), control.declaredUnits)
        assertEquals(UacVolume.PROTOCOL_UAC3, control.protocol)
    }

    @Test
    fun `a BADD configuration declares no units and still gets candidates`() {
        // The Apple adapter's shape: an audio control interface with nothing class-specific inside
        // it, because BADD's topology is defined by the spec rather than described per device.
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x30),
            interfaceDescriptor(number = 1, cls = 0x01, subclass = 0x02, protocol = 0x30),
        )
        val control = UacDescriptors.parse(raw).single()
        assertFalse(control.declared)
        assertTrue(control.declaredUnits.isEmpty())
        // 2 is what the adapter answers to over adb, so it is the first thing tried.
        assertEquals(2, control.candidateUnits().first())
        assertTrue(control.candidateUnits().size > 4)
    }

    @Test
    fun `streaming interfaces are not control interfaces`() {
        val raw = blob(
            // subclass 2 is AUDIOSTREAMING. It has endpoints, not units.
            interfaceDescriptor(number = 1, cls = 0x01, subclass = 0x02, protocol = 0x20),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x07, 0x01, 0x03)),
        )
        assertTrue(UacDescriptors.parse(raw).isEmpty())
    }

    @Test
    fun `a class-specific descriptor is attributed to the interface it follows`() {
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x02, 0x01, 0x03)),
            interfaceDescriptor(number = 3, cls = 0x03, subclass = 0x00, protocol = 0x00),
            // Belongs to the HID interface above, so it must not land on interface 0.
            csInterface(subtype = 0x06, payload = byteArrayOf(0x0B, 0x01, 0x03)),
        )
        val control = UacDescriptors.parse(raw).single()
        assertEquals(listOf(2), control.declaredUnits)
    }

    @Test
    fun `two audio control interfaces are both returned`() {
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x02, 0x01, 0x03)),
            interfaceDescriptor(number = 4, cls = 0x01, subclass = 0x01, protocol = 0x00),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x09, 0x01, 0x03)),
        )
        val controls = UacDescriptors.parse(raw)
        assertEquals(2, controls.size)
        assertEquals(listOf(0, 4), controls.map { it.interfaceNumber })
        assertEquals(UacVolume.PROTOCOL_UAC1, controls[1].protocol)
    }

    @Test
    fun `unit id zero is not a unit`() {
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x00, 0x01, 0x03)),
        )
        assertTrue(UacDescriptors.parse(raw).single().declaredUnits.isEmpty())
    }

    @Test
    fun `a zero length byte ends the walk instead of looping on it`() {
        // The failure mode this guard exists for: bLength 0 advances the cursor by nothing.
        val raw = interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20) +
            byteArrayOf(0x00, 0x24, 0x06, 0x02)
        val control = UacDescriptors.parse(raw).single()
        assertTrue(control.declaredUnits.isEmpty())
    }

    @Test
    fun `a descriptor claiming to run past the end is dropped, not read`() {
        val raw = interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20) +
            byteArrayOf(0x40, 0x24, 0x06, 0x02)
        val control = UacDescriptors.parse(raw).single()
        assertTrue(control.declaredUnits.isEmpty())
    }

    @Test
    fun `nothing at all is not a crash`() {
        assertTrue(UacDescriptors.parse(null).isEmpty())
        assertTrue(UacDescriptors.parse(ByteArray(0)).isEmpty())
        assertTrue(UacDescriptors.parse(byteArrayOf(0x09)).isEmpty())
    }

    @Test
    fun `candidates never repeat`() {
        val raw = blob(
            interfaceDescriptor(number = 0, cls = 0x01, subclass = 0x01, protocol = 0x20),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x02, 0x01, 0x03)),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x02, 0x01, 0x03)),
            csInterface(subtype = 0x06, payload = byteArrayOf(0x05, 0x01, 0x03)),
        )
        val candidates = UacDescriptors.parse(raw).single().candidateUnits()
        assertEquals(candidates.size, candidates.toSet().size)
        assertEquals(listOf(2, 5), candidates.take(2))
    }

    private fun blob(vararg parts: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (part in parts) out += part
        return out
    }

    /** A standard 9-byte interface descriptor. */
    private fun interfaceDescriptor(
        number: Int,
        cls: Int,
        subclass: Int,
        protocol: Int,
    ): ByteArray = byteArrayOf(
        0x09, 0x04,
        number.toByte(), 0x00, 0x02,
        cls.toByte(), subclass.toByte(), protocol.toByte(),
        0x00,
    )

    /** A class-specific interface descriptor: length, 0x24, subtype, then payload. */
    private fun csInterface(subtype: Int, payload: ByteArray): ByteArray =
        byteArrayOf((3 + payload.size).toByte(), 0x24, subtype.toByte()) + payload
}
