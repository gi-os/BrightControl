package com.gios.lightcontrol

import com.gios.lightcontrol.adb.GrantRequest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every ADB line the live index actually ships, run through the parser.
 *
 * These were read out of each app's README and put into the catalog by hand, so this is the
 * test that they survive the trip: a line that the parser refuses would reach a user as a button
 * that opens BrightControl only to say no. Copied here verbatim -- if the catalog gains an app
 * whose setup does not parse, that is a thing to find here rather than on a phone.
 */
class RealIndexGrantsTest {

    private val catalog = mapOf(
        "com.gios.brightway" to listOf(
            "adb shell pm grant com.gios.brightway android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.gios.lightcamera" to listOf(
            "adb shell pm grant com.gios.lightcamera android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.gios.lightchat" to listOf(
            "adb shell appops set com.gios.lightchat SYSTEM_ALERT_WINDOW allow",
            "adb shell pm grant com.gios.lightchat android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.gios.lightfog" to listOf(
            "adb shell pm grant com.gios.lightfog android.permission.ACCESS_BACKGROUND_LOCATION",
        ),
        "com.gios.lightnotebook" to listOf(
            "adb shell appops set com.gios.lightnotebook SYSTEM_ALERT_WINDOW allow",
        ),
        "com.gios.lightpass" to listOf(
            "adb shell pm grant com.gios.lightpass android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.gios.lightsports" to listOf(
            "adb shell appops set com.gios.lightsports SYSTEM_ALERT_WINDOW allow",
        ),
        "com.lightfastread" to listOf(
            "adb shell pm grant com.lightfastread android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.lightphone.spotify" to listOf(
            "adb shell appops set com.lightphone.spotify GET_USAGE_STATS allow",
            "adb shell appops set com.lightphone.spotify SYSTEM_ALERT_WINDOW allow",
            "adb shell pm grant com.lightphone.spotify android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.lightrss.reader" to listOf(
            "adb shell pm grant com.lightrss.reader android.permission.WRITE_SECURE_SETTINGS",
        ),
        "com.vandam.ritual" to listOf(
            "adb shell pm grant com.vandam.ritual android.permission.WRITE_SECURE_SETTINGS",
        ),
    )

    @Test
    fun `every line in the catalog parses`() {
        catalog.forEach { (pkg, lines) ->
            val parsed = GrantRequest.parse(pkg, lines)
            assertTrue(
                "$pkg refused: ${(parsed as? GrantRequest.Parsed.Refused)?.why}",
                parsed is GrantRequest.Parsed.Ok,
            )
            val steps = (parsed as GrantRequest.Parsed.Ok).steps
            assertTrue("$pkg produced no steps", steps.isNotEmpty())
            // Every rebuilt command names its own app and nothing else.
            steps.forEach { assertTrue("${it.command} escaped $pkg", it.command.contains(pkg)) }
        }
    }

    @Test
    fun `a trailing semicolon from a readme is tolerated`() {
        // BrightLibrary's README writes the line with one. It is stripped rather than refused,
        // and it cannot become command chaining: the parser matches the whole line or nothing.
        val parsed = GrantRequest.parse(
            "com.lightfastread",
            listOf("adb shell pm grant com.lightfastread android.permission.WRITE_SECURE_SETTINGS;"),
        )
        assertTrue(parsed is GrantRequest.Parsed.Ok)
    }
}
