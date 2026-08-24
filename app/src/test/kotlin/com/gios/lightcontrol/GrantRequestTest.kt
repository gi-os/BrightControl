package com.gios.lightcontrol

import com.gios.lightcontrol.adb.GrantCheck
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

    // ------------------------------------------------------------------ start shizuku

    @Test
    fun `start shizuku is rebuilt here, not taken from the request`() {
        val parsed = GrantRequest.parse("com.gios.brighthotspot", listOf("start shizuku"))
        val steps = (parsed as GrantRequest.Parsed.Ok).steps
        assertEquals(1, steps.size)
        assertEquals("Start Shizuku", steps[0].label)
        assertEquals(
            "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            steps[0].command,
        )
    }

    @Test
    fun `the shizuku verb tolerates the spellings a readme would use`() {
        listOf("start shizuku", "Start Shizuku", "adb shell start shizuku", "run shizuku")
            .forEach { line ->
                assertTrue(
                    "refused: " + line,
                    GrantRequest.parse("com.gios.brighthotspot", listOf(line))
                        is GrantRequest.Parsed.Ok,
                )
            }
    }

    /**
     * The whole safety of this verb is that a requester may only *ask*. A line carrying a path, an
     * argument or a second command is not the verb and must not be treated as one — otherwise the
     * one entry on the allowlist that is not package-pinned becomes a way to run anything.
     */
    @Test
    fun `nothing may ride along with the shizuku verb`() {
        listOf(
            "start shizuku /sdcard/evil.sh",
            "start shizuku; rm -rf /sdcard",
            "start shizuku && id",
            "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            "start shizukuu",
            "startshizuku",
        ).forEach { line ->
            assertTrue(
                "accepted: " + line,
                GrantRequest.parse("com.gios.brighthotspot", listOf(line))
                    is GrantRequest.Parsed.Refused,
            )
        }
    }

    // ---------------------------------------------------------------- read-back checks

    /**
     * Every step carries the state to read back afterwards, and that state names the requesting
     * package. This is the same pinning the command itself gets: a check that could be pointed at
     * another package would let a refused request be reported as somebody else's granted one.
     */
    @Test
    fun `each kind of step carries a check pinned to the requester`() {
        assertEquals(
            GrantCheck.Permission(roll, "android.permission.WRITE_SECURE_SETTINGS"),
            ok(roll, "pm grant $roll android.permission.WRITE_SECURE_SETTINGS")
                .steps.single().check,
        )
        assertEquals(
            GrantCheck.AppOp(roll, "SYSTEM_ALERT_WINDOW", "allow"),
            ok(roll, "appops set $roll SYSTEM_ALERT_WINDOW allow").steps.single().check,
        )
        assertEquals(
            GrantCheck.SecureListHas("enabled_notification_listeners", "$roll/.Listener"),
            ok(roll, "cmd notification allow_listener $roll/.Listener").steps.single().check,
        )
        assertEquals(
            GrantCheck.SecureListHas("enabled_accessibility_services", "$roll/.KeyService"),
            ok(roll, "accessibility $roll/.KeyService").steps.single().check,
        )
    }

    /**
     * Starting Shizuku is the one step with nothing to read back, and it says so rather than
     * borrowing a check that would always pass. An unverifiable step reported as granted is worse
     * than one reported as unknown, because it is the version nobody goes back to look at.
     */
    @Test
    fun `starting Shizuku is honestly unverifiable`() {
        assertEquals(GrantCheck.None, ok(roll, "start shizuku").steps.single().check)
    }

    /**
     * `appops set` accepts modes that are not "allow", and the check has to read back the mode
     * that was actually asked for. A deny that reads back as allow is a failure reported as a
     * success, in the direction that matters.
     */
    @Test
    fun `an appop check reads back the mode that was asked for`() {
        assertEquals(
            GrantCheck.AppOp(roll, "SYSTEM_ALERT_WINDOW", "deny"),
            ok(roll, "appops set $roll SYSTEM_ALERT_WINDOW deny").steps.single().check,
        )
    }
}
