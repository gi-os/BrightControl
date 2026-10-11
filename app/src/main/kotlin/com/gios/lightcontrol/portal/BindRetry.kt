package com.gios.lightcontrol.portal

/**
 * When the Wi-Fi login screen tries the bind again after Android refuses it with no VPN up.
 *
 * light-reports #672: the screen opened 0.4 s after the phone started joining the Wi-Fi. The
 * network was listed, but had no DNS and was not yet validated, and the bind failed. The only
 * retry ran 2 ms later on the same network. light-reports #686: the network the system's intent
 * handed over was already torn down and no Wi-Fi was listed at all, the replacement appearing a
 * moment later. A network that is still joining, or between networks, needs a moment, not a
 * verdict, so the retries are spaced out: about six and a half seconds in all before the
 * screen calls it a failure. Each attempt looks the Wi-Fi up afresh.
 *
 * No Android imports, so the schedule is checked without a phone.
 */
object BindRetry {

    private val DELAYS_MS = longArrayOf(250, 500, 1_000, 2_000, 3_000)

    /** How many retries follow the first refused bind. */
    val ATTEMPTS: Int get() = DELAYS_MS.size

    /** The longest the retries can hold the screen, pauses only. */
    val TOTAL_MS: Long get() = DELAYS_MS.sum()

    /** The pause before retry [attempt], counting from 1, or null once the retries are spent. */
    fun delayBefore(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt - 1)
}
