package com.gios.lightcontrol.lock

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager

/** A call, as the lock face draws it. */
data class LockCallState(
    val stage: Stage,
    val who: String,
    val sub: String,
    /** Uptime the stage began, for the in-call timer. */
    val since: Long,
) {
    enum class Stage { Ringing, Active }
}

/**
 * Whether the phone is on a call, who with, and how to answer or hang up.
 *
 * ### Why the lock face needs this at all
 *
 * The face is a `TYPE_ACCESSIBILITY_OVERLAY` at layer 31 (see [LockOverlay] for the table), which
 * is above every activity on the phone — including the one the dialer raises with a full-screen
 * intent when a call arrives. So with the face on, a ringing phone showed the clock: LightOS's
 * incoming-call screen was underneath, painted, correct, and unreachable. It is the same trap the
 * camera button hit in v2.12, and the same shape of answer applies — either draw the thing
 * yourself, or get out of the way. This draws the ring and gets out of the way for the call.
 *
 * ### Two sources, because neither is enough on its own
 *
 * The **notification** carries the name, the number and the buttons, and needs no grant this face
 * does not already have. The **audio mode** carries the truth about what the radio is doing:
 * `MODE_RINGTONE`, then `MODE_IN_CALL`. A call answered on a headset changes the mode a beat
 * before the notification is rebuilt, and a dialer that posts nothing at all — or a listener grant
 * that was never given — leaves the mode as the only thing that knows a call is happening.
 *
 * So: the notification decides *who*, the mode decides *what stage*, and either one arriving is
 * enough to start looking.
 */
