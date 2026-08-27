package com.gios.lightcontrol

import com.gios.lightcontrol.keys.VolumeNews
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stream 3 is `STREAM_MUSIC`; the number is irrelevant here, only that it is stable. */
private const val MUSIC = 3
private const val RING = 2

class VolumeNewsTest {

    @Test
    fun `a press that moved the level is news`() {
        assertTrue(VolumeNews.worthShowing(MUSIC, 8, MUSIC, 7, 15, up = true))
    }

    /**
     * The whole point. BrightLibrary turns pages with the volume keys and consumes them, so the
     * system never sees the press and the level does not move — and the strip used to appear over
     * every page turn.
     */
    @Test
    fun `a press the app in front swallowed is not`() {
        assertFalse(VolumeNews.worthShowing(MUSIC, 7, MUSIC, 7, 15, up = true))
        assertFalse(VolumeNews.worthShowing(MUSIC, 7, MUSIC, 7, 15, up = false))
    }

    @Test
    fun `up at maximum still shows the full bar`() {
        assertTrue(VolumeNews.worthShowing(MUSIC, 15, MUSIC, 15, 15, up = true))
    }

    @Test
    fun `down at zero still shows the empty bar`() {
        assertTrue(VolumeNews.worthShowing(MUSIC, 0, MUSIC, 0, 15, up = false))
    }

    /** Down at maximum is not the rail it is pressed away from. */
    @Test
    fun `down at maximum with nothing moved is not news`() {
        assertFalse(VolumeNews.worthShowing(MUSIC, 15, MUSIC, 15, 15, up = false))
    }

    @Test
    fun `up at zero with nothing moved is not news`() {
        assertFalse(VolumeNews.worthShowing(MUSIC, 0, MUSIC, 0, 15, up = true))
    }

    /** Music started mid-press: the keys moved to another stream, which is worth saying. */
    @Test
    fun `a change of stream is news even at the same level`() {
        assertTrue(VolumeNews.worthShowing(MUSIC, 7, RING, 7, 15, up = true))
    }

    /** No baseline: this app moved the volume itself from a bound button. */
    @Test
    fun `no baseline always shows`() {
        assertTrue(VolumeNews.worthShowing(MUSIC, 7, -1, -1, 0, up = true))
    }

    /** An unreadable maximum must not turn a swallowed press into a rail. */
    @Test
    fun `an unknown maximum is not the top of the scale`() {
        assertFalse(VolumeNews.worthShowing(MUSIC, 7, MUSIC, 7, 0, up = true))
    }
}
