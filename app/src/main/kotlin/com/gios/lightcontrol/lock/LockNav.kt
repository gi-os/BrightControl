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
import kotlin.math.roundToInt

/** The current turn, flattened to what the lock face draws. */
data class LockNavStep(
    val instruction: String,
    val distanceM: Int,
    val etaMinutes: Int,
    val mode: String,
    val stepIndex: Int,
    val stepCount: Int,
)

/**
 * BrightWay's current turn, read off its provider, for the lock face to draw.
 *
 * ### The contract
 *
 * `content://com.gios.brightway.nav/current` is BrightWay's deliberate channel to this face,
 * shipped in its v1.10.14 and stated in its `NavProvider`: while navigating, exactly one row --
 * `instruction`, `distanceM` (live), `etaMinutes` (whole trip, never 0 while anything remains),
 * `mode`, `lineColor`, `stepIndex`, `stepCount`, `updatedAt` -- and an **empty cursor** the rest
 * of the time, which is the only "trip over" signal there is. BrightWay calls `notifyChange` on
 * the URI on every update, so this registers a [ContentObserver] rather than polling.
 *
 * It has to be a provider and not the shade, because BrightWay's own nav notification is
 * IMPORTANCE_LOW and ongoing *precisely so the face ignores it* -- the row here is the face's
 * one source, by agreement between the two apps.
 *
 * ### Lifecycle, and the one rule about the screen
 *
 * Started as the face goes up and stopped as it comes down, exactly like [LockMedia] and for the
 * same reason: an observer registered against the service's context would outlive every face
 * that registered it. And while the screen is off, an observer ping is *ignored* rather than
 * answered -- a subway trip updates for an hour against a panel nobody is looking at, and a
 * cross-process query per fix all night is the v2.10 all-night-poll bug in a new coat. The face
 * re-asks on every wake instead, which is the one moment staleness would show. `updatedAt` going
 * stale for minutes between fixes is normal underground and deliberately not treated as ended.
 *
 * ### Absent rather than broken
 *
 * Every failure -- BrightWay missing, the provider refusing, a column renamed -- answers null,
 * and null is a hidden row. No version coupling: a phone without BrightWay simply never has a
 * turn to show.
 */
class LockNav(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Told on the main thread whenever the answer changes. Null means not navigating. */
    var onChange: ((LockNavStep?) -> Unit)? = null

    var state: LockNavStep? = null
        private set

    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var observer: ContentObserver? = null

    /** Read on the worker and on main; flipped only by [start] and [stop], on main. */
    @Volatile
    private var watching = false

    /**
     * Begin watching. Idempotent, because the face is shown again on every screen-off.
     *
     * The queries run on their own thread: a provider in another process can be slow to spin up,
     * and this is called on the main thread behind the lock screen, where a stall reads as the
     * phone freezing.
     */
    fun start() {
        if (watching) return
        watching = true
        val t = HandlerThread("lock-nav").apply { start() }
        thread = t
        val w = Handler(t.looper)
        worker = w
        val obs = object : ContentObserver(w) {
            override fun onChange(selfChange: Boolean) {
                // A ping against a dark panel is work for nobody. The wake re-asks.
                if (!interactive()) return
                read()
            }
        }
        observer = obs
        runCatching { context.contentResolver.registerContentObserver(URI, false, obs) }
        // The one initial query on show, unconditional: the face goes up as the screen goes off,
        // and what it holds has to be right the moment the panel next lights.
        w.post { read() }
    }

    /** Stop watching. The observer has to come off with the face; see the class comment. */
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

    /**
     * Ask again now. Called as the panel lights: `notifyChange` is a courtesy, not the contract,
     * and the moment somebody is looking is the one moment the row must not be stale.
     */
    fun requery() {
        worker?.post { read() }
    }

    private fun interactive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(true)

    private fun read() {
        val next = query()
        main.post {
            // A read that was in flight when the face came down must not resurrect the state or
            // tell a listener that is no longer drawing anything.
            if (!watching) return@post
            if (next == state) return@post
            state = next
            runCatching { onChange?.invoke(next) }
        }
    }

    private fun query(): LockNavStep? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            // Empty cursor: not navigating. The only trip-over signal the contract has.
            if (!c.moveToFirst()) return@use null
            val instruction = text(c, "instruction") ?: return@use null
            LockNavStep(
                instruction = instruction,
                distanceM = int(c, "distanceM") ?: 0,
                etaMinutes = int(c, "etaMinutes") ?: 0,
                mode = text(c, "mode").orEmpty(),
                stepIndex = int(c, "stepIndex") ?: 0,
                stepCount = int(c, "stepCount") ?: 0,
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

    companion object {
        /** BrightWay's row. `lineColor` is deliberately not read: this face is greyscale. */
        val URI: Uri = Uri.parse("content://com.gios.brightway.nav/current")
    }
}

/**
 * The numbers under the instruction, written the way an American street sign says them.
 *
 * Its own object so it can be tested without a phone -- the metres arrive metric off Google's
 * route and the face speaks feet and miles, and a conversion is exactly the kind of arithmetic
 * that ships off by a factor of ten when nothing pins it.
 */
object NavText {

    /** Metres to feet under about a fifth of a mile, then miles. Feet round to tens. */
    fun distance(metres: Int): String {
        val feet = (metres.coerceAtLeast(0) * 3.28084).roundToInt()
        val tens = (feet + 5) / 10 * 10
        if (tens < 1000) return "$tens FT"
        val miles = metres / 1609.344
        return if (miles < 9.95) {
            String.format(Locale.US, "%.1f MI", miles)
        } else {
            "${miles.roundToInt()} MI"
        }
    }

    /**
     * Minutes left in the whole trip. The contract says never 0 while anything remains, and the
     * floor here says the same thing from this side: a trip that exists takes at least a minute.
     */
    fun eta(minutes: Int): String {
        val m = minutes.coerceAtLeast(1)
        if (m < 60) return "$m MIN"
        val h = m / 60
        val rest = m % 60
        return if (rest == 0) "$h HR" else "$h HR $rest MIN"
    }

    /** "450 FT · 12 MIN · 3/8". The step counter is one-based here because people are. */
    fun secondary(distanceM: Int, etaMinutes: Int, stepIndex: Int, stepCount: Int): String {
        val step = if (stepCount > 0) " · ${stepIndex + 1}/$stepCount" else ""
        return "${distance(distanceM)} · ${eta(etaMinutes)}$step"
    }
}
