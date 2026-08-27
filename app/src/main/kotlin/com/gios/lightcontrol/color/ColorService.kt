package com.gios.lightcontrol.color

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.gios.lightcontrol.IColorProvider
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.keys.ControlService
import com.gios.lightcontrol.keys.Grants

/**
 * The way in for another app that wants colour.
 *
 * ## Exported, and why that is safe
 *
 * The same argument as `adb/GrantRequest.kt`, one floor down. That file holds a shell and refuses
 * to run a single character of what it is sent; this one holds `WRITE_SECURE_SETTINGS` and accepts
 * exactly two numbers, neither of which can name anything.
 *
 * Three properties do the work, and they are worth stating together because each one on its own
 * would look thin:
 *
 *  1. **The caller is identified by the kernel.** `Binder.getCallingUid` is not something the
 *     request carries, cannot be spoofed, and is resolved to a package by the package manager. A
 *     request from `com.gios.roll` is recorded against `com.gios.roll` and there is no field in
 *     which it could claim to be anything else.
 *  2. **A request is only honoured while its sender is the app in front.** Rules are read by
 *     [com.gios.lightcontrol.keys.ColorMode.applyFor], which is only ever called for the
 *     foreground package. So the worst a request can do is repaint a screen the caller is already
 *     occupying — which is a screen it was drawing anyway.
 *  3. **The vocabulary is three states.** Colour, mono, or nothing. Not a setting name, not a
 *     value, not a package. There is no version of this call that reaches any other setting on the
 *     phone, and an unrecognised state is read as "nothing".
 *
 * What is left is an app being able to make its own screen colour without a privileged permission
 * of its own. That is the feature.
 *
 * ## Its own service, not a method on ControlService
 *
 * [com.gios.lightcontrol.keys.ControlService] is an accessibility service: the system binds it,
 * and a system-bound service cannot also take arbitrary binds. More to the point, that service
 * filters every key on this phone, and an interface other apps can reach must not be able to take
 * it down — an accessibility service that throws often enough is stood down by the framework, and
 * standing down the key filter to fix a colour request would be a poor trade.
 */
class ColorService : Service() {

    private val binder = object : IColorProvider.Stub() {

        override fun protocol(): Int = ColorRequests.PROTOCOL

        override fun want(state: Int, token: IBinder?): Int {
            val holder = token ?: return ColorRequests.REFUSED
            val pkg = callerPackage() ?: return ColorRequests.REFUSED
            ColorRequests.set(holder, pkg, ColorRequests.ruleOf(state))
            return outcome()
        }

        /**
         * Which package is calling, or null if that cannot be answered without guessing.
         *
         * A uid with more than one package is a shared user id, and there is no honest way to pick
         * one: the whole gate is "this caller is the app in front", and an answer chosen from a
         * list is not that caller, it is a coin toss that happens to be spelled like a package
         * name. Refused instead — the caller falls back to its own writer, which is where it was
         * before this existed.
         */
        private fun callerPackage(): String? = runCatching {
            packageManager.getPackagesForUid(Binder.getCallingUid())?.singleOrNull()
        }.getOrNull()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Whether this app can act on what it was just told, which is the answer the caller needs
     * rather than an acknowledgement it was heard.
     *
     * Three things all have to be true and any one of them can be false on a phone that looks set
     * up: the grant, the user's master switch, and a bound service to do the writing. Reporting
     * [ColorRequests.INERT] is what sends an app that still holds its own grant back to its own
     * writer — and because the request itself is kept either way, switching the feature on later
     * makes every app already asking correct without any of them being asked again.
     */
    private fun outcome(): Int {
        val prefs = Prefs(this)
        val able = Grants.canWriteSecureSettings(this) &&
            prefs.colorAutoSwitch &&
            ControlService.bound
        return if (able) ColorRequests.SERVING else ColorRequests.INERT
    }
}
