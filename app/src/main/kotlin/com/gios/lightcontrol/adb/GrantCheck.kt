package com.gios.lightcontrol.adb

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Whether a grant actually landed, asked of the phone rather than of the command that ran.
 *
 * ## Why the output of the command is not the answer
 *
 * The adb `shell:` service is the legacy protocol: it merges stdout and stderr into one stream and
 * carries **no exit status**. So the only thing a caller can do with the result is look at the
 * text, and both screens here did the same thing with it — blank meant "ok". That reading is wrong
 * in the two cases that matter most:
 *
 *  - A command that fails quietly prints nothing, and was reported as ok.
 *  - A command that never ran at all — the socket died, every later call threw `Stream closed` —
 *    produced a run where the button still ended on DONE, because reaching the end of the list was
 *    treated as success. Six failures in a row looked like six results.
 *
 * A grant is a state, not an event, so it can simply be read back. That is the same move
 * [com.gios.lightcontrol.keys.ColorMode] makes after writing the daltonizer, and the same one
 * [com.gios.lightcontrol.keys.Grants] makes by asking `AccessibilityManager` instead of
 * string-matching a setting: state the intent, then ask the phone what is true.
 *
 * Each check is cheap, and the expensive ones do not exist — a permission is a `PackageManager`
 * call with no shell involved, which means it is still answerable after the connection has died.
 * That is deliberate: the run that most needs verifying is the run where adb stopped working.
 */
sealed interface GrantCheck {

    /** A runtime or signature permission on a package. */
    data class Permission(val pkg: String, val permission: String) : GrantCheck

    /** An app op, read back through `appops get` and matched on the mode word. */
    data class AppOp(val pkg: String, val op: String, val mode: String) : GrantCheck

    /**
     * A colon-joined secure setting that should contain a component —
     * `enabled_accessibility_services`, `enabled_notification_listeners`.
     */
    data class SecureListHas(val key: String, val component: String) : GrantCheck

    /**
     * Notification policy access — "Do Not Disturb access", the thing that lets an app mute the
     * phone. Not a permission and not an app op: a list of packages the notification service keeps,
     * and the framework answers for our own package without a shell.
     */
    data class PolicyAccess(val pkg: String) : GrantCheck

    /**
     * Nothing to read back. Starting Shizuku is the only one: it brings up somebody else's
     * service, which then asks the user per app in its own UI, so there is no state here that
     * says whether it worked. Reported honestly as unknown rather than dressed up as success.
     */
    /**
     * A grant the platform keeps nowhere this app can read — only the shell can ask. The command
     * is run and its output is looked at for a word; `cmd wifi network-suggestions-has-user-approved`
     * answers `yes`/`no`, which is the case this exists for.
     */
    data class ShellSays(val command: String, val expect: String) : GrantCheck

    data object None : GrantCheck
}

/** What a step turned out to be. Three states, because "we could not tell" is a real answer. */
enum class Outcome {
    /** Read back and confirmed. */
    Held,

    /** Read back and it is not there. The command did not do what it said. */
    Failed,

    /** Nothing to read back, or the read itself failed. Not a success. */
    Unknown,
}

/** One step, run and then checked. [detail] is for the user, so it says the useful half. */
data class StepResult(
    val label: String,
    val outcome: Outcome,
    val detail: String,
)

object GrantCheckRunner {

    /**
     * How long a read-back may take. Much shorter than a command's own deadline: a check that has
     * to be waited on is a check that is not going to answer.
     */
    private const val CHECK_MS = 8_000L

    /**
     * Run one command and then find out whether it worked.
     *
     * The command's own output is kept only as detail. It is genuinely useful when it is
     * non-empty — `Operation not allowed`, `Unknown package`, `Stream closed` all name the cause —
     * but it never decides the outcome. The read-back does.
     */
    fun runAndVerify(
        context: Context,
        adb: AdbManager,
        label: String,
        command: String,
        check: GrantCheck,
        /** How long this one command may take before the socket is closed under it. */
        timeoutMs: Long = AdbManager.COMMAND_MS,
        /** Told each line the command prints while it is still running. */
        onLine: (String) -> Unit = {},
    ): StepResult {
        // Through [AdbManager.runVia], which owns both of the things this used to do by hand: one
        // reconnect-and-retry for a socket that died while nobody was looking, and — the part that
        // was missing — a **deadline**. `runCommand` reads until EOF, and a stream that stalls
        // rather than closing never gets there, so the read blocked forever: three grants into a
        // batch the buttons greyed and nothing else was ever printed, because a step's line is
        // written after its command returns. It never returned.
        val output = AdbManager.runVia(context, command, timeoutMs, onLine)
        val threw = output.startsWith("error:")
        val said = output.removePrefix("error:").trim()

        // Permission and appop state settles as the command returns, but the process-side caches
        // are invalidated asynchronously, and a check that runs in the same millisecond as the
        // write occasionally reads the old value. One retry costs a quarter second on the
        // failure path and nothing on the success path.
        // Fetched again rather than reusing the parameter: a reconnect above replaces the cached
        // manager, and checking through the one that was handed in would put the question to the
        // socket we just gave up on.
        val live = AdbManager.getInstance(context)
        var held = holds(context, live, check)
        if (held == false) {
            Thread.sleep(RECHECK_MS)
            held = holds(context, AdbManager.getInstance(context), check)
        }

        return when (held) {
            true -> StepResult(label, Outcome.Held, "granted")
            false -> StepResult(
                label = label,
                outcome = Outcome.Failed,
                detail = when {
                    said.isNotBlank() -> said.take(160)
                    threw -> "the command did not run"
                    else -> "the command ran and the grant is still not there"
                },
            )
            null -> StepResult(
                label = label,
                outcome = Outcome.Unknown,
                detail = when {
                    threw -> said.take(160).ifBlank { "the command did not run" }
                    said.isNotBlank() -> said.take(160)
                    else -> "nothing to read back — cannot confirm"
                },
            )
        }
    }

