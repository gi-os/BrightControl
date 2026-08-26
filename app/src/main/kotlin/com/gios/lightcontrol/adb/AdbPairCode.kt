package com.gios.lightcontrol.adb

/**
 * Finding the six-digit pairing code in a screenful of Settings text.
 *
 * Deliberately free of any Android import. This is the one part of the pairing path that Light's
 * fork of Settings can quietly break — everything downstream is protocol work that either
 * succeeds or throws — so it is kept as plain string handling that a unit test can pin down,
 * and tuned here alone if a real LPIII ever disagrees with it.
 *
 * The reader hands over a window flattened to one node per line, text and content description
 * alike, which is what makes the strong signal available: a line that is *exactly* six digits is
 * the code, near enough always.
 *
 * ## Why there are five strategies and not one
 *
 * light-reports#61, from a real phone: *"pairing box present but numbers within not detected."*
 * The dialog was found — that message only appears once the text has been recognised as the
 * pairing dialog — and no six-digit run was in it. So the digits are on that screen in a shape
 * this did not expect, and the shapes it could be are enumerable:
 *
 *  - **Grouped.** `123 456`, or with a hyphen. Neither is six consecutive digits.
 *  - **Split per node.** A row of six views, one digit each, flattened to six lines.
 *  - **Labelled inline.** `Wi-Fi pairing code: 123456` in one view, which the old loose scan did
 *    catch — but only after the dialog test, which is why the tighter passes come first.
 *
 * Each pass is tried in order of how much it proves, and the loose ones only run once the text is
 * known to be the pairing dialog, so a stray number elsewhere in Settings can never start a
 * pairing.
 */
object AdbPairCode {

    /**
     * True when this text looks like the pairing dialog rather than one of the several Settings
     * screens the reader passes on the way there.
     *
     * The address is the strongest second signal — a dialog that offers a code offers the
     * `ip:port` to use it against — but it is not guaranteed to be in the same window as the
     * code, and a reskin that separates them would leave this returning false about the very
     * screen it exists to recognise. So the word "code" counts too. Six digits alone never does:
     * that is a build number.
     */
    fun looksLikePairingDialog(text: String): Boolean {
        if (!text.contains("pair", ignoreCase = true)) return false
        return ADDRESS.containsMatchIn(text) || text.contains("code", ignoreCase = true)
    }

    /**
     * The code, or null if this screen does not carry one.
     *
     * The port on the same dialog is not a hazard: a TCP port stops at 65535, so it is five digits
     * at most and cannot satisfy a six-digit match. An IP address cannot either — stripped of its
     * dots it is eight digits or more, and [group] requires exactly six.
     */
    fun extract(text: String): String? {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()

        // 1. Six digits alone on a line. The one pass strong enough to need no corroboration.
        lines.firstOrNull { it.length == 6 && it.all(Char::isDigit) }?.let { return it }

        if (!looksLikePairingDialog(text)) return null

        // 2. Six digits on a line, grouped. `123 456`, `123-456`, and the space Android sometimes
        //    renders as a non-breaking or thin one.
        lines.firstNotNullOfOrNull { group(it) }?.let { return it }

        // 3. Six lines, one digit each — a row of views flattened. Consecutive, so a digit from a
        //    label at the top and five from the bottom of the screen cannot be read as a code.
        digitsAcrossLines(lines)?.let { return it }

        // 4. Labelled inline: the digits that follow the word "code", grouped or not, within the
        //    short reach of a label rather than anywhere on the screen.
        labelled(text)?.let { return it }

        // 5. Any six-digit run. Last, because by here nothing more specific has matched and the
        //    text is known to be the dialog.
        return LOOSE.find(text)?.value
    }

    /** A line whose digits are exactly six once the separators a designer might use are removed. */
    private fun group(line: String): String? {
        if (line.any { it in FORBIDDEN }) return null
        val digits = line.filter(Char::isDigit)
        if (digits.length != 6) return null
        // Everything that is not a digit has to be one of the characters a code is grouped with.
        // Without this, "Android 14 (SDK 34) 1234" and friends would qualify on digit count alone.
        if (line.any { !it.isDigit() && it !in SEPARATORS }) return null
        return digits
    }

    /** Six consecutive single-digit lines, joined. */
    private fun digitsAcrossLines(lines: List<String>): String? {
        var run = StringBuilder()
        for (line in lines) {
            if (line.length == 1 && line[0].isDigit()) {
                run.append(line)
                if (run.length == 6) return run.toString()
            } else if (run.isNotEmpty()) {
                run = StringBuilder()
            }
        }
        return null
    }

    /**
     * Digits within reach of the word "code", which is how a reskin labels one.
     *
     * Line-wise rather than character-wise, and that is not a detail: scanning forward through
     * characters runs straight into `192.168.1.10:37103` and reads `192168` as a pairing code,
     * which then fails against a daemon for a reason nobody could guess from the screen. A line
     * carrying a colon or a dot is an address and is skipped whole.
     *
     * Only the label's own line and the two after it are considered. A code sits next to its
     * label; six digits found five rows away belong to something else.
     */
    private fun labelled(text: String): String? {
        val at = text.indexOf("code", ignoreCase = true)
        if (at < 0) return null
        val after = text.substring(minOf(text.length, at + "code".length))
        return after.lineSequence()
            .take(LABEL_LINES)
            .map(String::trim)
            .mapNotNull { line ->
                if (line.any { it in FORBIDDEN }) return@mapNotNull null
                // Punctuation a label ends with, and the words either side of it, are dropped —
                // what is left has to be digits and the separators a code is grouped with.
                val stripped = line.dropWhile { !it.isDigit() }
                if (stripped.any { !it.isDigit() && it !in SEPARATORS }) return@mapNotNull null
                stripped.filter(Char::isDigit).takeIf { it.length == 6 }
            }
            .firstOrNull()
    }

    /** The label's line and the two after it. A code sits beside its label, not across the screen. */
    private const val LABEL_LINES = 3

    /** What a six-digit code may be broken up with. */
    private val SEPARATORS = charArrayOf(' ', ' ', ' ', ' ', '-', '–', '_')

    /**
     * Characters whose presence means a line is an address or a port, not a code. Checked before
     * the digit count, because `192.168.1.10:37103` stripped to digits is long enough to be
     * rejected anyway but `10:37103` is not.
     */
    private val FORBIDDEN = charArrayOf(':', '.', '/')

    private val ADDRESS = Regex("""\d+\.\d+\.\d+\.\d+:\d{4,5}""")
    private val LOOSE = Regex("""(?<!\d)\d{6}(?!\d)""")
}
