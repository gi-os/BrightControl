package com.gios.lightcontrol

import com.gios.lightcontrol.adb.GrantRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What another app is allowed to ask this phone's shell to do on its behalf.
 *
 * These are the security tests. BrightControl holds a live ADB connection, and the requests
 * arrive from an app index anyone can submit to, so the interesting cases are all the ones that
 * must be turned down.
 */
class GrantRequestTest {

    private val roll = "com.gios.roll"

    private fun ok(pkg: String, vararg lines: String) =
        GrantRequest.parse(pkg, lines.toList()) as GrantRequest.Parsed.Ok

    private fun refused(pkg: String, vararg lines: String) =
        GrantRequest.parse(pkg, lines.toList()) as GrantRequest.Parsed.Refused

    // ---- the shapes a README actually carries -------------------------------

    @Test
    fun `a readme line is accepted however it was pasted`() {
        val forms = listOf(
            "adb shell pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS",
            "pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS",
            "$ adb shell pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS",
            "`adb shell pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS`",
            "adb  shell   pm grant com.gios.roll   android.permission.WRITE_SECURE_SETTINGS",
        )
        forms.forEach { line ->
            val parsed = ok(roll, line)
            assertEquals(
                "pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS",
                parsed.steps.single().command,
            )
        }
    }

    @Test
    fun `app ops and notification listeners are understood`() {
        assertEquals(
            "appops set com.gios.roll SYSTEM_ALERT_WINDOW allow",
            ok(roll, "adb shell appops set com.gios.roll SYSTEM_ALERT_WINDOW allow")
                .steps.single().command,
        )
        assertEquals(
            "cmd notification allow_listener com.gios.roll/.Listener",
            ok(roll, "adb shell cmd notification allow_listener com.gios.roll/.Listener")
                .steps.single().command,
        )
    }

    // ---- the escalation this exists to stop ---------------------------------

    @Test
    fun `an app cannot grant anything to another package`() {
        // The whole reason nothing is executed as written: an index entry for one app quietly
        // handing privileges to a second one.
        val r = refused(roll, "pm grant com.gios.lightcontrol android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(r.why, r.why.contains("may only set up itself"))
    }

    @Test
    fun `arbitrary shell is refused`() {
        listOf(
            "pm install /sdcard/evil.apk",
            "settings put global adb_enabled 0",
            "rm -rf /sdcard",
            "pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS; pm install x",
            "sh -c 'echo hi'",
            "am start -n com.gios.roll/.Main",
        ).forEach { line ->
            val r = GrantRequest.parse(roll, listOf(line))
            assertTrue("should have refused: $line", r is GrantRequest.Parsed.Refused)
        }
    }

    @Test
    fun `one bad line refuses the whole request`() {
        // A half-applied setup is worse to hand back than a clear no.
        val r = refused(
            roll,
            "pm grant com.gios.roll android.permission.WRITE_SECURE_SETTINGS",
            "pm install /sdcard/evil.apk",
        )
        assertEquals("pm install /sdcard/evil.apk", r.line)
    }

    // ---- the dangerous one we rebuild ourselves -----------------------------

    @Test
    fun `enabling an accessibility service appends rather than overwrites`() {
        val command = ok(roll, "accessibility com.gios.roll/.KeyService").steps.single().command
        // The failure this prevents: switching off every other accessibility service on the
        // phone, silently, because `settings put` writes the whole colon-joined value.
        assertTrue(command, command.contains("settings get secure enabled_accessibility_services"))
        assertTrue(command, command.contains("\$cur:com.gios.roll/.KeyService"))
    }

    @Test
    fun `a raw settings put over the accessibility list is refused`() {
        // The unsafe spelling of the line above. There is no reason to accept it when the only
        // thing needed is which service to switch on.
        val r = refused(
            roll,
            "settings put secure enabled_accessibility_services com.gios.roll/.KeyService",
        )
        assertTrue(r.why, r.why.isNotBlank())
    }

    // ---- housekeeping -------------------------------------------------------

    @Test
    fun `duplicate lines run once`() {
        val parsed = ok(
            roll,
            "pm grant com.gios.roll android.permission.CAMERA",
            "adb shell pm grant com.gios.roll android.permission.CAMERA",
        )
        assertEquals(1, parsed.steps.size)
    }

    @Test
    fun `an empty request is refused rather than silently succeeding`() {
        assertTrue(GrantRequest.parse(roll, emptyList()) is GrantRequest.Parsed.Refused)
        assertTrue(GrantRequest.parse(roll, listOf("   ")) is GrantRequest.Parsed.Refused)
        assertTrue(GrantRequest.parse("", listOf("pm grant x.y z")) is GrantRequest.Parsed.Refused)
    }
}
