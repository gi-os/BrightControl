package com.gios.lightcontrol.lock

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The next thing on the calendar, flattened to the one line the lock face draws. */
data class LockNextUpEntry(
    val startAt: Long,
    val title: String,
    val kind: String,
    val allDay: Boolean,
)

/**
 * BrightNotebook's next entry, read off its provider, for the quiet line under the clock.
 *
 * ### The contract
 *
 * `content://com.gios.lightnotebook.nextup/next` answers with at most one row -- `startAt`
 * (epoch ms), `title`, `kind` ("event", "reminder" or "ticket"), `allDay` (0/1) -- covering the
 * next 48 hours, and an empty cursor when there is nothing. Its `notifyChange` is best-effort,
 * so the face queries on show and on every wake as well as observing; what it must **never** do
 * is poll on a schedule while the phone is dark, which is the v2.10 all-night bug.
 *
 * ### Absent rather than broken
 *
 * Until the Notebook release that ships the provider lands, every query fails or answers empty,
 * and both mean a hidden line -- which is exactly right, with no version anywhere. Same for a
 * phone that never installs the Notebook at all.
 *
 * Same lifecycle as [LockNav] and [LockMedia]: started with the window, stopped with it, and an
 * observer ping against a dark panel is ignored because the wake re-asks anyway.
 */
class LockNextUp(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Told on the main thread whenever the answer changes. Null means nothing in the 48 h. */
    var onChange: ((LockNextUpEntry?) -> Unit)? = null

    var state: LockNextUpEntry? = null
        private set

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var observer: ContentObserver? = null

    @Volatile
    private var watching = false

    /** Begin watching, with the one initial query the contract asks for. Idempotent. */
    fun start() {
        if (watching) return
        watching = true
        val t = HandlerThread("lock-nextup").apply { start() }
        thread = t
        val w = Handler(t.looper)
        worker = w
        val obs = object : ContentObserver(w) {
            override fun onChange(selfChange: Boolean) {
                if (!interactive()) return
                read()
            }
        }
        observer = obs
        runCatching { context.contentResolver.registerContentObserver(URI, false, obs) }
        w.post { read() }
    }

    fun stop() {
        if (!watching) return
        watching = false
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        worker = null
        thread?.quitSafely()
        thread = null
        state = null
    }

    /** Ask again now -- the screen-on half of "query on face show + screen-on". */
    fun requery() {
        worker?.post { read() }
    }

    private fun interactive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(true)

    private fun read() {
        val next = query()
        main.post {
            if (!watching) return@post
            if (next == state) return@post
            state = next
            runCatching { onChange?.invoke(next) }
        }
    }

    private fun query(): LockNextUpEntry? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val title = text(c, "title")?.takeIf { it.isNotBlank() } ?: return@use null
            val startAt = long(c, "startAt") ?: return@use null
            LockNextUpEntry(
                startAt = startAt,
                title = title,
                kind = text(c, "kind").orEmpty(),
                allDay = (int(c, "allDay") ?: 0) != 0,
            )
        }
    }.getOrNull()

    private fun text(c: Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getString(i)
    }

    private fun int(c: Cursor, name: String): Int? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getInt(i)
    }

    private fun long(c: Cursor, name: String): Long? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getLong(i)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.gios.lightnotebook.nextup/next")
    }
}

/**
 * The line itself. Its own object so the day arithmetic can be tested without a phone --
 * "tomorrow" is a timezone question, and timezone questions are where a quiet line starts lying.
 */
object NextUpText {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    /**
     * "NEXT UP · 9:30 DENTIST", built from the entry and the clock.
     *
     * A timed entry shows its time; on a later day the day comes first, because "9:30" alone on
     * Tuesday night means Wednesday morning to nobody. An all-day entry has no time to show, so
     * it says the day. The window is 48 hours, so the day is almost always TOMORROW; the weekday
     * is the honest fallback for a provider that answered past its own contract.
     */
    fun label(startAt: Long, allDay: Boolean, now: Long, zone: ZoneId, title: String): String {
        val head = lead(startAt, allDay, now, zone)
        val name = title.replace(WHITESPACE, " ").trim().uppercase(Locale.US)
        return "NEXT UP · $head $name"
    }

    private fun lead(startAt: Long, allDay: Boolean, now: Long, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayWord = when {
            !day.isAfter(today) -> "TODAY"
            day == today.plusDays(1) -> "TOMORROW"
            else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US)
        }
        if (allDay) return dayWord
        val time = TIME.format(Instant.ofEpochMilli(startAt).atZone(zone))
        return if (!day.isAfter(today)) time else "$dayWord $time"
    }

    private val WHITESPACE = Regex("""\s+""")
}