class LockCall(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Who is calling, from telephony, for the case the notification never covers.
     *
     * The second source for *who* -- the shade was the first and on this phone it is often not
     * there at all, because LightOS's dialer shows its own activity without posting anything. See
     * [CallerId].
     */
    private val callerId = CallerId(context)

    /** Told on the main thread whenever the answer changes. Null means no call. */
    var onChange: ((LockCallState?) -> Unit)? = null

    var state: LockCallState? = null
        private set

    /**
     * Told on every look while a call is up, changed or not.
     *
     * The speaker boost hangs off this. Route changes -- somebody pressing speaker on the dialer's
     * own screen, a headset going in -- are not a change to *who is calling*, so they never reach
     * [onChange], and they are exactly the moments the level has to be re-asserted.
     */
    var onTick: (() -> Unit)? = null

    private var listening = false
    private var polling = false
    private var stageSince = 0L

    private val modeListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { evaluate() }
        } else {
            null
        }

    /**
     * A one-second tick, and only while a call is up.
     *
     * The v2.10 lesson, applied before it could be repeated: a poll that runs whenever the service
     * does is a poll that runs all night. This one starts when something says there may be a call
     * and stops the moment there is not, so its whole life is the length of a phone call. It exists
     * because `OnModeChangedListener` is API 31 and because the transition that matters most —
     * ringing to answered — can otherwise arrive only as a notification rebuild.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            evaluate()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (listening) return
        listening = true
        LockCalls.onChange = { handler.post { evaluate() } }
        callerId.onChange = { handler.post { evaluate() } }
        callerId.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.addOnModeChangedListener(
                    context.mainExecutor,
                    modeListener as AudioManager.OnModeChangedListener,
                )
            }
        }
        evaluate()
    }

    fun stop() {
        if (!listening) return
        listening = false
        stopPolling()
        LockCalls.onChange = null
        callerId.onChange = null
        callerId.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(
                    modeListener as AudioManager.OnModeChangedListener,
                )
            }
        }
    }

    /**
     * Answer, by whichever route this phone gives us.
     *
     * The notification's own action first: it is the dialer's button, it does exactly what
     * pressing it in the shade does, and it needs nothing granted. `TelecomManager` second, for a
     * dialer whose button could not be identified by its text — that one needs
     * `ANSWER_PHONE_CALLS`, which the ADB screen grants in a line.
     */
    fun answer(): Boolean {
        val note = LockCalls.current
        if (send(note?.answer)) return true
        if (!granted(android.Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            context.getSystemService(TelecomManager::class.java)?.acceptRingingCall()
            true
        }.getOrDefault(false)
    }

    /** Decline a ringing call, or end one in progress. Same two routes as [answer]. */
    fun decline(): Boolean {
        val note = LockCalls.current
        if (send(note?.decline)) return true
        if (!granted(android.Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.endCall()
            true
        }.getOrDefault(false)
    }

    /** Whichever app the phone rings through, for the launch route and for the log. */
    fun dialerPackage(): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    /**
     * What was known about this call, in one line, for the diagnostics log.
     *
     * Written because the last two attempts at this were both fixed in the wrong place: the card
     * was blank and the reason -- no notification at all, rather than a notification with no name
     * -- was not visible from anywhere. A shake-to-report now carries the answer.
     */
    fun evidence(): String {
        val note = LockCalls.current
        return buildString {
            append("note=").append(if (note == null) "none" else note.pkg.substringAfterLast('.'))
            append(" who=").append(if (note?.who.isNullOrBlank()) "-" else "yes")
            append(" fsi=").append(if (note?.fullScreen != null) "yes" else "-")
            append(" content=").append(if (note?.content != null) "yes" else "-")
            append(" tel=").append(if (callerId.granted()) "ok" else "NO GRANT")
            append(" name=").append(if (callerId.name != null) "yes" else "-")
            append(" number=").append(if (callerId.number != null) "yes" else "-")
            append(" dialer=").append(dialerPackage()?.substringAfterLast('.') ?: "none")
        }
    }

    /**
     * Put the dialer's own call screen in front, now.
     *
     * The missing half of standing down. Hiding the face uncovers whatever is behind it, and what
     * is behind it is not reliably the call: the dialer fired its full-screen intent while a window
     * at layer 31 was over the top, and by the time the call is answered that activity may have
     * been stopped, or never resumed, or replaced by the keyguard. Uncovering nothing and calling it
     * a hand-off is how a call ends up connected with no screen for it -- no mute, no keypad, no
     * hang up, on a phone that looks idle.
     *
     * The **full-screen intent** is the one the dialer built for exactly this -- a call taking over
     * a phone that was not being looked at -- and it is the only one that comes up over a keyguard
     * by right. The **content intent** is the same screen reached the ordinary way, which is what
     * an ongoing call carries once the ring is over.
     *
     * `TelecomManager.showInCallScreen` is asked underneath both, and **does not count as a
     * route**. It returns nothing and reports nothing: it is a request passed to the dialer's
     * in-call service, and on a phone where that request goes unanswered a method that always
     * "succeeded" is how the last fix came to stop at a screen that never appeared. So it is asked
     * and then not believed, and this returns null, which is the caller's cue to go and fetch the
     * dialer itself.
     *
     * @return the route that definitely fired, or null if only the unverifiable one did.
     */
    fun openCallScreen(): String? {
        val note = LockCalls.current
        if (send(note?.fullScreen)) return "full-screen intent"
        if (send(note?.content)) return "content intent"
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        }
        return null
    }

    private fun send(intent: android.app.PendingIntent?): Boolean =
        intent != null && runCatching { intent.send(); true }.getOrDefault(false)

    private fun granted(permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** The current answer, from both sources, published if it changed. */
    private fun evaluate() {
        val note = LockCalls.current
        val audio = runCatching { context.getSystemService(AudioManager::class.java) }.getOrNull()
        val mode = runCatching { audio?.mode }.getOrNull() ?: AudioManager.MODE_NORMAL
        val ringingMode = mode == AudioManager.MODE_RINGTONE
        val activeMode = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

        val stage = when {
            // The mode is the stronger signal for *answered*, and the notification for *ringing*:
            // a dialer rebuilds its notification a moment after the mode moves, and drawing
            // ANSWER over a call already in progress is worse than being a second late.
            activeMode -> LockCallState.Stage.Active
            ringingMode -> LockCallState.Stage.Ringing
            note != null && note.incoming -> LockCallState.Stage.Ringing
            note != null -> LockCallState.Stage.Active
            else -> null
        }

        if (stage == null) {
            stopPolling()
            callerId.forget()
            publish(null)
            return
        }
        startPolling()
        if (state?.stage != stage) stageSince = SystemClock.elapsedRealtime()
        val lines = CallerText.of(
            noteWho = note?.who,
            noteText = note?.text,
            name = callerId.name,
            number = callerId.number,
            ringing = stage == LockCallState.Stage.Ringing,
        )
        publish(
            LockCallState(
                stage = stage,
                who = lines.who,
                sub = lines.sub,
                since = stageSince,
            ),
        )
        runCatching { onTick?.invoke() }
    }

    private fun publish(next: LockCallState?) {
        if (next == state) return
        state = next
        runCatching { onChange?.invoke(next) }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.postDelayed(tick, POLL_MS)
    }

    private fun stopPolling() {
        if (!polling) return
        polling = false
        handler.removeCallbacks(tick)
    }

    private companion object {
        /** Only ever runs during a call. See [tick]. */
        const val POLL_MS = 1_000L
    }
}
