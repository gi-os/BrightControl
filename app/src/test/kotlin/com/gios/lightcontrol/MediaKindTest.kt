package com.gios.lightcontrol

import com.gios.lightcontrol.lock.MediaCapabilities
import com.gios.lightcontrol.lock.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lock face's three control sets, decided from what a session declares.
 *
 * The cases are written as the players that produce them, because the rule that matters is not
 * "duration is zero" but "a radio station gets a stop". Every one of these was read off a real
 * session on the phone.
 */
class MediaKindTest {

    private fun caps(
        skip: Boolean = false,
        seek: Boolean = false,
        step: Boolean = false,
        stop: Boolean = false,
        duration: Long = 0L,
    ) = MediaCapabilities(
        canSkip = skip,
        canSeek = seek,
        canStep = step,
        canStop = stop,
        durationMs = duration,
    )

    @Test
    fun `a song is music`() {
        val song = caps(skip = true, seek = true, duration = 3 * 60 * 1000L)
        assertEquals(MediaKind.MUSIC, MediaKind.of(song))
    }

    @Test
    fun `a podcast that declares a step is spoken`() {
        val episode = caps(seek = true, step = true, duration = 62 * 60 * 1000L)
        assertEquals(MediaKind.SPOKEN, MediaKind.of(episode))
    }

    @Test
    fun `a step is spoken before the duration has arrived`() {
        assertEquals(MediaKind.SPOKEN, MediaKind.of(caps(step = true)))
    }

    @Test
    fun `a long seekable file is spoken even with no step declared`() {
        val recording = caps(seek = true, duration = 90 * 60 * 1000L)
        assertEquals(MediaKind.SPOKEN, MediaKind.of(recording))
    }

    @Test
    fun `a queue wins over a step`() {
        // A music player that offers fast-forward as well as next is still a music player: throwing
        // skip away because scrubbing exists would cost the queue the buttons are for.
        val player = caps(skip = true, seek = true, step = true, duration = 4 * 60 * 1000L)
        assertEquals(MediaKind.MUSIC, MediaKind.of(player))
    }

    @Test
    fun `a radio stream is live`() {
        assertEquals(MediaKind.LIVE, MediaKind.of(caps(stop = true)))
    }

    @Test
    fun `a stream that allows seeking is still live`() {
        // Timeshifted radio reports a position with no length. No length and no queue is a stream,
        // whatever else it allows -- which is why the missing duration is tested before the long
        // one.
        assertEquals(MediaKind.LIVE, MediaKind.of(caps(seek = true, stop = true)))
    }

    @Test
    fun `a negative duration is a missing one`() {
        assertEquals(MediaKind.LIVE, MediaKind.of(caps(duration = -1L)))
    }

    @Test
    fun `a stream inside a station list keeps its skip buttons`() {
        // A player that walks a list of stations has a next worth pressing, so it is not given the
        // stop. Music is the fallback precisely because previous and next are wrong in the fewest
        // ways when nothing else is known.
        assertEquals(MediaKind.MUSIC, MediaKind.of(caps(skip = true, stop = true)))
    }

    @Test
    fun `a session that says nothing is music`() {
        assertEquals(MediaKind.MUSIC, MediaKind.of(caps(skip = true)))
    }

    @Test
    fun `the spoken threshold is twenty minutes`() {
        val under = caps(seek = true, skip = true, duration = MediaKind.SPOKEN_MIN_MS - 1)
        val over = caps(seek = true, skip = true, duration = MediaKind.SPOKEN_MIN_MS)
        assertEquals(MediaKind.MUSIC, MediaKind.of(under))
        assertEquals(MediaKind.SPOKEN, MediaKind.of(over))
    }
}
