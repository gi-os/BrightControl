package com.gios.lightcontrol.adb

/**
 * Finding the six-digit pairing code in a screenful of Settings text.
 *
 * Deliberately free of any Android import. This is the one part of the pairing path that Light's
 * fork of Settings can quietly break — everything downstream is protocol work that either
 * succeeds or throws — so it is kept as plain string handling that a unit test can pin down,
 * and tuned here alone if a real LPIII ever disagrees with it.
 *
 * The reader hands over a window flattened to one node per line, which is what makes the strong
 * signal available: a line that is *exactly* six digits is the code, near enough always.
 */
object AdbPairCode {

    /**
     * True when this text looks like the pairing dialog rather than one of the several Settings
     * screens the reader passes on the way there.
     *
     * Both halves are needed. Six digits alone show up as build numbers and device ids; an
     * address alone is on the Wireless debugging screen itself, which has no code on it.
     */
    fun looksLikePairingDialog(text: String): Boolean =
        text.contains("pair", ignoreCase = true) && ADDRESS.containsMatchIn(text)

    /**
     * The code, or null if this screen does not carry one.
     *
     * A standalone six-digit line wins outright. Failing that — a reskin that puts the label and
     * the digits in one view, say — fall back to a loose scan, but only once the text is known to
     * be the pairing dialog, so a stray number elsewhere in Settings can never start a pairing.
     *
     * The port on the same dialog is not a hazard: a TCP port stops at 65535, so it is five
     * digits at most and cannot satisfy a six-digit match.
     */
    fun extract(text: String): String? {
        text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.length == 6 && it.all(Char::isDigit) }
            ?.let { return it }

        if (!looksLikePairingDialog(text)) return null
        return LOOSE.find(text)?.value
    }

    private val ADDRESS = Regex("""\d+\.\d+\.\d+\.\d+:\d{4,5}""")
    private val LOOSE = Regex("""(?<!\d)\d{6}(?!\d)""")
}
