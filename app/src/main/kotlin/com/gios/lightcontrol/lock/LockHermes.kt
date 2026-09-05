package com.gios.lightcontrol.lock

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager

/** The one card June has put on the lock face, flattened to what the face draws. */
data class LockHermesCard(
    val title: String,
    val text: String,
    /** Epoch seconds. The card is nothing after this, whatever the provider still says. */
    val expiresAt: Double,
    val action: String?,
) {
    fun live(nowMs: Long = System.currentTimeMillis()): Boolean =
        (title.isNotBlank() || text.isNotBlank()) && nowMs / 1000.0 < expiresAt
}

/**
 * BrightHermes's lock card, read off its provider, for the face to draw where the player goes.
 *
 * ### The contract
 *
 * `content://com.gios.brighthermes.deck/lock` answers **at most one row** — `title`, `text`,
 * `expiresAt` (epoch seconds), `action`, `updatedAt` — the one very important thing June has
 * decided cannot wait for the phone to be opened, and an empty cursor when there is none or it
 * has run out. Querying it while the screen is on also makes BrightHermes ask its gateway for a
 * fresher card and `notifyChange` if one arrives, so the face's query-on-wake is what surfaces a
 * card posted while the phone lay dark. The face observes the URI for the answer.
 *
 * ### Where it goes
 *
 * The media row's slot, **in place of** the media row. That is the one place on the face a
 * message can go without moving the clock or the notes, and it is the place the eye already
 * goes for "the thing that is happening". Music comes back the moment the card is gone.
 *
 * ### Lifecycle
 *
 * Same as [LockNav] and [LockNextUp]: started with the window, stopped with it, an observer ping
 * against a dark panel ignored because the wake re-asks anyway. Absent BrightHermes, every query
 * fails or answers empty, and both mean no row.
 */
class LockHermes(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Told on the main thread whenever the answer changes. Null means no card. */
    var onChange: ((LockHermesCard?) -> Unit)? = null

    var state: LockHermesCard? = null
        private set

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var observer: ContentObserver? = null

    @Volatile
    private var watching = false

    fun start() {
        if (watching) return
        watching = true
        val t = HandlerThread("lock-hermes").apply { start() }
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

    /** Ask again now — the screen-on half of "query on face show + screen-on". */
    fun requery() {
        worker?.post { read() }
    }

    private fun interactive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(true)

    private fun read() {
        val next = query()?.takeIf { it.live() }
        main.post {
            if (!watching) return@post
            if (next == state) return@post
            state = next
            runCatching { onChange?.invoke(next) }
        }
    }

    private fun query(): LockHermesCard? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            LockHermesCard(
                title = text(c, "title").orEmpty(),
                text = text(c, "text").orEmpty(),
                expiresAt = double(c, "expiresAt") ?: 0.0,
                action = text(c, "action"),
            )
        }
    }.getOrNull()

    private fun text(c: Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getString(i)
    }

    private fun double(c: Cursor, name: String): Double? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getDouble(i)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.gios.brighthermes.deck/lock")
        const val PACKAGE = "com.gios.brighthermes"
    }
}
