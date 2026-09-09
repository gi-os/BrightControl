package com.gios.lightcontrol.adb

/**
 * Which way to go, from whatever Settings screen the reader is looking at, to reach the pairing
 * dialog.
 *
 * Deliberately free of any Android import, for the same reason [AdbPairCode] is: this is the part
 * of the pairing path that decides to *touch* the user's Settings, and a wrong decision here does
 * not fail politely — it turns wireless debugging off. So the decision is plain string handling
 * that a unit test pins down, and the reader only carries it out.
 *
 * ## What went wrong before this existed
 *
 * The walk lived in AdbPairReader.advance as a two-arm `when`: tap "Pair device with pairing
 * code" if the screen says it, else tap "Wireless debugging" if the screen says that, else do
 * nothing. Three separate failures came out of those three arms, and between them they account for
 * most of the pairing reports filed all summer.
 *
 *  - **Nothing to tap, and no way to look further.** The app drops the user on *Developer options*
 *    and the "Wireless debugging" row is below the fold on a 360-pixel screen. A row that is not
 *    laid out is not in the flattened text and has no node to click, so the `when` fell to `else`,
 *    returned false, and the ninety seconds ran out with the row one swipe away. Seven reports say
 *    `windows seen while waiting: Developer options` and nothing else — light-reports#302, #296,
 *    #290, #266, #255, #254 and #247.
 *  - **The second arm turning the feature off.** On the Wireless debugging screen itself, the
 *    pairing row can also be below the fold — and there the fallback arm fires, because that screen
 *    says "Wireless debugging" in its own title. `findAccessibilityNodeInfosByText` matches on a
 *    substring, so it matched the switch row **Use wireless debugging**, which is clickable, and
 *    clicked it. That is the report that reads *"the pairing was accepted and mDNS then found
 *    nothing to connect to — wireless debugging may have been switched off by the trip through
 *    Settings"*: it was, and this is one of the things that could have done it. Thirteen reports
 *    read that way, light-reports#318 down to #229.
 *  - **Reaching the row and not pressing it.** Covered in the reader, not here — the clickable
 *    node in a preference list is not the label's parent.
 *
 * So the fallback is now guarded by the screen it must never fire on, and "look further" is a step
 * of its own instead of an `else`.
 */
object AdbPairWalk {

    /** What to do with the screen the reader just flattened. */
    sealed interface Step {
        /** Press the row with this label. The label is matched as written, on this screen only. */
        data class Tap(val label: String) : Step

        /** The row we want is not laid out. Scroll the list one page and look again. */
        data object Scroll : Step

        /** Nothing on this screen leads anywhere. Leave it alone. */
        data object Nothing : Step
    }

    /**
     * The next step towards the dialog, from this screen.
     *
     * Order matters and each arm earns its place:
     *
     * 1. The dialog itself is not walked. [AdbPairCode.extract] reads it; touching it can only
     *    dismiss it, and a dismissed dialog takes the pairing session with it.
     * 2. The pairing row, wherever it appears. This is the only row worth pressing.
     * 3. The Wireless debugging screen **without** its pairing row on it: scroll, never tap. This
     *    arm exists solely to keep arm 4 away from a screen where "Wireless debugging" is the
     *    title above an on/off switch rather than a row that goes somewhere.
     * 4. The Wireless debugging row, on any other screen — which in practice means Developer
     *    options with the list already scrolled down to it.
     * 5. Developer options with the row still below the fold: scroll.
     */
    fun next(text: String): Step = when {
        AdbPairCode.looksLikePairingDialog(text) -> Step.Nothing
        text.contains(PAIRING_ROW, ignoreCase = true) -> Step.Tap(PAIRING_ROW)
        onTheWirelessDebuggingScreen(text) -> Step.Scroll
        text.contains(WIRELESS_ROW, ignoreCase = true) -> Step.Tap(WIRELESS_ROW)
        looksLikeDeveloperOptions(text) -> Step.Scroll
        else -> Step.Nothing
    }

    /**
     * The Wireless debugging screen, told apart from every screen that merely mentions it.
     *
     * "Use wireless debugging" is the label on that screen's own switch and appears nowhere else,
     * which is why [AdbPairCode.looksLikeTheList] already uses it. Not reused wholesale here: that
     * test also accepts the QR screen, and the QR screen has nothing below the fold to scroll to.
     */
    private fun onTheWirelessDebuggingScreen(text: String): Boolean =
        text.contains("Use wireless debugging", ignoreCase = true)

    /**
     * Developer options, which is where the app itself puts the user, so it is the one screen the
     * walk can count on being handed.
     *
     * Matched on its heading, and that is a real limit rather than a shortcut: every label here is
     * English, and light-reports#259 is this walk on a phone set to French — *"Options pour les
     * développeurs / Débogage sans fil"* — where nothing matched and the reader waited out the
     * ninety seconds beside a screen it could have walked. Which languages to carry is not a
     * decision to guess at, so it is left open rather than half-answered.
     */
    private fun looksLikeDeveloperOptions(text: String): Boolean =
        text.contains("Developer options", ignoreCase = true)

    const val PAIRING_ROW = "Pair device with pairing code"

    const val WIRELESS_ROW = "Wireless debugging"

    /**
     * How many scrolls one armed window is allowed.
     *
     * Developer options is the longest list this walk meets and it is about four screens on this
     * phone. Twelve is room to spare and still a hard stop, so a list that reports itself scrollable
     * and never moves cannot have the whole ninety seconds spent on it.
     */
    const val MAX_SCROLLS = 12
}
