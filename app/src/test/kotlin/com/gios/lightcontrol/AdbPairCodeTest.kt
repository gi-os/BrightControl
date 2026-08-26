package com.gios.lightcontrol

import com.gios.lightcontrol.adb.AdbPairCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pulling the six digits out of a screenful of Settings text.
 *
 * This is the one piece of the pairing path that Light's fork of Settings can break without
 * anyone noticing, so it reads by shape rather than by view id — and the shapes are worth
 * pinning down. The dialog's own IP and port live in the same text and must never be mistaken
 * for the code, and the reader sees several unrelated Settings screens on the way to the dialog.
 */
class AdbPairCodeTest {

    /** The AOSP dialog, flattened one node per line the way the reader flattens it. */
    private val pairingDialog = """
        Pair with device
        Wi-Fi pairing code
        482913
        IP address & Port
        192.168.1.24:37419
        Cancel
    """.trimIndent()

    @Test
    fun `reads the code off the pairing dialog`() {
        assertEquals("482913", AdbPairCode.extract(pairingDialog))
    }

    @Test
    fun `never mistakes the port for the code`() {
        // A TCP port cannot exceed 65535, so it is at most five digits and can never satisfy a
        // six-digit match. This asserts the guarantee rather than the implementation.
        val noCode = """
            Wireless debugging
            IP address & Port
            192.168.1.24:37419
        """.trimIndent()
        assertNull(AdbPairCode.extract(noCode))
    }

    @Test
    fun `ignores ordinary settings screens on the way`() {
        // Six digits can appear anywhere — a build number, a MAC fragment. Without the pairing
        // shape around them they must not trigger a pairing attempt.
        val developerOptions = """
            Developer options
            Build number
            TP1A.220624.014
            Device ID
            884213
        """.trimIndent()
        assertFalse(AdbPairCode.looksLikePairingDialog(developerOptions))
    }

    @Test
    fun `a standalone six-digit line wins over a looser match`() {
        // If the fork ever renders the code inside a sentence as well as on its own, the node
        // that is exactly the code is the one to trust.
        val doubled = """
            Pair with device
            Enter 482913 on the other device
            482913
            192.168.1.24:37419
        """.trimIndent()
        assertEquals("482913", AdbPairCode.extract(doubled))
    }

    @Test
    fun `falls back to a loose scan when the code shares a line`() {
        // A reskin that puts the label and the digits in one TextView still has to show them.
        val inline = """
            Pair with device
            Wi-Fi pairing code: 482913
            IP address & Port 192.168.1.24:37419
        """.trimIndent()
        assertEquals("482913", AdbPairCode.extract(inline))
    }

    @Test
    fun `recognises the dialog only when both the wording and an address are present`() {
        assertTrue(AdbPairCode.looksLikePairingDialog(pairingDialog))
        assertFalse(AdbPairCode.looksLikePairingDialog("Pair with device\nCancel"))
        assertFalse(AdbPairCode.looksLikePairingDialog("192.168.1.24:37419"))
    }

    // ---- light-reports#61: the box was found and the digits were not -------------

    @Test
    fun `a grouped code is read`() {
        // The report said "pairing box present but numbers within not detected", which can only
        // mean the digits were on that screen in a shape the old single pass did not admit.
        assertEquals(
            "123456",
            AdbPairCode.extract("Wi-Fi pairing code\n123 456\n192.168.1.10:37103"),
        )
        assertEquals(
            "123456",
            AdbPairCode.extract("Wi-Fi pairing code\n123\u00a0456\n192.168.1.10:37103"),
        )
        assertEquals(
            "123456",
            AdbPairCode.extract("Pair device with pairing code\n123-456\n192.168.1.10:37103"),
        )
    }

    @Test
    fun `a code split one digit per view is read`() {
        assertEquals(
            "123456",
            AdbPairCode.extract("Pair with device\ncode\n1\n2\n3\n4\n5\n6\n192.168.1.10:37103"),
        )
    }

    @Test
    fun `a code beside its label is read, grouped or not`() {
        assertEquals(
            "987654",
            AdbPairCode.extract("Pair device\nWi-Fi pairing code: 987654\n192.168.1.10:37103"),
        )
        assertEquals(
            "987654",
            AdbPairCode.extract("Pairing code 98 76 54\n192.168.1.10:37103"),
        )
    }

    @Test
    fun `the dialog is recognised without an address in the same window`() {
        // A reskin that puts the address in a different view would have left the old test
        // returning false about the very screen it exists to recognise.
        assertTrue(AdbPairCode.looksLikePairingDialog("Pair device with pairing code"))
        assertEquals("123456", AdbPairCode.extract("Pair device with pairing code\n123 456"))
    }

    @Test
    fun `an address is never read as a code`() {
        // The character-wise version of the labelled pass read `192168` out of the address on the
        // line below the label, which then fails against the daemon for a reason nothing on the
        // screen could explain.
        assertNull(AdbPairCode.extract("Pair device with pairing code\n192.168.1.10:37103"))
        assertNull(AdbPairCode.extract("Wireless debugging\nIP address & Port\n192.168.68.59:5555"))
    }

    @Test
    fun `a code is six digits, not five and not seven`() {
        assertNull(AdbPairCode.extract("Pair device with pairing code\n12345\n192.168.1.10:37103"))
        assertNull(AdbPairCode.extract("Pair device with pairing code\n1234567\n192.168.1.10:37103"))
    }

}
