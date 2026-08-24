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
     * Nothing to read back. Starting Shizuku is the only one: it brings up somebody else's
     * service, which then asks the user per app in its own UI, so there is no state here that
     * says whether it worked. Reported honestly as unknown rather than dressed up as success.
     */
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
    ): StepResult {
        val output = runCatching { adb.runCommand(command) }
            .getOrElse { e -> "!${e.message ?: e.javaClass.simpleName}" }
        val threw = output.startsWith("!")
        val said = output.removePrefix("!").trim()

        // Permission and appop state settles as the command returns, but the process-side caches
        // are invalidated asynchronously, and a check that runs in the same millisecond as the
        // write occasionally reads the old value. One retry costs a quarter second on the
        // failure path and nothing on the success path.
        var held = holds(context, adb, check)
        if (held == false) {
            Thread.sleep(RECHECK_MS)
            held = holds(context, adb, check)
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

        is GrantCheck.AppOp -> runCatching {
            val out = adb.runCommand("appops get ${check.pkg} ${check.op}")
            // `appops get` prints `OP: mode; time=...`, and an op that was never set prints
            // nothing at all. Match the mode word rather than the whole line.
            out.substringAfter(':', out).contains(check.mode, ignoreCase = true)
        }.getOrNull()

        is GrantCheck.SecureListHas -> runCatching {
            val cur = Settings.Secure.getString(context.contentResolver, check.key).orEmpty()
            val want = expand(check.component) ?: return@runCatching false
            cur.split(':').any { expand(it.trim()) == want }
        }.getOrNull()

        GrantCheck.None -> null
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
