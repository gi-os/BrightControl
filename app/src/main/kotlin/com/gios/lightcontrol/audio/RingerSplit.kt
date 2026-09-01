package com.gios.lightcontrol.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockCallState

/**
 * A ring and a text message at two different volumes, on a phone that only has one.
 *
 * The rules are in [SplitDecision] and are tested without a phone in the room. This is the part
 * that has to talk to Android: where the call state comes from, where a volume change comes from,
 * and what happens when the process holding a raised ringer is killed.
 *
 * ### Where the ring comes from
 *
 * `lock.LockCall`, which this app already runs unconditionally for the lock face, and which is a
 * better source than anything this feature would have built for itself. It reads three things at
 * once — the telephony broadcast, the audio mode, and the call notification — because on LightOS no
 * one of them is reliable: the dialer posts no notification at all, `MODE_RINGTONE` does not arrive
 * for a call the ringer will not play out loud, and a withheld number used to make the broadcast
 * look empty. See `lock.CallerId` for that history. The first of the three to speak starts the ring
 * here.
 *
 * **Answered is not ringing.** The ringtone stops the moment a call is picked up, so `Active` puts
 * the level back rather than holding it for the length of the conversation. A notification arriving
 * mid-call is then at the notification level, which is the right answer and is free.
 *
 * ### The race, and why it is small
 *
 * The broadcast and the ringtone are started by different parts of the platform at about the same
 * moment, so the first fraction of a second of a ring can play at the old level. It cannot be
 * eliminated from an app: the earliest hook that exists is a `CallScreeningService`, which requires
 * being the phone's screening app, and LightOS's dialer is a system app that holds that role.
 *
 * It is also small, because `setStreamVolume` applies to a ringtone that is *already playing*. The
 * worst case is a ring that starts quiet and is at full level before the first repeat, which is
 * roughly what a ramping ringer does on purpose.
 *
 * ### The failure that matters
 *
 * A process killed between the boost and the restore leaves a phone loud for ever, with no screen
 * anywhere on LightOS that would show why. So the marker is in `SharedPreferences` and not in a
 * field, [start] asks the question again on every bind, and there is a three-minute unwind for a
 * call whose end nothing reported.
 */
