package com.gios.lightcontrol

import com.gios.lightcontrol.portal.PortalRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address decisions behind light-reports #286/#287, checked without a phone.
 */
class PortalRouteTest {

    @Test
    fun `the system's url is used when it is one`() {
        assertEquals(
            "http://portal.hotel.example/welcome",
            PortalRoute.startUrl("http://portal.hotel.example/welcome"),
        )
        assertEquals("https://1.2.3.4/login", PortalRoute.startUrl("https://1.2.3.4/login"))
    }

    @Test
    fun `anything that is not http falls back to the probe url`() {
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl(null))
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl(""))
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl("   "))
        // An exported activity's extra is not a place to accept these from.
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl("javascript:alert(1)"))
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl("file:///etc/hosts"))
        assertEquals(PortalRoute.PROBE_URL, PortalRoute.startUrl("http://a b/c"))
    }

    @Test
    fun `the dhcp server wins, then the gateway, then a private resolver`() {
        assertEquals(
            "http://192.168.44.1/",
            PortalRoute.gatewayUrl("192.168.44.1", "192.168.44.254", listOf("192.168.44.2")),
        )
        assertEquals(
            "http://192.168.44.254/",
            PortalRoute.gatewayUrl(null, "192.168.44.254", listOf("192.168.44.2")),
        )
        // Both reports had the resolver and nothing else: dns=192.168.44.1, dns=172.20.96.1.
        assertEquals("http://192.168.44.1/", PortalRoute.gatewayUrl(null, null, listOf("192.168.44.1")))
        assertEquals("http://172.20.96.1/", PortalRoute.gatewayUrl(null, null, listOf("172.20.96.1")))
    }

    @Test
    fun `a public resolver is not a login page`() {
        assertNull(PortalRoute.gatewayUrl(null, null, listOf("8.8.8.8", "1.1.1.1")))
        assertNull(PortalRoute.gatewayUrl(null, null, listOf("139.7.30.125", "2a01:860:0:300::53")))
        assertNull(PortalRoute.gatewayUrl(null, null, emptyList()))
        assertNull(PortalRoute.gatewayUrl("0.0.0.0", null, emptyList()))
    }

    @Test
    fun `InetAddress toString leaves a slash on the front`() {
        assertEquals("http://192.168.1.1/", PortalRoute.gatewayUrl("/192.168.1.1", null, emptyList()))
    }

    @Test
    fun `an ipv6 gateway is bracketed`() {
        assertEquals("http://[fe80::1]/", PortalRoute.gatewayUrl(null, "fe80::1", emptyList()))
    }

    @Test
    fun `172 is only private in the middle sixteen`() {
        assertTrue(PortalRoute.isPrivate("172.16.0.1"))
        assertTrue(PortalRoute.isPrivate("172.20.96.1"))
        assertTrue(PortalRoute.isPrivate("172.31.255.254"))
        assertFalse(PortalRoute.isPrivate("172.15.0.1"))
        assertFalse(PortalRoute.isPrivate("172.32.0.1"))
        assertFalse(PortalRoute.isPrivate("172.web.0.1"))
    }

    @Test
    fun `dns failures are told apart from a dead network`() {
        assertTrue(
            PortalRoute.isDnsThrow(
                "UnknownHostException",
                "Unable to resolve host \"connectivitycheck.gstatic.com\": No address associated with hostname",
            ),
        )
        assertTrue(PortalRoute.isDnsThrow(null, "Unable to resolve host \"x\""))
        assertFalse(PortalRoute.isDnsThrow("SocketException", "EPERM (Operation not permitted)"))
        assertFalse(PortalRoute.isDnsThrow("SocketTimeoutException", "timeout"))
        assertFalse(PortalRoute.isDnsThrow(null, null))
    }
}
