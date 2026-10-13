package com.gios.lightcontrol.adb

/**
 * A failed adb step, in one line for the pairing trail and the report.
 *
 * ## Why this exists
 *
 * Every connect in the pairing flow ran inside `runCatching { }.getOrDefault(false)`. A refused
 * handshake, a closed port and an mDNS lookup that timed out all came out as `false`, and the
 * trail wrote "mDNS found nothing" for each of them. About fifty reports carried that guess, and
 * not one carried the exception. The cause was found from a screenshot of the trail, by its
 * timing: both connects failed in the same second, and an mDNS lookup that finds nothing takes
 * fifteen. This keeps the exception, so the next fault can be read from the report itself.
 *
 * No Android imports, so it runs in the JVM unit tests.
 */
object AdbFailure {

    /** What kind of answer a failed connect got. Decides the words the user sees. */
    enum class Kind {
        /** mDNS found no debugging service before its deadline. */
        NotFound,

        /** A port was tried and nothing was listening on it. */
        Closed,

        /** Something answered and refused this app: the TLS handshake or the key. */
        Refused,

        /** Anything else, including a connect that ran out of time. */
        Other,
    }

    /** The exception and up to two causes, LAN addresses removed, at most [MAX] characters. */
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { e -> e.cause?.takeIf { it !== e } }
            .take(3)
            .map { e ->
                e.javaClass.simpleName +
                    (e.message?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
            }
            .toList()
        return scrub(chain.joinToString(" <- ")).take(MAX)
    }

    /**
     * Sort a failure by what it says about the phone.
     *
     * libadb's discovery throws `InterruptedException("Timed out while trying to find a valid host
     * address and port")` when nothing turns up, and `IOException("Could not find any valid host
     * address or port")` when a service was lost before it resolved. A closed port is a
     * `ConnectException` somewhere in the chain. A refusal is TLS, or libadb's own pairing and
     * authentication exceptions.
     */
    fun kind(error: Throwable): Kind {
        val chain = generateSequence(error) { e -> e.cause?.takeIf { it !== e } }.take(4).toList()
        val text = chain.joinToString(" ") { (it.javaClass.name + " " + (it.message ?: "")) }
        return when {
            text.contains("valid host", ignoreCase = true) -> Kind.NotFound
            chain.any { it is java.net.ConnectException } ||
                text.contains("ECONNREFUSED") -> Kind.Closed
            chain.any { it.javaClass.name.startsWith("javax.net.ssl.") } ||
                text.contains("PairingRequired") ||
                text.contains("AuthenticationFailed") ||
                text.contains("Connection failed") -> Kind.Refused
            else -> Kind.Other
        }
    }

    /**
     * The same text with LAN addresses taken out.
     *
     * Loopback stays, because which address a connect went to is part of the diagnosis and
     * `127.0.0.1` identifies nobody. A Wi-Fi address and an IPv6 address are the phone's place on
     * somebody's network, and that does not belong in an issue.
     *
     * IPv6 is matched only when the text has `::` or a hex letter in it. Without that rule the
     * pattern also matches a clock time like `21:13:25`.
     */
    fun scrub(text: String): String =
        text
            .replace(IPV4) { if (it.value.startsWith("127.")) it.value else LAN }
            .replace(IPV6, LAN)

    private const val LAN = "<lan>"

    /** Long enough for an exception and two causes, short enough for one trail line. */
    private const val MAX = 240

    private val IPV4 = Regex("""(?<![\d.])\d{1,3}(?:\.\d{1,3}){3}(?![\d.])""")

    private val IPV6 = Regex(
        """(?<![0-9A-Za-z:])(?=[0-9A-Fa-f:]*(?:::|[A-Fa-f]))""" +
            """(?:[0-9A-Fa-f]{1,4})?(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[0-9A-Za-z_.]+)?""",
    )
}
