package com.gios.lightcontrol.lock

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import java.util.Locale

/** Today's weather, flattened to the one dim line the lock face draws. */
data class LockWeatherEntry(
    val updatedAt: Long,
    val tempC: Double,
    val hiC: Double,
    val loC: Double,
    val code: Int,
    val description: String,
    val precipPct: Int,
)

/**
 * LightFog's weather, read off its provider, for the dim line in the top region.
 *
 * ### The contract
 *
 * `content://com.gios.lightfog.weather/today` answers with at most one row — `updatedAt` (epoch
 * ms of the fetch), `tempC`/`hiC`/`loC` (Doubles, Celsius), `code` (Int, WMO), `description`
 * (String, e.g. "Clear"), `precipPct` (Int 0–100) — and an empty cursor when nothing has ever
 * been fetched. LightFog serves stale weather on purpose and `updatedAt` is how the reader
 * decides; this face's line is [WeatherText]'s call, and past three hours it shows nothing.
 *
 * ### Absent rather than broken
 *
 * No LightFog installed, a LightFog too old to have the provider, a query that throws, an empty
 * cursor — all of them are a hidden line, with no version check anywhere.
 *
 * Same lifecycle as [LockNav] and [LockNextUp]: started with the window, stopped with it,
 * queried on face show and on every wake, on its own HandlerThread, and **never** polled on a
 * schedule while the phone is dark — weather is the last thing worth the v2.10 all-night bug.
 */
class LockWeather(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Told on the main thread whenever the answer changes. Null means nothing to draw. */
    var onChange: ((LockWeatherEntry?) -> Unit)? = null

    var state: LockWeatherEntry? = null
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
        val t = HandlerThread("lock-weather").apply { start() }
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
        val next = query()
        main.post {
            if (!watching) return@post
            if (next == state) return@post
            state = next
            runCatching { onChange?.invoke(next) }
        }
    }

    private fun query(): LockWeatherEntry? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            LockWeatherEntry(
                updatedAt = long(c, "updatedAt") ?: return@use null,
                tempC = double(c, "tempC") ?: return@use null,
                hiC = double(c, "hiC") ?: return@use null,
                loC = double(c, "loC") ?: return@use null,
                code = int(c, "code") ?: 0,
                description = text(c, "description").orEmpty(),
                precipPct = (int(c, "precipPct") ?: 0).coerceIn(0, 100),
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

    private fun double(c: Cursor, name: String): Double? {
        val i = c.getColumnIndex(name)
        return if (i < 0 || c.isNull(i)) null else c.getDouble(i)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.gios.lightfog.weather/today")
    }
}

/**
 * The line itself. Its own object so the conversion and the staleness rule can be tested
 * without a phone — a rounding mistake here is one degree of quiet lying, all day.
 */
object WeatherText {

    /**
     * How old the fetch may be before the line stops being drawn at all.
     *
     * LightFog serves stale weather deliberately and says so in `updatedAt`; the face's answer
     * is the same as everywhere else on it — absent, not broken. A morning temperature shown at
     * dinner is worse than no line, because a wrong line teaches the user to stop reading it.
     */
    const val MAX_AGE_MS = 3L * 60L * 60L * 1000L

    /** How likely rain has to be before it earns a place on the line. */
    const val RAIN_FROM_PCT = 40

    /** Celsius to whole Fahrenheit, half-up — 21.5 °C is 71 °F, not 70. */
    fun fahrenheit(celsius: Double): Int = Math.round(celsius * 9.0 / 5.0 + 32.0).toInt()

    /**
     * "72° · CLEAR · H 81 L 64", with "· RAIN 60%" only when it is actually worth carrying an
     * umbrella — or null when the fetch is older than [MAX_AGE_MS] and the line should not
     * exist. US units on the face; the provider speaks Celsius and the conversion lives here.
     */
    fun label(entry: LockWeatherEntry, now: Long): String? {
        if (entry.updatedAt <= 0L) return null
        if (now - entry.updatedAt > MAX_AGE_MS) return null
        val sky = entry.description.replace(WHITESPACE, " ").trim().uppercase(Locale.US)
            .ifEmpty { return null }
        val line = "${fahrenheit(entry.tempC)}° · $sky · " +
            "H ${fahrenheit(entry.hiC)} L ${fahrenheit(entry.loC)}"
        return if (entry.precipPct >= RAIN_FROM_PCT) "$line · RAIN ${entry.precipPct}%" else line
    }

    private val WHITESPACE = Regex("""\s+""")
}
