package com.gios.lightcontrol

import com.gios.lightcontrol.adb.AdbPairWalk
import com.gios.lightcontrol.adb.AdbPairWalk.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which way the reader walks, from each Settings screen it can be handed.
 *
 * Every screen here is a real one, flattened one node per line the way the reader flattens it, and
 * every case is a report. The two that matter most are the ones the old two-arm walk got wrong: a
 * long list with the row it wants below the fold, where it did nothing at all; and the Wireless
 * debugging screen in the same state, where it pressed the switch in the title and turned wireless
 * debugging off.
 */
class AdbPairWalkTest {

    /** Developer options as the app opens it, scrolled to the top. The row is four screens down. */
    private val developerOptionsTop = """
        Developer options
        Use developer options
        Memory
        Bug report
        Bug report shortcut
        Desktop backup password
        Stay awake
        Enable Bluetooth HCI snoop log
        OEM unlocking
    """.trimIndent()

    /** The same list, scrolled far enough that the row is laid out. */
    private val developerOptionsAtTheRow = """
        Developer options
        Debugging
        USB debugging
        Debug mode when USB is connected
        Revoke USB debugging authorizations
        Wireless debugging
        Debug mode when Wi-Fi is connected
        Bug report shortcut
    """.trimIndent()

    /** The Wireless debugging screen, top of the list. Both pairing rows are below the fold. */
    private val wirelessDebuggingTop = """
        Wireless debugging
        Use wireless debugging
        Device name
        Pixel
        IP address & Port
        192.168.1.24:38675
    """.trimIndent()

    /** The same screen, scrolled to the rows. */
    private val wirelessDebuggingAtTheRows = """
        Wireless debugging
        Use wireless debugging
        Pair new device
        Pair device with QR code
        Pair device with pairing code
        Paired devices
    """.trimIndent()

    /** The dialog. Reading it is [com.gios.lightcontrol.adb.AdbPairCode]'s job, not the walk's. */
    private val pairingDialog = """
        Pair with device
        Wi-Fi pairing code
        482913
        IP address & Port
        192.168.1.24:37419
        Cancel
    """.trimIndent()

    /** The QR screen, which carries no code and has nothing below the fold. */
    private val qrScreen = """
        Scan QR code
        Pair device over Wi-Fi by scanning a QR code
    """.trimIndent()

    @Test
    fun `developer options scrolls when the row is below the fold`() {
        // light-reports#302, #296, #290, #266, #255, #254 and #247 — every one of them
        // `windows seen while waiting: Developer options`, and nothing after it.
        assertEquals(Step.Scroll, AdbPairWalk.next(developerOptionsTop))
    }

    @Test
    fun `developer options taps the row once it is on screen`() {
        assertEquals(
            Step.Tap(AdbPairWalk.WIRELESS_ROW),
            AdbPairWalk.next(developerOptionsAtTheRow),
        )
    }

    @Test
    fun `the wireless debugging screen is never tapped by its title`() {
        // The bug this arm exists for: "Wireless debugging" is the title of this screen, the text
        // search matches the switch labeled "Use wireless debugging", the switch is clickable, and
        // the walk turned the feature off — light-reports#318, #314, #312, #310, #308, #303, #288,
        // #270, #265, #260, #258, #234, #229, every one of them "the pairing was accepted and mDNS
        // then found nothing to connect to".
        assertEquals(Step.Scroll, AdbPairWalk.next(wirelessDebuggingTop))
    }

    @Test
    fun `the pairing row is tapped wherever it appears`() {
        assertEquals(
            Step.Tap(AdbPairWalk.PAIRING_ROW),
            AdbPairWalk.next(wirelessDebuggingAtTheRows),
        )
    }

    @Test
    fun `the dialog is left alone`() {
        // Touching it can only dismiss it, and a dismissed dialog takes the pairing session down
        // with it.
        assertEquals(Step.Nothing, AdbPairWalk.next(pairingDialog))
    }

    @Test
    fun `the QR screen leads nowhere`() {
        assertEquals(Step.Nothing, AdbPairWalk.next(qrScreen))
    }

    @Test
    fun `an unrelated settings screen is left alone`() {
        // light-reports#299 and #317 are a user wandering: Storage, Apps storage, About phone,
        // Gestures, Navigation mode. None of them is on the way, and none of them gets touched.
        val aboutPhone = """
            About phone
            Device name
            Phone number
            Legal information
            Build number
            00WW_1_440000
        """.trimIndent()
        assertEquals(Step.Nothing, AdbPairWalk.next(aboutPhone))
    }

    @Test
    fun `a french phone is still not walked`() {
        // light-reports#259, and honest about it: the labels are English, so a phone set to French
        // gets no walk. Pinned so the day someone adds the strings, this test is what changes.
        val french = """
            Options pour les développeurs
            Débogage sans fil
            Infos sur l'appareil
        """.trimIndent()
        assertEquals(Step.Nothing, AdbPairWalk.next(french))
    }
}