class RingerSplit(
    context: Context,
    private val prefs: Prefs,
    private val log: (String) -> Unit,
) {

    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** The last thing this app wrote, so its own echo is not mistaken for a person. */
    private var wrote = -1
    private var wroteAt = 0L

    @Volatile
    private var ringing = false

    /**
     * A ring that nothing ever ended.
     *
     * `lock.LockCall` expires a telephony-only ring after two minutes and publishes no call, which
     * covers the case this is for. This is the layer under that: if even the publish is missed, a
     * raised ringer comes back down on its own rather than staying up until somebody notices.
     */
    private val unwind = Runnable {
        if (prefs.splitBoosted) {
            ringing = false
            apply("nothing ended the call")
        }
    }

    private val volumes = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A receiver that throws takes the process with it, and this process is a key filter.
            runCatching {
                when (intent?.action) {
                    VOLUME_CHANGED -> {
                        val stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1)
                        // Both, because the alias means a ring change is broadcast twice — once
                        // under each name — and either copy may arrive first.
                        if (stream != AudioManager.STREAM_RING &&
                            stream != AudioManager.STREAM_NOTIFICATION
                        ) {
                            return
                        }
                        val value = intent.getIntExtra(EXTRA_STREAM_VALUE, -1)
                        if (value < 0) return
                        onLevel(value)
                    }
                    // The ringer going to vibrate or silent, or coming back from it. Not a level
                    // change, but it changes whether this feature may write at all.
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> apply("ringer mode")
                }
            }
        }
    }

    fun start() {
        runCatching {
            ContextCompat.registerReceiver(
                app,
                volumes,
                IntentFilter(VOLUME_CHANGED).apply {
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        // The bind is the moment to ask whether a previous process left this phone loud. It is also
        // a boot and an update, and both of those are moments a marker can outlive the level it was
        // taken for -- see the class note.
        if (prefs.splitBoosted) log("ringer split · was holding a boost across a restart")
        ringing = false
        apply("start")
    }

    fun stop() {
        handler.removeCallbacks(unwind)
        runCatching { app.unregisterReceiver(volumes) }
    }

    /**
     * The call, as the lock face sees it. Null is no call.
     *
     * Called from `keys.ControlService.onCallChanged`, which is where every other consumer of the
     * call state already hangs, and which runs whether or not the lock face is switched on.
     */
    fun onCall(state: LockCallState?) {
        val next = state?.stage == LockCallState.Stage.Ringing
        if (next == ringing) return
        ringing = next
        handler.removeCallbacks(unwind)
        if (next) handler.postDelayed(unwind, STUCK_MS)
        apply(if (next) "ringing" else "call over")
    }

    /**
     * Somebody, or something, moved the ring level.
     *
     * The whole of the settings for this feature: the two numbers are learned from the hardware
     * keys rather than typed in. See [SplitDecision.learn] for which of them a given press means.
     */
    private fun onLevel(value: Int) {
        val now = SystemClock.elapsedRealtime()
        // Our own write, come back round. Not a person, and not a number to learn from.
        if (value == wrote && now - wroteAt < ECHO_MS) return
        val state = state() ?: return
        val learned = SplitDecision.learn(state, value) ?: return
        if (learned.ring) {
            prefs.splitRingLevel = learned.level
            log("ringer split · learned ring ${learned.level}")
        } else {
            prefs.splitNotifyLevel = learned.level
            log("ringer split · learned notifications ${learned.level}")
        }
        prefs.splitLast = "ring ${prefs.splitRingLevel} · everything else ${prefs.splitNotifyLevel}"
    }

    /**
     * Read the phone, decide, and write at most one number.
     *
     * Public because the settings screen calls it: switching the mode should take effect on the
     * phone in your hand rather than at the next call.
     */
    fun apply(reason: String) {
        runCatching {
            val state = state() ?: return
            when (val move = SplitDecision.decide(state)) {
                SplitDecision.Move.Leave -> Unit

                is SplitDecision.Move.Hold -> {
                    prefs.splitBoosted = true
                    log("ringer split · ${move.why} ($reason)")
                }

                is SplitDecision.Move.Release -> {
                    prefs.splitBoosted = false
                    handler.removeCallbacks(unwind)
                    log("ringer split · let go — ${move.why} ($reason)")
                }

                is SplitDecision.Move.Write -> {
                    val ok = write(move.level)
                    prefs.splitBoosted = move.hold && ok
                    if (!move.hold) handler.removeCallbacks(unwind)
                    log("ringer split · ${move.why} ($reason)" + if (ok) "" else " · REFUSED")
                }
            }
        }
    }

    /** Everything the decision is made from, or null when there is no audio service to ask. */
    private fun state(): SplitDecision.State? {
        val audio = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull()
            ?: return null
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_RING) }.getOrDefault(0)
        val current = runCatching { audio.getStreamVolume(AudioManager.STREAM_RING) }.getOrDefault(0)
        return SplitDecision.State(
            mode = prefs.splitMode,
            ringing = ringing,
            holdingBoost = prefs.splitBoosted,
            ringLevel = prefs.splitRingLevel,
            notifyLevel = prefs.splitNotifyLevel,
            current = current,
            max = max,
            ringerSilent = runCatching {
                audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
            }.getOrDefault(false),
            // The claim, not the ringer mode: `audio.WifiRinger` writing silence for a network is
            // the case to stand down for, and it is the one thing that says so.
            wifiHolding = prefs.wifiRingerOn && prefs.wifiRingerSilencedFor.isNotBlank(),
        )
    }

    /**
     * Write the level, and read it back rather than trusting the call.
     *
     * The same discipline `audio.WifiRinger.setMode` uses and for the same reason: a marker taken
     * for a write that never landed is a level this app would later put "back" to a number the
     * phone was never at.
     */
    private fun write(level: Int): Boolean = runCatching {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        wrote = level
        wroteAt = SystemClock.elapsedRealtime()
        // No flags. A ring being made louder must not also produce the platform's own volume
        // panel over the top of the incoming-call screen, and this app draws its own strip.
        audio.setStreamVolume(AudioManager.STREAM_RING, level, 0)
        audio.getStreamVolume(AudioManager.STREAM_RING) == level
    }.getOrDefault(false)

    /**
     * Fill the two levels in for a phone that has never had them.
     *
     * Called once, when the mode is first set to two levels. Both are zero until then and the rules
     * refuse to act on two equal levels, so without this the feature would appear to do nothing
     * until a person had thought to press the volume keys twice in two different situations.
     *
     * The guesses: the ring goes to the top, because somebody switching this on wants to hear calls;
     * everything else stays exactly where they had it, because that is the level they have been
     * living with and it is the half of the pair they were complaining about. Both are only a
     * starting point — the next press of a volume key overwrites whichever one is in front of them.
     */
    fun seed() {
        val max = maxLevel()
        if (max <= 0) return
        if (prefs.splitRingLevel <= 0) prefs.splitRingLevel = max
        if (prefs.splitNotifyLevel <= 0) {
            val now = level()
            prefs.splitNotifyLevel = if (now in 1 until max) now else (max / 3).coerceAtLeast(1)
        }
        prefs.splitLast = "ring ${prefs.splitRingLevel} · everything else ${prefs.splitNotifyLevel}"
    }

    /** What the settings screen shows: the live level, so the two numbers can be checked. */
    fun level(): Int = runCatching {
        app.getSystemService(AudioManager::class.java)
            ?.getStreamVolume(AudioManager.STREAM_RING) ?: 0
    }.getOrDefault(0)

    fun maxLevel(): Int = runCatching {
        app.getSystemService(AudioManager::class.java)
            ?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 0
    }.getOrDefault(0)

    /** Whether the phone is on vibrate or silent, which stands this whole feature down. */
    fun ringerDown(): Boolean = runCatching {
        app.getSystemService(AudioManager::class.java)
            ?.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }.getOrDefault(false)

    private companion object {
        /**
         * `AudioManager.VOLUME_CHANGED_ACTION` and its extras, spelled out because they are
         * `@hide`. The broadcast itself is an ordinary protected system broadcast and a registered
         * receiver gets it — the same route `keys.VolumeWatcher` has used since v1.8.
         */
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"

        /** How long a broadcast may still be an echo of this app's own write. */
        const val ECHO_MS = 1_500L

        /** A ring nothing ended. Longer than any ringtone, shorter than a forgotten evening. */
        const val STUCK_MS = 180_000L
    }
}
