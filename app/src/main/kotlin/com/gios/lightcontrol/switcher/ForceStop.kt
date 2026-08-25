package com.gios.lightcontrol.switcher

import android.app.ActivityManager
import android.content.Context
import com.gios.lightcontrol.adb.AdbManager

/**
 * Stopping another app, on a phone where that is normally a privileged act.
 *
 * Two ways in, and which one is available decides how much actually happens:
 *
 *  - **`am force-stop`, over this app's own adb shell.** The real thing — the same command the
 *    Settings app's Force stop button runs, which kills every process of the package, cancels its
 *    alarms and jobs, and drops it out of the task list. Available whenever wireless debugging
 *    has been paired with this app (see [AdbManager]), which on this phone is a setup people have
 *    usually already done for the other grants.
 *  - **`killBackgroundProcesses`.** The unprivileged fallback, and its permission is a *normal*
 *    one, granted at install with nothing to ask for. It is genuinely weaker: it kills background
 *    processes and leaves anything the system considers foreground alone, and it does not touch
 *    scheduled work. For an app sitting in the switcher rather than on screen, it is usually
 *    indistinguishable from the real thing.
 *
 * Both are attempted, cheap one first, and the caller is told which succeeded so the screen can
 * say "Stopped" or "Backgrounded" rather than claiming more than happened. Saying the wrong one is
 * worse than doing the weaker thing: somebody who force-stops an app to make it stop misbehaving
 * needs to know whether it was actually stopped.
 *
 * **Never this app itself.** Handled by the caller — the switcher does not list us — but worth
 * being clear about, because force-stopping the process that holds the accessibility service means
 * killing the phone's home button to close a list.
 */
object ForceStop {

    /** What happened. [Stopped] is the real force-stop; [Backgrounded] is the fallback. */
    enum class Result { Stopped, Backgrounded, Failed }

    /**
     * Blocking. Call it off the main thread — the adb path opens a socket and waits for a command
     * to exit, and this runs from an accessibility service whose main thread is where key events
     * are dispatched.
     */
    fun stop(context: Context, pkg: String): Result {
        if (pkg == context.packageName) return Result.Failed
        val backgrounded = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(pkg)
            true
        }.getOrDefault(false)
        val forced = runCatching {
            if (!AdbManager.ensureAlive(context)) return@runCatching false
            val out = AdbManager.getInstance(context).runCommand("am force-stop $pkg")
            // `am force-stop` prints nothing on success. Anything it does print is a refusal —
            // an unknown package, a permission problem, a dead socket — and treating silence as
            // the only success is what keeps this from reporting a stop that did not happen.
            out.isBlank()
        }.getOrDefault(false)
        return when {
            forced -> Result.Stopped
            backgrounded -> Result.Backgrounded
            else -> Result.Failed
        }
    }
}
