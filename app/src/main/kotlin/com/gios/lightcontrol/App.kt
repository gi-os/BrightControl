package com.gios.lightcontrol

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exists for one reason: to write down why the app died.
 *
 * On LightOS a sideloaded app that crashes on launch tells you nothing. There is no crash dialog
 * worth reading, no Play Console, and no adb attached at the moment it happens — so "it crashes
 * when I open it, every time" is the entire bug report, and the next step is a cable and a
 * reboot before the buffer clears.
 *
 * So the stack goes into SharedPreferences on the way out, and the settings screen shows it on
 * the next launch. The default handler is still called afterwards: this records the crash, it
 * does not swallow it. An app that keeps running after an uncaught exception is in a state
 * nobody reasoned about, which is worse than the crash.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped, because a handler that throws replaces a legible crash with a mysterious
            // one — and this runs while the process is already failing.
            runCatching { Prefs(this).recordCrash(describe(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun describe(thread: Thread, error: Throwable): String {
        val stamp = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date())
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return "$stamp · ${thread.name}\n$stack"
    }
}
