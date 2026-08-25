package com.gios.lightcontrol.lock

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** What is playing, flattened to the things the lock face draws. */
data class LockTrack(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    /** Which three buttons to draw. See [MediaKind]. */
    val kind: MediaKind,
)

/**
 * The player, read off the platform media session, for the lock face to draw.
 *
 * ### Why this lives in BrightControl and not in the player
 *
 * BrightMusic already draws its own row over LightOS's lock screen, and that row is a
 * `TYPE_APPLICATION_OVERLAY` -- layer 11 in AOSP's `getWindowLayerFromTypeLw`. The Light face is a
 * `TYPE_ACCESSIBILITY_OVERLAY` at layer 31 (see [LockOverlay] for the whole table). So with the
 * face on, the player's own controls are painted *underneath* it and can be neither seen nor
 * touched, and no flag, permission or window trick lifts an ordinary app above 31. Whatever draws
 * over the face has to be the thing that owns the face.
 *
 * Which makes the source a platform question rather than a private one. `MediaSessionManager`
 * already carries the title, the artist, the artwork and the transport controls of whatever is
 * playing, for every app on the phone at once, and BrightMusic's session is registered and correct
 * -- it is only LightOS that ignores it. Reading that costs no agreement between two apps, no
 * broadcast contract to keep in step across two releases, and it works the same for the radio, for
 * a podcast, and for any player installed later.
 *
 * ### The grant
 *
 * `getActiveSessions` is refused unless the [ComponentName] handed to it is an enabled
 * notification listener. That is [LockNotifications], which the face already needs for the shade,
 * so this asks for no new grant -- but it does mean the row is absent, not broken, on a phone
 * where that grant was never given. Every platform call is wrapped: a `SecurityException` here
 * would be thrown on the main thread behind the lock screen, which a user reads as the phone
 * freezing.
 */
