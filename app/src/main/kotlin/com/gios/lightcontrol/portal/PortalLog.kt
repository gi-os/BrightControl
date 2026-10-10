package com.gios.lightcontrol.portal

/**
 * What the Wi-Fi login screen saw, line by line, in the order it saw it.
 *
 * The screen had one status line and no memory. When it failed — and on the phone it does fail —
 * the only account of why was whatever that line happened to say at the moment somebody gave up,
 * and "sign in above — checking the connection as you go" explains nothing. This is the log the
 * screen writes for itself as it goes: which networks it found and what the system thought of
 * them, whether a WebView existed, what every probe answered, every page the WebView started and
 * every error it swallowed. It is what gets attached to the report when the screen fails, and what
 * the LOG button shows on the phone.
 *
 * Pure Kotlin on purpose (the clock is injected) so `kotlinc` can test it without Android.
 */
class PortalLog(
    private val now: () -> Long = System::currentTimeMillis,
    /** Enough for a long portal flow; the issue body caps at 65,536 characters in total. */
    private val maxChars: Int = 24_000,
) {

    private val lines = ArrayList<String>()
    private var chars = 0
    private var dropped = 0
    private var signal = 0
    private val startedAt = now()

    /** Called with each new line, on whatever thread wrote it. */
    var onLine: ((String) -> Unit)? = null

    @Synchronized
    fun add(text: String) {
        val elapsed = now() - startedAt
        val line = "%3d.%03d  %s".format(elapsed / 1000, elapsed % 1000, text.replace('\n', ' '))
        lines += line
        chars += line.length + 1
        if (!isBookkeeping(text)) signal++
        // Oldest first out. The end of the log is where the failure is; the start is repeatable
        // by opening the screen again.
        while (chars > maxChars && lines.size > 1) {
            chars -= lines.removeAt(0).length + 1
            dropped++
        }
        onLine?.invoke(line)
    }

    @Synchronized
    fun dump(): String = buildString {
        if (dropped > 0) appendLine("… $dropped earlier lines dropped")
        lines.forEach { appendLine(it) }
    }.trimEnd()

    @Synchronized
    fun last(): String? = lines.lastOrNull()

    @Synchronized
    fun size(): Int = lines.size

    /**
     * How many lines so far said something about the network, counting ones since dropped. The
     * SEND LOG button's own lines do not count: three taps in a row are one log, not three, and
     * light-reports #673–675 are what filing each tap looks like.
     */
    @Synchronized
    fun signal(): Int = signal

    companion object {
        /** Lines the SEND LOG button writes about itself. */
        val BOOKKEEPING = listOf("SEND LOG tapped", "SEND LOG: nothing new", "report posted or queued", "report queued")

        fun isBookkeeping(text: String): Boolean = BOOKKEEPING.any { text.startsWith(it) }
    }
}