    /** True, false, or null when the phone could not be asked. */
    fun holds(context: Context, adb: AdbManager, check: GrantCheck): Boolean? = when (check) {
        // No shell involved, so this still answers after the connection has died — which is
        // exactly the run that needs an answer.
        is GrantCheck.Permission -> runCatching {
            context.packageManager.checkPermission(check.permission, check.pkg) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrNull()

        // Our own two app ops have framework answers, and a framework answer cannot be lost to a
        // dropped stream. This is the whole reason Brightness and Overlay came back UNKNOWN while
        // the four grants after them read OK: those four are checked through PackageManager and
        // Settings.Secure and never touch adb, and these two were the only checks still asking the
        // shell a question the shell had already stopped answering.
        is GrantCheck.AppOp -> ownAppOp(context, check)
            ?: runCatching {
                // Bounded like everything else. A check is not worth a frozen screen, and this one
                // asks the shell a question at the exact moment the shell has most likely stopped
                // answering — right after a grant that failed.
                val out = AdbManager.runVia(
                    context,
                    "appops get ${check.pkg} ${check.op}",
                    CHECK_MS,
                )
                // `appops get` prints `OP: mode; time=...`, and an op that was never set prints
                // nothing at all. Match the mode word rather than the whole line.
                out.substringAfter(':', out).contains(check.mode, ignoreCase = true)
            }.getOrNull()

        // Framework-answered, like the two app ops below, and for the same reason: a check that
        // needs the shell is a check that fails exactly when the grant did.
        is GrantCheck.PolicyAccess -> if (check.pkg != context.packageName) {
            null
        } else {
            runCatching {
                context.getSystemService(android.app.NotificationManager::class.java)
                    ?.isNotificationPolicyAccessGranted
            }.getOrNull()
        }

        is GrantCheck.SecureListHas -> runCatching {
            val cur = Settings.Secure.getString(context.contentResolver, check.key).orEmpty()
            val want = expand(check.component) ?: return@runCatching false
            cur.split(':').any { expand(it.trim()) == want }
        }.getOrNull()

        is GrantCheck.ShellSays -> runCatching {
            adb.runCommand(check.command).contains(check.expect, ignoreCase = true)
        }.getOrNull()
        GrantCheck.None -> null
    }

    /**
     * The two app ops this app grants itself, answered by the framework instead of by adb.
     *
     * `Settings.System.canWrite` and `Settings.canDrawOverlays` are the same question `appops get`
     * asks, put to the system directly — no shell, no stream, and no way for the answer to be
     * lost because the connection went away between the write and the read. Only valid for our
     * own package and only for `allow`: both APIs answer "can this app do it", which is not the
     * same question as "is the op set to deny rather than ignore".
     *
     * Null for anything else, which sends the caller back to `appops get` — the right route for
     * another app's ops, because there is no framework call that asks on someone else's behalf.
     */
    private fun ownAppOp(context: Context, check: GrantCheck.AppOp): Boolean? {
        if (check.pkg != context.packageName) return null
        if (!check.mode.equals("allow", ignoreCase = true)) return null
        return when (check.op) {
            "WRITE_SETTINGS" -> runCatching { Settings.System.canWrite(context) }.getOrNull()
            "SYSTEM_ALERT_WINDOW" -> runCatching { Settings.canDrawOverlays(context) }.getOrNull()
            else -> null
        }
    }

    /**
     * `pkg/.Class` and `pkg/pkg.Class` name the same component.
     *
     * The short form is what a README writes and what `settings put` stores, the long form is what
     * a caller builds from a class reference, and comparing the two as text reports a service that
     * is running as missing. The same expansion [com.gios.lightcontrol.keys.Grants] does, kept
     * here so the check does not depend on a screen.
     */
    private fun expand(entry: String): ComponentName? {
        val parsed = ComponentName.unflattenFromString(entry) ?: return null
        val cls = parsed.className
        return when {
            cls.startsWith(".") -> ComponentName(parsed.packageName, parsed.packageName + cls)
            !cls.contains('.') -> ComponentName(parsed.packageName, "${parsed.packageName}.$cls")
            else -> parsed
        }
    }

    /** How long to wait before asking a second time. Past the cache invalidation, under a blink. */
    private const val RECHECK_MS = 250L
}
