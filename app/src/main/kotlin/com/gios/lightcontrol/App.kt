package com.gios.lightcontrol

import android.app.Application
import com.gios.lightcontrol.report.CrashLog

/**
 * Exists for one reason: to write down why the app died, from the earliest moment there is.
 *
 * On LightOS a sideloaded app that crashes tells you nothing. There is no crash dialog worth
 * reading, no Play Console, and no adb attached at the moment it happens — so "it closed itself"
 * is the entire bug report, and the next step is a cable and a reboot before the buffer clears.
 *
 * ## Why this is the only handler now
 *
 * There used to be two. This class wrote the stack into SharedPreferences, and
 * [CrashLog] wrote it to a file — but `CrashLog.install` was called from `MainActivity.onCreate`,
 * which only runs when somebody opens the settings. So a crash in the accessibility service, on a
 * phone whose owner had not opened the settings since the last install, was recorded in one place
 * and read from another: the report sheet reads the file and printed "None — the app did not die"
 * over a report filed *because* the app had died. That is light-reports#12.
 *
 * `Application.onCreate` runs before anything else in the process, service included, so
 * installing here covers the case the old arrangement missed. The default handler is still called
 * afterwards: this records the crash, it does not swallow it. An app that keeps running after an
 * uncaught exception is in a state nobody reasoned about, which is worse than the crash.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Anything the old recorder caught and nothing has read yet. Carried over once, so
        // consolidating the two does not quietly discard a trace somebody is waiting to send.
        runCatching {
            val prefs = Prefs(this)
            prefs.lastCrash()?.takeIf { it.isNotBlank() }?.let { text ->
                CrashLog.adopt(this, text)
                prefs.clearCrash()
            }
        }
    }
}
