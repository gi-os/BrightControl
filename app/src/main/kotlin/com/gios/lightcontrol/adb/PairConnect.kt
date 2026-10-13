package com.gios.lightcontrol.adb

/**
 * The decisions in the connect after a pairing: when to try, and what to say when every try fails.
 *
 * Kept apart from [AdbPairSession], which needs a phone, so the words can be tested on the JVM.
 *
 * ## Why the message is built from what was seen
 *
 * The old message said wireless debugging "was likely switched off by the trip through Settings",
 * and pointed at "the button at the top". The app had already checked the switch, and in
 * light-reports#700 it was on. The button only appears while the switch is off, so the user was
 * sent to look for a button that was not there. Now the message names only what the tries found.
 */
object PairConnect {

    /**
     * The wait before each try, in milliseconds. The first try goes at once.
     *
     * Three tries with about five seconds of waiting. That covers the moment the phone takes to
     * save a newly paired key, and a daemon coming back after the switch was turned on, without
     * making a working pairing wait.
     */
    val WAITS_MS = longArrayOf(0L, 1_500L, 3_500L)

    /**
     * How long mDNS may look on each try.
     *
     * Longer on the first try, which follows the trip back from Settings. The later tries follow a
     * try that already looked.
     */
    fun discoveryMs(index: Int): Long = if (index == 0) 10_000L else 6_000L

    /**
     * The failure that says the most, across all the tries.
     *
     * A refusal outranks everything: something answered, so the service was there and the key
     * was the problem. "Found nothing" outranks a closed port, because the port is only ever a
     * guess saved from the screen and can be old.
     */
    fun worst(kinds: List<AdbFailure.Kind?>): AdbFailure.Kind? =
        RANK.firstOrNull { it in kinds }

    private val RANK = listOf(
        AdbFailure.Kind.Refused,
        AdbFailure.Kind.NotFound,
        AdbFailure.Kind.Closed,
        AdbFailure.Kind.Other,
    )

    /** What the ADB screen says under the PAIR button after every try failed. */
    fun failedMessage(wifiOn: Boolean?, kind: AdbFailure.Kind?): String = when {
        wifiOn == false ->
            "paired, but wireless debugging is off now, so there is nothing to connect to. Tap " +
                "TURN WIRELESS DEBUGGING ON near the top of this screen, then GRANT ALL."
        kind == AdbFailure.Kind.Refused ->
            "paired, but the phone refused the connection. Tap GRANT ALL below to try again. If " +
                "it is refused again, tap START OVER AND PAIR."
        kind == AdbFailure.Kind.NotFound ->
            "paired, but the phone's debugging service did not show up. Check that Wi-Fi is " +
                "still connected, then tap GRANT ALL below to try again."
        else ->
            "paired, but the connection did not go through. Tap GRANT ALL below to try again. " +
                "If it fails again, tap START OVER AND PAIR."
    }

    /** The report body: what the tries found, then the trail that shows each one. */
    fun reportDetail(wifiOn: Boolean?, kind: AdbFailure.Kind?, trail: List<String>): String =
        buildString {
            append("The phone accepted the pairing, then ${WAITS_MS.size} connect tries failed.\n")
            append("Wireless debugging after the last try: ")
            append(
                when (wifiOn) {
                    true -> "on"
                    false -> "off"
                    null -> "unknown"
                },
            )
            append("\n")
            append("Most telling failure: ${kind?.name ?: "none recorded"}\n")
            append("\n")
            append("Pair trail:\n")
            trail.takeLast(TRAIL_LINES).forEach { append(AdbFailure.scrub(it)).append("\n") }
        }.trimEnd()

    /** The whole trail fits: it is cut at 24 lines on the phone. */
    private const val TRAIL_LINES = 24
}
