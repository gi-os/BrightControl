package com.gios.lightcontrol.adb

/**
 * Another app asking BrightControl to run the ADB setup its README describes.
 *
 * ## The whole point: nothing that arrives here is ever executed
 *
 * BrightControl holds a shell. Handing it a command string from outside and running it would be
 * handing shell to whoever wrote that string — and the strings come from an app index anyone can
 * submit to. `pm install`, `settings put`, `pm grant` naming *someone else's* package: all of it
 * would work, and none of it is what the sender is supposed to be able to ask for.
 *
 * So the command is parsed, not run. Each line is matched against the small set of things an app
 * is allowed to need, and if it matches, the command that actually runs is **rebuilt here** from
 * the pieces, pinned to the requesting package. A line that does not match is refused with a
 * reason. The worst a malicious index entry can do is ask for a permission on itself, which is
 * exactly what installing an app already implies.
 *
 * Two invariants do most of the work:
 *
 *  1. **Every command names the requesting package and no other.** A request from `com.gios.roll`
 *     cannot touch `com.gios.lightcontrol` or anything else. This is what stops the obvious
 *     escalation, which is an app quietly granting privileges to a second app.
 *  2. **The dangerous shell is ours.** Enabling an accessibility service means a read-modify-write
 *     of a colon-joined secure setting, and a naive `settings put` silently switches off every
 *     other service on the phone. The request only says *which service*; the shell that appends
 *     rather than overwrites is written here, the same way [SelfGrant] does it for this app.
 */
object GrantRequest {

    /** One thing that will be run, with the words to show the user before it is. */
    data class Step(val label: String, val command: String)

    sealed interface Parsed {
        data class Ok(val steps: List<Step>) : Parsed

        /** One line could not be turned into something safe. Nothing runs. */
        data class Refused(val line: String, val why: String) : Parsed
    }

    /**
     * Turn the lines an app's README carries into steps this phone will run.
     *
     * All or nothing. A request with one unrecognised line is refused whole rather than partly
     * applied: a half-granted app is a worse thing to hand back than a clear refusal, and a line
     * nobody could parse is the case most likely to be someone trying something.
     */
    fun parse(pkg: String, lines: List<String>): Parsed {
        if (pkg.isBlank()) return Parsed.Refused("", "no package named")
        if (lines.isEmpty()) return Parsed.Refused("", "nothing to run")

        val steps = mutableListOf<Step>()
        for (raw in lines) {
            val line = normalize(raw)
            if (line.isBlank()) continue
            val step = step(pkg, line) ?: return Parsed.Refused(
                raw.trim(),
                refusalFor(pkg, line),
            )
            steps += step
        }
        if (steps.isEmpty()) return Parsed.Refused("", "nothing to run")
        return Parsed.Ok(steps.distinctBy { it.command })
    }

    /**
     * Strip the parts of a README line that are about running it from a computer.
     *
     * `adb shell pm grant …` and `pm grant …` are the same request; so is a line someone pasted
     * with a `$` prompt or wrapped in backticks. Being liberal here costs nothing, because what
     * comes out is still only matched against the allowlist below.
     */
    private fun normalize(raw: String): String {
        // Runs of whitespace collapse first. A line copied out of a README can carry `adb  shell`
        // with two spaces, and peeling prefixes before normalising the spacing meant that line
        // kept a stray `shell` on the front and was refused as unrecognised.
        var s = raw.trim().removeSurrounding("`").trim().replace(Regex("\\s+"), " ")
        while (true) {
            s = when {
                s == "$" -> return ""
                s.startsWith("$ ") -> s.removePrefix("$ ")
                s.startsWith("adb ") -> s.removePrefix("adb ")
                // No command below begins with `shell`, so peeling it is unambiguous.
                s.startsWith("shell ") -> s.removePrefix("shell ")
                else -> return s.trim().removeSuffix(";").trim()
            }.trim()
        }
    }

    private fun step(pkg: String, line: String): Step? {
        PM_GRANT.matchEntire(line)?.let { m ->
            val (target, permission) = m.destructured
            if (target != pkg) return null
            return Step(
                label = "Permission · ${permission.substringAfterLast('.')}",
                command = "pm grant $pkg $permission",
            )
        }
        APPOPS.matchEntire(line)?.let { m ->
            val (target, op, mode) = m.destructured
            if (target != pkg) return null
            if (mode.lowercase() !in APPOP_MODES) return null
            return Step(
                label = "App op · $op",
                command = "appops set $pkg $op ${mode.lowercase()}",
            )
        }
        NOTIFICATION_LISTENER.matchEntire(line)?.let { m ->
            val (target, cls) = m.destructured
            if (target != pkg) return null
            return Step(
                label = "Read notifications",
                command = "cmd notification allow_listener $pkg/$cls",
            )
        }
        ACCESSIBILITY.matchEntire(line)?.let { m ->
            val (target, cls) = m.destructured
            if (target != pkg) return null
            return Step(
                label = "Accessibility service",
                command = enableAccessibility("$pkg/$cls"),
            )
        }
        return null
    }

    /** Why a line was turned down, in words worth showing someone. */
    private fun refusalFor(pkg: String, line: String): String {
        val named = listOf(PM_GRANT, APPOPS, NOTIFICATION_LISTENER, ACCESSIBILITY)
            .firstNotNullOfOrNull { it.matchEntire(line)?.groupValues?.getOrNull(1) }
        return when {
            named != null && named != pkg ->
                "names $named, but this request is from $pkg — an app may only set up itself"
            else ->
                "not a permission, app op, notification listener or accessibility service"
        }
    }

    /**
     * Add one service to `enabled_accessibility_services` without dropping what is already there.
     *
     * `settings put` overwrites, and overwriting this particular value switches off every other
     * accessibility service on the phone — a password manager, LightVoice, this app's own key
     * service. Read and write in one `sh -c` because the daemon gives each command its own
     * process, so a value read in one would not see a write from the next.
     *
     * The same shell [SelfGrant] uses on itself, for the same reason.
     */
    private fun enableAccessibility(component: String): String =
        "sh -c '" +
            "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \":\$cur:\" in *:$component:*) echo already;; " +
            "*) if [ \"\$cur\" = null ] || [ -z \"\$cur\" ]; then " +
            "settings put secure enabled_accessibility_services $component; " +
            "else settings put secure enabled_accessibility_services \"\$cur:$component\"; fi; " +
            "settings put secure accessibility_enabled 1; echo done;; esac'"

    private val PKG = """[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+"""
    private val CLS = """[A-Za-z0-9_.$]+"""

    private val PM_GRANT = Regex("""pm grant ($PKG) ([A-Za-z0-9_.]+)""")
    private val APPOPS = Regex("""appops set ($PKG) ([A-Za-z0-9_]+) ([A-Za-z]+)""")
    private val NOTIFICATION_LISTENER =
        Regex("""cmd notification allow_listener ($PKG)/($CLS)""")

    /**
     * Matched as a *declaration*, not as the `settings put` a README would show. A README line
     * that overwrites the whole accessibility list is refused on purpose — the safe version of
     * that command is built above, and there is no reason to accept the unsafe spelling when the
     * only thing needed from it is which service to switch on.
     */
    private val ACCESSIBILITY =
        Regex("""(?:enable )?accessibility(?: service)? ($PKG)/($CLS)""", RegexOption.IGNORE_CASE)

    private val APPOP_MODES = setOf("allow", "deny", "ignore", "default")
}
