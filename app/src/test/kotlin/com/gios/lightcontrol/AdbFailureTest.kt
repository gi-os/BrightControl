package com.gios.lightcontrol

import com.gios.lightcontrol.adb.AdbFailure
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed connect, kept as what libadb said rather than collapsed into `false`.
 *
 * The exceptions are built the way libadb 3.1.1 builds them, so a change in how a failure reads
 * shows up here before it shows up as a mislabeled report.
 */
class AdbFailureTest {

    /** libadb's own wrapping: `(IOException) new IOException().initCause(th)`. */
    private fun closedPort() = IOException().apply {
        initCause(
            ConnectException(
                "failed to connect to /192.168.10.220 (port 38217) from /192.168.10.220 " +
                    "(port 51234) after 0ms: isConnected failed: ECONNREFUSED (Connection refused)",
            ),
        )
    }

    @Test
    fun `describe keeps the exception and its cause, without the LAN address`() {
        val said = AdbFailure.describe(closedPort())
        assertTrue(said, said.startsWith("IOException <- ConnectException: failed to connect to /<lan> (port 38217)"))
        assertFalse(said, said.contains("192.168"))
    }

    @Test
    fun `describe stays one trail line`() {
        val said = AdbFailure.describe(IOException("x".repeat(1_000)))
        assertTrue(said.length <= 240)
    }

    @Test
    fun `discovery that found nothing reads as not found`() {
        val timedOut = InterruptedException("Timed out while trying to find a valid host address and port")
        val lost = IOException("Could not find any valid host address or port")
        assertEquals(AdbFailure.Kind.NotFound, AdbFailure.kind(timedOut))
        assertEquals(AdbFailure.Kind.NotFound, AdbFailure.kind(lost))
    }

    @Test
    fun `a port with nothing on it reads as closed`() {
        assertEquals(AdbFailure.Kind.Closed, AdbFailure.kind(closedPort()))
    }

    @Test
    fun `a handshake the phone refused reads as refused`() {
        // waitForConnection's shape for a refused certificate.
        val pairing = AdbPairingRequiredException("ADB pairing is required.").apply {
            initCause(SSLProtocolException("Read error: ssl=0x7b: Failure in SSL library, usually a protocol error"))
        }
        assertEquals(AdbFailure.Kind.Refused, AdbFailure.kind(pairing))
        assertEquals(AdbFailure.Kind.Refused, AdbFailure.kind(SSLHandshakeException("bad certificate")))
        assertEquals(AdbFailure.Kind.Refused, AdbFailure.kind(IOException("Connection failed")))
        assertEquals(AdbFailure.Kind.Refused, AdbFailure.kind(AdbAuthenticationFailedException()))
    }

    @Test
    fun `anything else is other`() {
        assertEquals(AdbFailure.Kind.Other, AdbFailure.kind(IllegalStateException("Already connected")))
    }

    @Test
    fun `scrub leaves clock times, ports and loopback alone`() {
        val line = "21:13:25  try 1: port 38217 — connected to /127.0.0.1 (port 38217)"
        assertEquals(line, AdbFailure.scrub(line))
    }

    @Test
    fun `scrub takes out Wi-Fi and IPv6 addresses`() {
        assertEquals("to /<lan>:5555", AdbFailure.scrub("to /192.168.1.24:5555"))
        assertEquals("host <lan> up", AdbFailure.scrub("host fe80::1c2b:3cff:fe4d:5e6f%wlan0 up"))
        assertEquals("host <lan> up", AdbFailure.scrub("host 2001:db8:85a3::8a2e:370:7334 up"))
    }

    @Test
    fun `scrub leaves ordinary words with colons alone`() {
        val line = "isConnected failed: ECONNREFUSED (Connection refused)"
        assertEquals(line, AdbFailure.scrub(line))
    }
}
