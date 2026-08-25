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

/** What is playing, flattened to the things the lock face draws. */
data class LockTrack(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
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
        return LockTrack(
            pkg = c.packageName,
            title = title,
            artist = artist,
            art = art?.takeIf { !it.isRecycled },
            // Buffering counts as playing for the button: the user pressed play, the thing to draw
            // is pause. A glyph that flips back for two seconds of buffering reads as a failure.
            playing = state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING,
        )
    }

    private companion object {
        /**
         * States worth a row.
         *
         * `STOPPED`, `NONE` and `ERROR` all mean there is no music, and a phone that shows the
         * last song it played, with dead buttons, hours after it ended is worse than one that
         * shows nothing. A session that merely exists is not a session that is playing.
         */
        fun active(state: Int?): Boolean = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            -> true
            else -> false
        }
    }
}
