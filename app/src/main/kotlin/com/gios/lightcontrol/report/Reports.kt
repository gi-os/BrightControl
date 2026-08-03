package com.gios.lightcontrol.report

import android.content.Context
import android.os.Build
import com.gios.lightcontrol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What went wrong, in the order the chips appear.
 *
 * Two labels because the two readers are different: `chip` has to fit a third of a 3.92" screen,
 * `label` is read weeks later in an issue title with no phone in front of you.
 */
enum class Symptom(val chip: String, val label: String, val slug: String) {
    Crashed("CLOSED", "It closed itself", "crash"),
    Froze("FROZE", "It stopped responding", "freeze"),
    Wrong("LOOKS OFF", "Something looks wrong", "render"),
    Slow("SLOW", "It was very slow", "slow"),
    Other("OTHER", "Something else", "other"),
}

/** A report on its way out: exactly the three fields the issues API wants. */
data class Report(val title: String, val body: String, val labels: List<String>)

/**
 * Shake-to-report, from the phone to a GitHub issue.
 *
 * Reports queue on disk first and are posted afterwards, always — not as a fallback for being
 * offline. A phone that reports a freeze is by definition a phone that was just misbehaving, and
 * a report that exists only in flight is the one report guaranteed to be lost. The queue is also
 * why the send button can close the sheet immediately: nothing the user sees depends on a socket.
 *
 * Ported from LightCamera, with one deliberate change: the HTTP call is `HttpURLConnection`
 * rather than OkHttp. Most of the apps this module is going into do not otherwise need a
 * networking library, and a bug reporter that drags 800KB of OkHttp into a tip calculator is a
 * bug reporter that does not get installed. Two requests an hour at most does not need a
 * connection pool.
 */
object Reports {

    private const val DIR = "reports"
    private const val MAX_QUEUED = 20
    private const val TIMEOUT_MS = 45_000

    /** True when this build can actually send. False means reports pile up in the queue. */
    fun canSend(): Boolean = BuildConfig.REPORT_TOKEN.isNotBlank()

    fun pendingCount(context: Context): Int = queued(context).size

    /** Turn what the sheet collected into an issue body. */
    fun compose(
        context: Context,
        symptom: Symptom,
        note: String,
        screen: String,
        crash: String?,
        failure: Failure? = null,
    ): Report {
        val version = versionName(context)
        val trimmed = note.trim()
        // The note is the headline when there is one. A title reading "Something else" tells
        // you nothing three weeks later; "standings empty for the WNBA" is the whole report.
        val headline = trimmed.takeIf { it.isNotEmpty() }?.let { first(it) } ?: symptom.label
        val body = buildString {
            appendLine("### What happened")
            appendLine()
            appendLine(symptom.label + (trimmed.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""))
            appendLine()
            if (failure != null) {
                appendLine("### What the app itself reported")
                appendLine()
                appendLine("Could not ${failure.what}.")
                if (!failure.detail.isNullOrBlank()) {
                    appendLine()
                    appendLine("```")
                    appendLine(failure.detail)
                    appendLine("```")
                }
                appendLine()
            }
            appendLine("### Where")
            appendLine()
            appendLine("On the `$screen` screen.")
            appendLine()
            appendLine("### Build")
            appendLine()
            appendLine("| | |")
            appendLine("|-|-|")
            appendLine("| App | ${ReportApp.NAME} $version |")
            appendLine("| Package | ${context.packageName} |")
            appendLine("| Android | ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) |")
            appendLine("| Device | ${Build.MANUFACTURER} ${Build.MODEL} |")
            appendLine("| Firmware | ${Build.DISPLAY} |")
            appendLine("| Reported | ${stamp()} |")
            appendLine("| Free space | ${megabytes(context.filesDir.freeSpace)} |")
            appendLine("| Heap | ${megabytes(usedHeap())} of ${megabytes(Runtime.getRuntime().maxMemory())} |")
            appendLine()
            appendLine("### Last crash")
            appendLine()
            if (crash.isNullOrBlank()) {
                appendLine("None — the app did not die, so this is a glitch and not a stack trace.")
            } else {
                appendLine("```")
                appendLine(crash.take(6_000))
                appendLine("```")
            }
        }
        val labels = buildList {
            add(ReportApp.LABEL)
            // A stack trace outranks whatever chip was tapped: if the app died, that is what
            // this report is about, whatever the person picked from a list afterwards.
            add(if (!crash.isNullOrBlank()) "crash" else symptom.slug)
            // Worth separating: the app noticed this one on its own, so it is reproducible
            // from the detail rather than from somebody remembering what they were doing.
            if (failure != null) add("self-reported")
        }
        return Report(
            title = "${ReportApp.NAME} $version — $headline",
            body = body,
            labels = labels,
        )
    }

    /**
     * Write the report to disk, then try to send everything waiting.
     *
     * Queue first, always. See the class comment.
     */
    suspend fun submit(context: Context, report: Report): Unit = withContext(Dispatchers.IO) {
        enqueue(context, report)
        flush(context)
    }

    /** Post everything queued. Safe to call on launch; does nothing without a token. */
    suspend fun flush(context: Context): Unit = withContext(Dispatchers.IO) {
        if (!canSend()) return@withContext
        for (f in queued(context)) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: run { f.delete(); return@withContext }
            if (post(json)) f.delete() else return@withContext
        }
    }

    // ---------------------------------------------------------------- queue

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun queued(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun enqueue(context: Context, report: Report) {
        val d = dir(context)
        // A phone that has been failing offline for a week should not fill its own storage
        // with the evidence. Oldest goes first: the newest report is the one still relevant.
        val existing = queued(context)
        if (existing.size >= MAX_QUEUED) {
            existing.take(existing.size - MAX_QUEUED + 1).forEach { it.delete() }
        }
        val payload = JSONObject()
            .put("title", report.title)
            .put("body", report.body)
            .put("labels", JSONArray().apply { report.labels.forEach { put(it) } })
        runCatching {
            File(d, "${System.currentTimeMillis()}-${(0..999).random()}.json")
                .writeText(payload.toString())
        }
    }

    // ---------------------------------------------------------------- transport

    /** @return true when the issue was created, or when it never can be and should be dropped. */
    private fun post(payload: JSONObject): Boolean {
        val url = URL("https://api.github.com/repos/${BuildConfig.REPORT_REPO}/issues")
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORT_TOKEN}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", ReportApp.NAME)
            }
            conn!!.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn!!.responseCode
            // 4xx that is not rate limiting will never succeed — a bad token, a deleted repo,
            // a label that no longer exists. Dropping it is better than retrying it forever on
            // every launch.
            code in 200..299 || (code in 400..499 && code != 403 && code != 429)
        }.getOrDefault(false).also { runCatching { conn?.disconnect() } }
    }

    // ---------------------------------------------------------------- detail

    private fun first(text: String): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= 72) line else line.take(69).trimEnd() + "…"
    }

    /** Prefixed here rather than at every call site, and never blank — "v?" is still a fact. */
    private fun versionName(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.let { "v$it" } ?: "v?"

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun megabytes(bytes: Long): String = "${bytes / 1_048_576} MB"

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
