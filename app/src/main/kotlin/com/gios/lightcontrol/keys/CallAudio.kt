package com.gios.lightcontrol.keys

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.gios.lightcontrol.lock.LockCallState

/**
 * The call speaker, as loud as this phone will go.
 *
 * ### What can and cannot be done here
 *
 * A call's downlink audio does not pass through the app mixer. It goes modem to codec to speaker,
 * and its gain is set inside the audio HAL from one number: the `STREAM_VOICE_CALL` index. So
 * `LoudnessEnhancer`, an `AudioEffect` on session 0, a custom dialer, an `InCallService` — none of
 * them adds a decibel, because none of them is in that path. Writing a dialer would buy control of
 * the *route* and of the in-call screen. It would not buy volume.
 *
 * What is real is that the index is often not at the top, and on LightOS there is no way to see
 * that or fix it: the phone ships no volume UI at all, so a call on speaker sits whereverit was
 * left, silently, with the keys the only way to move it and nothing on screen to say they did.
 *
 * ### So: pinned on the speaker, and left alone everywhere else
 *
 * When a call is up and the route is the built-in speaker, the index is put to maximum **once**,
 * on the transition into that route. Once, deliberately: a level re-asserted every second is a
 * volume control the user cannot turn down, and turning it down is exactly what somebody does when
 * a call gets loud in a quiet room. Lower it after the boost and it stays lowered.
 *
 * Android keeps a separate index per output device, so this moves the speaker's level and never
 * the earpiece's. A boost that made the next call painful against your ear would not be worth
 * having.
 */
class CallAudio(
    private val context: Context,
    private val allowed: () -> Boolean,
    private val log: (String) -> Unit,
) {

    /** True once the speaker route has been boosted, until the route or the call changes. */
    private var boosted = false

    /** The call ended. Next one starts from scratch. */
    fun onCall(state: LockCallState?) {
        if (state == null) boosted = false
    }

    /**
     * Look at the route, and boost if this is the moment.
     *
     * Called from the call watcher's tick, which only runs during a call.
     */
    fun check() {
        if (!allowed()) return
        val audio = runCatching {
            context.getSystemService(AudioManager::class.java)
        }.getOrNull() ?: return
        if (!inCall(audio)) {
            boosted = false
            return
        }
        if (!onSpeaker(audio)) {
            // Route changed away from the speaker: arm again, so putting it back on speaker later
            // in the same call boosts again.
            boosted = false
            return
        }
        if (boosted) return
        boosted = true
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }
            .getOrDefault(0)
        if (max <= 0) return
        val now = runCatching { audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }
            .getOrDefault(max)
        if (now >= max) {
            log("call speaker · already $now/$max")
            return
        }
        val ok = runCatching {
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
            true
        }.getOrDefault(false)
        log("call speaker · $now → $max" + if (ok) "" else " · REFUSED")
    }

    private fun inCall(audio: AudioManager): Boolean = runCatching {
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)

    /**
     * Whether the call is coming out of the phone's own speaker.
     *
     * `getCommunicationDevice` is the answer on 31 and up and names the device exactly, which
     * matters: a wired headset or a car is somebody else's volume to set. `isSpeakerphoneOn` is
     * the older question and is deprecated rather than wrong.
     */
    private fun onSpeaker(audio: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audio.communicationDevice ?: return@runCatching legacySpeaker(audio)
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            legacySpeaker(audio)
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun legacySpeaker(audio: AudioManager): Boolean = audio.isSpeakerphoneOn
}