class LockMedia(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** Told whenever the answer changes, on the main thread. Null means nothing is playing. */
    var onChange: ((LockTrack?) -> Unit)? = null

    var track: LockTrack? = null
        private set

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var listening = false

    private val component = ComponentName(context, LockNotifications::class.java)

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        /**
         * The session went away. Rescan rather than clear: closing one player while another sits
         * paused should fall back to that one, not to an empty row.
         */
        override fun onSessionDestroyed() = rescan()
    }

    /**
     * Begin watching. Called as the face goes up, which is while the screen is off.
     *
     * Idempotent, because the face is shown again on every screen-on and a second registration
     * would deliver every change twice.
     */
    fun start() {
        if (listening) return
        val mgr = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
        }.getOrNull() ?: return
        manager = mgr
        val ok = runCatching {
            mgr.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            true
        }.getOrDefault(false)
        if (!ok) {
            manager = null
            return
        }
        listening = true
        rescan()
    }

    /**
     * Stop watching, and let go of the controller.
     *
     * The listener has to come off with the face. It is registered against the *service's*
     * context and would otherwise outlive every face that registered it, waking this process on
     * every track change all day for a window that is not on screen.
     */
    fun stop() {
        if (listening) {
            listening = false
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        manager = null
        detach()
        track = null
    }

    fun playPause() = command { c ->
        if (track?.playing == true) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = command { it.transportControls.skipToNext() }

    fun previous() = command { it.transportControls.skipToPrevious() }

    /** Fifteen seconds on, for a podcast. */
    fun forward() = step(STEP_MS)

    /** Fifteen seconds back. */
    fun back() = step(-STEP_MS)

    /**
     * Move [delta] inside what is playing.
     *
     * `seekTo` rather than `fastForward()`, wherever the session allows it, because the platform's
     * step is whatever the player decided -- thirty seconds in one app, ten in the next, a whole
     * track in a third. A button drawn with **15** on it has to move fifteen seconds. `fastForward`
     * is only the fallback for a session that will not take a position, and there the number on the
     * glyph is a promise the player is making, not this app.
     */
    private fun step(delta: Long) = command { c ->
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        if (actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            val duration = runCatching {
                c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            }.getOrDefault(0L)
            var target = (position(state) + delta).coerceAtLeast(0L)
            if (duration > 0L) target = target.coerceAtMost(duration)
            c.transportControls.seekTo(target)
        } else if (delta >= 0L) {
            c.transportControls.fastForward()
        } else {
            c.transportControls.rewind()
        }
    }

    /**
     * End it, for a stream.
     *
     * Falls back to pause on a session that does not accept `stop`, which is most of them: half the
     * players on Android declare only pause and treat it as a stop for a stream anyway. A button
     * that does nothing would be worse than one that does the nearest thing.
     *
     * Stopping takes the row away with it -- `STATE_STOPPED` is not a state worth a row, see
     * [active] -- which is the point. Stop on a radio means done, not paused at a position that no
     * longer exists.
     */
    fun stopPlayback() = command { c ->
        val actions = c.playbackState?.actions ?: 0L
        if (actions and PlaybackState.ACTION_STOP != 0L) {
            c.transportControls.stop()
        } else {
            c.transportControls.pause()
        }
    }

    /**
     * Where the playhead is *now*.
     *
     * `PlaybackState.position` is a reading taken at `lastPositionUpdateTime` and never updated
     * again until the next state change, so on a track that has been playing for two minutes it is
     * two minutes stale. Seeking to it minus fifteen seconds would jump backwards to somewhere
     * near where the song started. The elapsed-realtime clock and the reported speed are how the
     * platform expects this to be extrapolated.
     */
    private fun position(state: PlaybackState?): Long {
        state ?: return 0L
        val base = state.position.coerceAtLeast(0L)
        val stamp = state.lastPositionUpdateTime
        if (state.state != PlaybackState.STATE_PLAYING || stamp <= 0L) return base
        val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
        val drift = ((SystemClock.elapsedRealtime() - stamp) * speed).toLong()
        return (base + drift).coerceAtLeast(0L)
    }

    private fun command(body: (MediaController) -> Unit) {
        val c = controller ?: return
        runCatching { body(c) }
    }

    private fun rescan() {
        val mgr = manager ?: return
        val sessions = runCatching { mgr.getActiveSessions(component) }.getOrNull() ?: return
        bind(sessions)
    }

    /**
     * Pick the one session worth drawing.
     *
     * Anything actually playing wins; otherwise the first the platform returns, which is in
     * priority order -- most recently active first. A paused player still gets the row, because a
     * lock screen you can press play on is the point.
     */
    private fun bind(sessions: List<MediaController>) {
        val chosen = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { active(it.playbackState?.state) }
            ?: sessions.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        detach()
        controller = chosen
        chosen?.let { runCatching { it.registerCallback(callback, handler) } }
        publish()
    }

    private fun detach() {
        controller?.let { runCatching { it.unregisterCallback(callback) } }
        controller = null
    }

    private fun publish() {
        val next = read()
        if (next == track) return
        track = next
        handler.post { runCatching { onChange?.invoke(next) } }
    }

    private fun read(): LockTrack? {
        val c = controller ?: return null
        val state = runCatching { c.playbackState?.state }.getOrNull()
        if (!active(state)) return null
        val meta = runCatching { c.metadata }.getOrNull() ?: return null
        val actions = runCatching { c.playbackState?.actions ?: 0L }.getOrDefault(0L)
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        // Three fallbacks because players disagree about which key carries the second line, and
        // the radio often fills only the album.
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: ""
        if (title.isBlank() && artist.isBlank()) return null
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val duration = runCatching {
            meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        }.getOrDefault(0L)
        return LockTrack(
            pkg = c.packageName,
            title = title,
            artist = artist,
            art = art?.takeIf { !it.isRecycled },
            // Buffering counts as playing for the button: the user pressed play, the thing to draw
            // is pause. A glyph that flips back for two seconds of buffering reads as a failure.
            playing = state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING,
            kind = MediaKind.of(
                MediaCapabilities(
                    canSkip = actions and
                        (PlaybackState.ACTION_SKIP_TO_NEXT or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
                    canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                    canStep = actions and
                        (PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND) != 0L,
                    canStop = actions and PlaybackState.ACTION_STOP != 0L,
                    durationMs = duration,
                ),
            ),
        )
    }

    companion object {

        /** What the two seek buttons move, and what is written on them. */
        const val STEP_MS: Long = 15_000L

        /**
         * States worth a row.
         *
         * `STOPPED`, `NONE` and `ERROR` all mean there is no music, and a phone that shows the
         * last song it played, with dead buttons, hours after it ended is worse than one that
         * shows nothing. A session that merely exists is not a session that is playing.
         */
        private fun active(state: Int?): Boolean = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            -> true
            else -> false
        }
    }
}
