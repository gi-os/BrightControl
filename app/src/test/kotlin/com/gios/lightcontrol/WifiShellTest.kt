package com.gios.lightcontrol

import com.gios.lightcontrol.wifi.WifiShell
import com.gios.lightcontrol.wifi.WifiShell.Security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `cmd wifi` prints padded columns, and the only column that can hold a space is the SSID. The
 * parsers therefore anchor on the fields around it. Sample lines are the shape of Android 14's
 * `WifiShellCommand` output, header included.
 */
class WifiShellTest {

    private val scan = """
        BSSID                           Frequency  RSSI  Age(sec)  SSID                            Flags
        a4:2b:8c:11:22:33               2437  -48   1.203   DFS Guest                       [ESS]
        a4:2b:8c:11:22:34               5180  -61   1.203   DFS Guest                       [ESS]
        10:20:30:40:50:60               5220  -70   0.900   BasilNet 5G                     [WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]
        10:20:30:40:50:61               2412  -40   0.900                                   [WPA2-PSK-CCMP][ESS]
        de:ad:be:ef:00:01               5745  -55   2.100   Corp                            [WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]
        de:ad:be:ef:00:02               5745  -58   2.100   Cafe Enhanced                   [RSN-OWE-CCMP][ESS]
        de:ad:be:ef:00:03               5745  -59   2.100   Modern                          [RSN-SAE-CCMP][ESS]
    """.trimIndent()

    @Test
    fun foldsAccessPointsToTheStrongestPerSsidAndDropsHidden() {
        val seen = WifiShell.parseScan(scan)
        assertEquals(listOf("DFS Guest", "Corp", "Cafe Enhanced", "Modern", "BasilNet 5G"), seen.map { it.ssid })
        val dfs = seen.first()
        assertEquals(-48, dfs.rssi)
        assertEquals(2437, dfs.frequency)
        assertEquals("a4:2b:8c:11:22:33", dfs.bssid)
        assertEquals("[ESS]", dfs.flags)
    }

    @Test
    fun securityIsReadFromTheFlags() {
        val by = WifiShell.parseScan(scan).associateBy { it.ssid }
        assertEquals(Security.Open, by["DFS Guest"]!!.security)
        assertEquals(Security.Wpa2, by["BasilNet 5G"]!!.security)
        assertEquals(Security.Enterprise, by["Corp"]!!.security)
        assertEquals(Security.Owe, by["Cafe Enhanced"]!!.security)
        assertEquals(Security.Wpa3, by["Modern"]!!.security)
    }

    @Test
    fun transitionApJoinsAsWpa2() {
        assertEquals(Security.Wpa2, Security.of("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]"))
    }

    @Test
    fun connectCommandQuotesForSh() {
        assertEquals("cmd wifi connect-network 'DFS Guest' open", WifiShell.connectCommand("DFS Guest", Security.Open, null))
        assertEquals(
            "cmd wifi connect-network 'Gio'\"'\"'s Net' wpa2 'pa ss\$word'",
            WifiShell.connectCommand("Gio's Net", Security.Wpa2, "pa ss\$word"),
        )
        assertNull(WifiShell.connectCommand("Corp", Security.Enterprise, null))
        assertNull(WifiShell.connectCommand("Home", Security.Wpa2, null))
    }

    @Test
    fun savedNetworks() {
        val out = """
            Network Id      SSID                         Security type
            0               "BasilNet"                   wpa2-psk
            3               DFS Guest                    open
        """.trimIndent()
        val saved = WifiShell.parseSaved(out)
        assertEquals(2, saved.size)
        assertEquals(WifiShell.Saved(0, "BasilNet", "wpa2-psk"), saved[0])
        assertEquals(WifiShell.Saved(3, "DFS Guest", "open"), saved[1])
        assertEquals(0, WifiShell.parseSaved("No networks").size)
    }

    @Test
    fun status() {
        assertEquals(WifiShell.Status(true, "DFS Guest"), WifiShell.parseStatus("Wifi is enabled\nWifi is connected to \"DFS Guest\"\nWifiInfo: SSID: \"DFS Guest\", BSSID: ..."))
        assertEquals(WifiShell.Status(true, null), WifiShell.parseStatus("Wifi is enabled\nWifi is not connected"))
        assertEquals(WifiShell.Status(false, null), WifiShell.parseStatus("Wifi is disabled"))
    }
}
