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

    /**
     * One thing that will be run, with the words to show the user before it is and the state to
     * read back after it. [check] is built here rather than derived from [command] later, because
     * here is where the package is already pinned — a check inferred from the string afterwards
     * would be a second parser of the same untrusted line, and the two could disagree.
     */
    data class Step(
        val label: String,
        val command: String,
        val check: GrantCheck,
        /**
         * True for the one shape that is not about the requester: a repair of a named system app.
         * The screen says so in its own words rather than inferring it from the command string.
         */
        val foreign: Boolean = false,
        /**
         * How long this command may take before the socket is closed under it. Carried per step
         * because one of them — answering a pairing request — is *supposed* to sit there for half
         * a minute, and the deadline that keeps a stalled `pm grant` from freezing the screen would
         * otherwise cut it off doing its job.
         */
        val timeoutMs: Long = AdbManager.COMMAND_MS,
    )

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
    fun parse(
        pkg: String,
        lines: List<String>,
        /**
         * Where the requesting package's own APK lives, asked of the phone rather than stated in
         * the request. Only [confirmPairing] needs it, and it needs it for the property that makes
         * that verb safe: the code that runs is the requester's own installed code, at a path this
         * phone resolved, never a path anybody sent.
         */
        apkOf: (String) -> String? = { null },
    ): Parsed {
        if (pkg.isBlank()) return Parsed.Refused("", "no package named")
        if (lines.isEmpty()) return Parsed.Refused("", "nothing to run")

        val steps = mutableListOf<Step>()
        for (raw in lines) {
            val line = normalize(raw)
            if (line.isBlank()) continue
            val made = repair(line)
                ?: confirmPairing(pkg, line, apkOf)?.let(::listOf)
                ?: step(pkg, line)?.let(::listOf)
                ?: return Parsed.Refused(raw.trim(), refusalFor(pkg, line))
            steps += made
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
                check = GrantCheck.Permission(pkg, permission),
            )
        }
        APPOPS.matchEntire(line)?.let { m ->
            val (target, op, mode) = m.destructured
            if (target != pkg) return null
            if (mode.lowercase() !in APPOP_MODES) return null
            return Step(
                label = "App op · $op",
                command = "appops set $pkg $op ${mode.lowercase()}",
                check = GrantCheck.AppOp(pkg, op, mode.lowercase()),
            )
        }
        NOTIFICATION_LISTENER.matchEntire(line)?.let { m ->
            val (target, cls) = m.destructured
            if (target != pkg) return null
            return Step(
                label = "Read notifications",
                command = "cmd notification allow_listener $pkg/$cls",
                check = GrantCheck.SecureListHas("enabled_notification_listeners", "$pkg/$cls"),
            )
        }
        ACCESSIBILITY.matchEntire(line)?.let { m ->
            val (target, cls) = m.destructured
            if (target != pkg) return null
            return Step(
                label = "Accessibility service",
                command = enableAccessibility("$pkg/$cls"),
                check = GrantCheck.SecureListHas("enabled_accessibility_services", "$pkg/$cls"),
            )
        }
        if (SHIZUKU.matches(line)) {
            // Nothing to read back: Shizuku asks the user per app in its own UI, so the phone
            // holds no state that says this worked. Reported as unknown, never as done.
            return Step(label = "Start Shizuku", command = START_SHIZUKU, check = GrantCheck.None)
        }
        return null
    }

    /**
     * Answer a Bluetooth pairing request on behalf of an app that cannot answer one itself.
     *
     * ### Why this is worth a verb
     *
     * On this phone no app can pair anything that asks for consent. The request becomes
     * `com.android.settings/.bluetooth.BluetoothPairingDialog`, LightOS's pairing fragment builds a
     * **null** dialog for that variant, and Settings dies in `DialogFragment.prepareDialog`. With
     * the screen off the request is posted as a notification instead — whose Pair button fires
     * `ACTION_PAIRING_DIALOG`, which starts the same activity and takes the pairing service down
     * with it. Three routes, one dead end. An Oura ring cannot be paired; neither could the iPad
     * this app's own hotspot trigger was waiting for.
     *
     * `BluetoothDevice.setPairingConfirmation` answers with no UI at all. It needs
     * `BLUETOOTH_PRIVILEGED` — `signature|privileged`, so ungrantable to anything sideloaded — and
     * **`com.android.shell` holds it**. This app holds a shell. That is the whole idea.
     *
     * ### Why it is not a hole
     *
     * The line may carry one thing: a MAC address, matched by shape. Everything else is written
     * here.
     *
     *  - **The code that runs is the requester's own.** `CLASSPATH` is the APK path this phone
     *    resolved for the requesting package, and the class is `<that package>.helper.Confirm`. An
     *    app can therefore only run code it already shipped and the user already installed — code
     *    it could run anyway, in its own process. What it gains is the uid, for as long as one
     *    command takes.
     *  - **Nothing composes.** No path, no flag, no second command, no shell metacharacter: the
     *    pattern admits hex and colons, and the rest of the line is a constant.
     *  - **It still asks.** The consent screen shows the built line, and the bond is one the user
     *    was already trying to make — this replaces a dialog they cannot answer, not one they never
     *    saw.
     *
     * A request from a package with no resolvable APK is refused rather than guessed at.
     */
    private fun confirmPairing(pkg: String, line: String, apkOf: (String) -> String?): Step? {
        val mac = CONFIRM_PAIRING.matchEntire(line)?.groupValues?.get(1)?.uppercase() ?: return null
        val apk = apkOf(pkg)?.takeIf { it.startsWith("/") && !it.contains(' ') && "'" !in it }
            ?: return null
        return Step(
            label = "Answer the pairing request · $mac",
            // Wrapped in `sh -c` because a leading `VAR=value` is shell syntax, and the daemon
            // gives each command a process rather than a login shell.
            command = "sh -c 'CLASSPATH=$apk app_process / $pkg.helper.Confirm $mac $CONFIRM_MS'",
            check = GrantCheck.None,
            timeoutMs = AdbManager.SLOW_COMMAND_MS,
        )
    }

    /**
     * Put a broken **system** app back on its feet. The second thing here that is not about the
     * requesting package, and the more dangerous shape of the two, so it is the more tightly drawn.
     *
     * ### Why this exists
     *
     * A phone can reach a state where the request an app needs cannot be answered at all. The one
     * that forced this: on LightOS the Settings app crashes on the Bluetooth pairing screen, so
     * *nothing* can be paired — not a ring, not a tablet for this app's own hotspot trigger. The
     * fix is `pm clear com.android.settings`, which is a thing a shell can do in a second and a
     * thing a user with no computer cannot do at all. Refusing it left the phone broken and the
     * user with no route at all, which is not a safer outcome, only a quieter one.
     *
     * ### Why it is not the hole it looks like
     *
     * `pm clear` on an arbitrary package is somebody else's data deleted, so the package is never
     * taken from the request. The request carries a **word**, the word maps to one of [REPAIRABLE],
     * and the commands are written here. So:
     *
     *  - The set is fixed and small, and every member is a system app shipped with the phone. No
     *    third-party app is nameable, which is the property that matters — the escalation this
     *    file exists to stop is one app reaching another app.
     *  - What is lost is a system app's own settings. Not the user's files, not an account, not an
     *    app. Settings rebuilds its state on next launch, which is the entire point of running it.
     *  - `pm uninstall`, `pm disable`, `pm grant` naming these packages: still unreachable. Two
     *    verbs, and the enable is only ever the un-break of a disable.
     *  - The consent screen still runs, and marks these steps as touching another app rather than
     *    letting them read as "this app setting itself up".
     *
     * The literal `pm clear <pkg>` / `pm enable <pkg>` spellings are matched too, because that is
     * what an app's README would say — but they are matched only to be *thrown away*: what runs is
     * rebuilt from [REPAIRABLE] either way, and a line naming anything else is refused.
     */
    private fun repair(line: String): List<Step>? {
        val name = REPAIR.matchEntire(line)?.groupValues?.get(1)?.lowercase()
        val pkg = REPAIRABLE[name]
            ?: REPAIR_LINE.matchEntire(line)?.groupValues?.get(1)?.takeIf { it in REPAIRABLE.values }
            ?: return null
        val label = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        // Enable before clear, not after. A package that was disabled by whatever broke it will
        // refuse the clear outright, and then the run reports a failure for the step that was
        // supposed to be the fix.
        return listOf(
            Step(
                label = "Re-enable $label",
                command = "pm enable $pkg",
                check = GrantCheck.None,
                foreign = true,
            ),
            Step(
                label = "Reset $label",
                command = "pm clear $pkg",
                check = GrantCheck.None,
                foreign = true,
            ),
        )
    }

    /**
     * Start Shizuku, which is the one thing here that is not about the requesting app.
     *
     * ### Why this is a verb and not a command
     *
     * Everything else on the allowlist is "do X to yourself", checked by comparing a package name.
     * This one is "start somebody else's service", so package pinning says nothing about it and
     * the safety has to come from somewhere else: the request may only *ask*, and the string that
     * runs is written here. `start shizuku` is four letters of intent; a requester cannot smuggle
     * a path, an argument or a second command through it.
     *
     * ### Why it is safe to run
     *
     * The script lives in Shizuku's own external-storage directory. Since Android 11's scoped
     * storage, `Android/data/<pkg>/` is writable by that package and by the shell and by nothing
     * else — so no third-party app can plant a script for this to execute. And starting Shizuku
     * grants nothing by itself: it brings up a service that then asks the user, per app, in its
     * own UI. Two consents, neither of them here.
     *
     * ### Why it is worth having at all
     *
     * Shizuku's own startup route is the wireless-debugging pairing flow, and Android tears it
     * down on every reboot — so it is a flow you repeat, not one you complete. This app already
     * holds an adb shell, which is the same privilege by a route the user has already set up.
     * One tap instead of the dance.
     */
    private const val START_SHIZUKU =
        "sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

    /** Why a line was turned down, in words worth showing someone. */
    private fun refusalFor(pkg: String, line: String): String {
        val named = listOf(PM_GRANT, APPOPS, NOTIFICATION_LISTENER, ACCESSIBILITY)
            .firstNotNullOfOrNull { it.matchEntire(line)?.groupValues?.getOrNull(1) }
        val repairing = REPAIR_LINE.matchEntire(line)?.groupValues?.getOrNull(1)
        return when {
            repairing != null && repairing !in REPAIRABLE.values ->
                "asks to reset $repairing. Only the phone's own " +
                    REPAIRABLE.values.joinToString(" and ") + " can be repaired this way — " +
                    "any other package would be somebody's data deleted"
            named != null && named != pkg ->
                "names $named, but this request is from $pkg — an app may only set up itself"
            CONFIRM_PAIRING.matches(line) ->
                "asks to answer a pairing request, but this phone could not find $pkg's own " +
                    "installed code to run it from"
            else ->
                "not a permission, app op, notification listener, accessibility service, " +
                    "\"repair settings\", \"confirm pairing <MAC>\", or \"start shizuku\""
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

    /**
     * Matched as a *declaration* like [ACCESSIBILITY], not as the shell line. Deliberately narrow:
     * anything with an argument on it is a different request, and there is only one thing to say.
     */
    private val SHIZUKU = Regex("""(?:start|run) shizuku""", RegexOption.IGNORE_CASE)

    /**
     * The system apps a request may ask to have reset, by the word that names each one. A map and
     * not a predicate on purpose: the package that runs comes out of here, so no part of the
     * request reaches the command line even when the request spelled the package out.
     */
    private val REPAIRABLE = mapOf(
        "settings" to "com.android.settings",
        "bluetooth" to "com.android.bluetooth",
    )

    /** The declaration form, like [ACCESSIBILITY] and [SHIZUKU]: a word, not a command. */
    private val REPAIR =
        Regex("""(?:repair|reset|fix) (settings|bluetooth)(?: app)?""", RegexOption.IGNORE_CASE)

    /**
     * The spelling a README carries. Matched so the package in it can be *checked against*
     * [REPAIRABLE] and then discarded; nothing captured here is ever run.
     */
    private val REPAIR_LINE = Regex("""pm (?:clear|enable) ($PKG)""")

    /**
     * A declaration carrying exactly one argument, and that argument is six hex pairs.
     *
     * The pattern is the security boundary for [confirmPairing], so it is written as tightly as the
     * thing it describes: no path can hide in it, no space, no quote, no second command. Matched
     * with [Regex.matchEntire], which is what stops `confirm pairing AA:… ; rm -rf /` from being a
     * MAC address with a tail on it.
     */
    private val CONFIRM_PAIRING = Regex(
        """(?:confirm|answer) pairing ((?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * How long the helper keeps answering, in milliseconds — decided here, not by the request. A
     * pairing request stands for about thirty seconds; this covers it without leaving a command
     * running on the shell for longer than the thing it waits for can exist.
     */
    private const val CONFIRM_MS = 24_000

    private val APPOP_MODES = setOf("allow", "deny", "ignore", "default")
}
