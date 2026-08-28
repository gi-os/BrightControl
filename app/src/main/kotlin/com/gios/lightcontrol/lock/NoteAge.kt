package com.gios.lightcontrol.lock

/**
 * How long ago a notification was posted, in the fewest characters that still say it.
 *
 * The face listed four messages with no hint of when any of them arrived, so a text from Tuesday
 * and one from a minute ago read identically — and on a phone whose whole point is that you look
 * at it rarely, the age of a row is most of what it means.
 *
 * Its own object so the arithmetic can be tested without a phone, like [CallWho] and [NoteFilter].
 *
 * ### Why these words
 *
 * Minutes for the first hour, hours to a day, days after that. Nothing finer than a minute:
 * the face repaints on `ACTION_TIME_TICK` and nothing else, so a "40S" would be wrong for most of
 * the minute it was on screen, and something that arrived seconds ago is the notification you are
 * holding the phone to read anyway. `NOW` covers that whole first minute.
 *
 * Uppercase and abbreviated because this sits on the small tracked label the rest of the phone
 * uses for a section — the same line as the app name, so a row costs no more height than before.
 * [LockNoteList] hands out rows by the space left under the clock, and a fifth line per row would
 * have been paid for out of the row count.
 *
 * A clock that has gone backwards — a manual time change, an NTP correction — reads as `NOW`
 * rather than as a negative number, which is the only honest answer to "posted in the future".
 */
object NoteAge {

    private const val MINUTE = 60_000L

    /** The label for a notification posted at [postedAt], as of [now]. Empty when unknown. */
    fun label(postedAt: Long, now: Long): String {
        // A `StatusBarNotification` always carries a `postTime`, but a zero here means something
        // upstream lost it, and "1970" on a lock screen is worse than saying nothing.
        if (postedAt <= 0L) return ""
        val minutes = (now - postedAt) / MINUTE
        if (minutes < 1L) return "NOW"
        if (minutes < 60L) return "${minutes}M"
        val hours = minutes / 60L
        if (hours < 24L) return "${hours}H"
        return "${hours / 24L}D"
    }
}
