package com.gios.lightcontrol.keys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * Noticing that the volume changed, so [VolumeHud] has something to show.
 *
 * Two ways in, because neither alone is enough.
 *
 * **The broadcast is the main one.** `android.media.VOLUME_CHANGED_ACTION` is sent by
 * `AudioService` whenever any stream moves, and it carries the stream and the new value with it —
 * so this catches the volume keys, a media app's own slider, a headset's buttons, and a Bluetooth
 * device turning itself down on connect. It is not in the public SDK, which means the *constant*
 * isn't; the broadcast is an ordinary protected system broadcast and a registered receiver gets
 * it. Nothing is assumed about the extras: a missing one falls back to reading the stream.
 *
 * **The key press is the fallback**, for the case the broadcast never arrives — a build that
 * stopped sending it, or a stream this misses. A volume key is read back a moment later rather
 * than acted on, because reading at the DOWN gives the *old* value: the system has not applied
 * the press yet. Two reads, close together, so a slow apply still shows the right number.
 *
 * Note what this never does: adjust anything. Volume already works on this phone; the only thing
 * missing is being told. See [VolumeHud].
 */
class VolumeWatcher(
    private val context: Context,
    private val hud: VolumeHud,
    /** The package in front, so LightOS's own screens can be left to their own volume UI. */
    private val front: () -> String?,
    /** Whether the HUD is wanted at all right now — the master switch and its own setting. */
    private val wanted: () -> Boolean,
    /** Whether a tap on the strip may pin a stream. See [onHudTap]. */
    private val pinningAllowed: () -> Boolean,
) {

    private val handler = Handler(Looper.getMainLooper())

    /** What was last shown, so a stale fallback read can't overwrite a fresher broadcast. */
    private var lastAt = 0L
    private var lastStream = -1
    private var lastLevel = -1

    /** The stream a tap chose, and when that choice runs out. See [onHudTap]. */
    private var pinnedStream: Int? = null
    private var pinnedUntil = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A receiver that throws takes the process with it, and this process is a key filter.
            runCatching {
                when (intent?.action) {
                    VOLUME_CHANGED -> {
                        val stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1)
                        if (stream < 0) return
                        val value = intent.getIntExtra(EXTRA_STREAM_VALUE, -1)
                        val prev = intent.getIntExtra(EXTRA_PREV_STREAM_VALUE, Int.MIN_VALUE)
                        // Streams the user is not adjusting also move: a notification volume
                        // mirrors the ringer, and every one of those would flash the HUD twice.
                        // Only a change is news.
                        if (prev != Int.MIN_VALUE && prev == value) return
                        present(stream, null, value)
                    }
                    // The ringer switching to vibrate or silent is a volume change with no number
                    // in it, and it is exactly the state you cannot hear.
                    AudioManager.RINGER_MODE_CHANGED_ACTION ->
                        present(AudioManager.STREAM_RING, null, -1)
                }
            }
        }
    }

    fun start() {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(VOLUME_CHANGED).apply {
                    addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    fun stop() {
        clearPin()
        handler.removeCallbacksAndMessages(null)
        runCatching { context.unregisterReceiver(receiver) }
        hud.dismiss()
    }

    /**
     * A volume key just arrived. Read the level back shortly afterwards and show it.
     *
     * Called from `onKeyEvent` *before* every guard in the service, and it must never influence
     * what that method returns — the key is not ours and the HUD is cosmetic. Hence the two reads
     * being posted rather than done here, and the whole of it inside a catch.
     */
    fun onVolumeKey() {
        if (!wanted()) return
        runCatching {
            handler.postDelayed({ readAndShow() }, FIRST_READ_MS)
            handler.postDelayed({ readAndShow() }, SECOND_READ_MS)
        }
    }

    private fun readAndShow() {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            present(activeStream(audio), null, -1)
        }
    }

    /**
     * Show one stream, reading whatever the broadcast didn't say.
     *
     * Nothing is shown while LightOS is in front. Its dashboard and lock screen have a volume
     * control of their own, and putting a second one over the top of it would be this app's oldest
     * mistake in a new place: on Light's own screens, anything added is something duplicated.
     */
    private fun present(stream: Int, note: String?, valueFromBroadcast: Int, force: Boolean = false) {
        if (!wanted()) return
        val app = front()
        if (app != null && app.startsWith(LIGHTOS)) {
            // And a pin cannot outlive the screen it was made on, or the keys would still be being
            // taken over on a screen showing LightOS's own slider.
            clearPin()
            hud.dismiss()
            return
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = runCatching { audio.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (max <= 0) return
        val level = if (valueFromBroadcast >= 0) {
            valueFromBroadcast
        } else {
            runCatching { audio.getStreamVolume(stream) }.getOrNull() ?: return
        }

        val now = SystemClock.uptimeMillis()
        // The same stream at the same level, twice inside a moment, is one change seen twice —
        // the broadcast and the fallback read both firing for one press.
        if (!force && stream == lastStream && level == lastLevel && now - lastAt < DEDUPE_MS) return
        lastStream = stream
        lastLevel = level
        lastAt = now

        // Vibrate and silent are the two levels with no number: on the ringer they are what the
        // volume *means*, and neither can be heard to check.
        val mode = runCatching { audio.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)
        val ringish = stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION
        val label = note ?: when {
            ringish && mode == AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            ringish && mode == AudioManager.RINGER_MODE_SILENT -> "SILENT"
            else -> null
        }
        hud.show(name(stream), level, max, label, pinned = pinnedStream == stream)
    }

    /**
     * The strip was tapped: move to the next stream and let the keys move *that* one.
     *
     * This is the only thing in this app that adjusts a volume, and it is worth being explicit
     * about why the rule bends here. Android gives the keys one stream at a time — whatever is
     * playing, and media when nothing is — so the ringer and alarm levels are unreachable from the
     * hardware on a phone with no volume UI to drag. LightOS has no screen for them either. Tapping
     * the strip is the way to reach them, and reaching them means the service has to take the press
     * and apply it itself.
     *
     * So the taking is fenced in: it needs an explicit tap first, it applies to *one* named stream,
     * and it expires with the strip. When [pinnedStream] is null — which is always, until you tap —
     * volume keys are untouched, exactly as they have always been. And a pin cannot survive a ring:
     * [takeKey] is only ever called from a path the service does not reach while anything is
     * ringing or a call is up, so the keys that dismiss an alarm are never in question.
     */
    fun onHudTap() {
        if (!wanted() || !pinningAllowed()) return
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            val from = pinnedStream ?: lastStream
            val next = cycle.filter { it != AudioManager.STREAM_VOICE_CALL || inCall(audio) }
            val stream = next[(next.indexOf(from) + 1).mod(next.size)]
            pinnedStream = stream
            pinnedUntil = SystemClock.uptimeMillis() + PIN_MS
            present(stream, null, -1, force = true)
        }
    }

    /**
     * A volume key while a stream is pinned: move that stream and swallow the press.
     *
     * Returns false for everything else, which is the normal case and the safe one — the key goes
     * where it always went. A press is consumed for its whole life once it is taken, DOWN, repeats
     * and UP, so no app is handed half of one.
     */
    fun takeKey(up: Boolean, event: KeyEvent): Boolean = runCatching {
        val stream = pinnedStream ?: return false
        if (SystemClock.uptimeMillis() > pinnedUntil) {
            clearPin()
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            // Flag 0: no system UI (there is none) and no beep. Crossing the ringer into silence
            // needs DND access, which a sideloaded app does not have, so that throws — and the
            // honest thing is to say so on the strip rather than look broken.
            val moved = runCatching { audio.adjustStreamVolume(stream, direction, 0) }.isSuccess
            pinnedUntil = SystemClock.uptimeMillis() + PIN_MS
            if (moved) {
                present(stream, null, -1, force = true)
            } else {
                present(stream, "NEEDS DND ACCESS", -1, force = true)
            }
        }
        true
    }.getOrDefault(false)

    private fun clearPin() {
        pinnedStream = null
        pinnedUntil = 0L
    }

    private fun inCall(audio: AudioManager): Boolean =
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION

    /**
     * Which stream a volume key is moving right now.
     *
     * Only needed for the fallback path — the broadcast says which one it was. The order is the
     * order the platform itself resolves in: something actually playing wins, then the audio mode,
     * and media is the answer when nothing is going on, because that is what the keys default to.
     */
    private fun activeStream(audio: AudioManager): Int {
        val usages = runCatching {
            audio.activePlaybackConfigurations.map { it.audioAttributes.usage }
        }.getOrDefault(emptyList())
        return when {
            AudioAttributes.USAGE_ALARM in usages -> AudioManager.STREAM_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE in usages -> AudioManager.STREAM_RING
            audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            audio.mode == AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
            runCatching { audio.isMusicActive }.getOrDefault(false) -> AudioManager.STREAM_MUSIC
            else -> AudioManager.STREAM_MUSIC
        }
    }

    private fun name(stream: Int): String = when (stream) {
        AudioManager.STREAM_MUSIC -> "MEDIA"
        AudioManager.STREAM_RING -> "RING"
        AudioManager.STREAM_NOTIFICATION -> "NOTIFICATIONS"
        AudioManager.STREAM_ALARM -> "ALARM"
        AudioManager.STREAM_VOICE_CALL -> "CALL"
        AudioManager.STREAM_SYSTEM -> "SYSTEM"
        AudioManager.STREAM_DTMF -> "TONES"
        AudioManager.STREAM_ACCESSIBILITY -> "SPEECH"
        else -> "VOLUME"
    }

    private companion object {
        /**
         * `AudioManager.VOLUME_CHANGED_ACTION` and its extras, spelled out because they are
         * `@hide` — the strings are the platform's and have not changed since Android 2.
         */
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        /** When to read the level back after a key press. The press has to land first. */
        const val FIRST_READ_MS = 90L
        const val SECOND_READ_MS = 320L

        /** One change seen twice, by both paths, inside this window. */
        const val DEDUPE_MS = 600L

        /** How long a tapped stream keeps the keys. Refreshed by every press that uses it. */
        const val PIN_MS = 4_000L

        /** LightOS's own screens, which have their own volume control. */
        const val LIGHTOS = "com.lightos"

        /**
         * The streams a tap walks through. Media, then the two you cannot otherwise reach from the
         * hardware at all, then the call stream when there is a call to have one.
         */
        val cycle = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
        )
    }
}
