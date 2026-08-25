package com.gios.lightcontrol.lock

/**
 * What the session says it can do, lifted out of `PlaybackState.actions` and the metadata.
 *
 * Booleans rather than the action bitmask so that [MediaKind.of] is a plain function of plain
 * values: no `PlaybackState`, no Android at all, and therefore testable on the JVM. [LockMedia]
 * does the bit twiddling in one place, where the platform types already are.
 *
 * @param canSkip the session offers a next or a previous -- it has a queue.
 * @param canSeek the session accepts `seekTo` -- there is a position to move to.
 * @param canStep the session advertises fast-forward or rewind -- the shape podcast players declare.
 * @param canStop the session accepts `stop`, as opposed to only pause.
 * @param durationMs length of what is playing, or 0/negative when the player does not know one.
 *   A stream never knows one; a track between two tracks does not know one *yet*.
 */
data class MediaCapabilities(
    val canSkip: Boolean,
    val canSeek: Boolean,
    val canStep: Boolean,
    val canStop: Boolean,
    val durationMs: Long,
)

/**
 * What kind of thing is playing, as far as the lock face's three buttons are concerned.
 *
 * Not a genre. The only question this answers is which transport controls are worth drawing, and
 * the three answers are the three sets that make sense on a phone:
 *
 * - [MUSIC] -- previous, play/pause, next. A queue you move through.
 * - [SPOKEN] -- back 15, play/pause, forward 15. One long file you move *inside*. Skip on a podcast
 *   throws away the episode; what you want at a lock screen is the fifteen seconds you missed.
 * - [LIVE] -- play/pause and stop. A stream has no previous, no next and no inside. Skip buttons on
 *   a radio station are two dead controls, and dead controls on a lock screen read as a broken
 *   phone rather than as a station that cannot be skipped.
 */
enum class MediaKind {
    MUSIC,
    SPOKEN,
    LIVE,
    ;

    companion object {

        /** How long a seekable file has to be before skip is the wrong button. */
        const val SPOKEN_MIN_MS: Long = 20 * 60 * 1000L

        /**
         * Read the kind off the capabilities.
         *
         * Deliberately not read off the package name. Spotify is a music player and a podcast
         * player in the same session and the same process, a radio feed and a podcast feed are the
         * same code in the same reader, and any player installed next year has a name nobody here
         * has heard of. What a session declares it can do is the one signal that is right for all
         * of them and needs no list to maintain.
         *
         * The order of the rules is the whole design:
         *
         * 1. **Fast-forward or rewind, with no queue.** The strongest signal there is, and first
         *    because it is true before any metadata has arrived. Podcast players declare a step and
         *    no next; music players declare a next.
         * 2. **No duration and no queue.** A stream. Checked before the long-duration rule because
         *    a stream's duration is not long, it is missing.
         * 3. **Seekable and long.** A two-hour seekable file with a queue around it is still spoken
         *    word -- an audiobook chapter, a recorded show, a set. [SPOKEN_MIN_MS] is where "skip is
         *    the wrong button" starts being true more often than not.
         * 4. Otherwise music, which is also what a session that has told us nothing gets. Previous,
         *    play and next are the controls that are wrong in the fewest ways when the answer is
         *    unknown.
         *
         * A podcast that reports nothing at all for the second it spends loading is [LIVE] for that
         * second and then corrects itself. That is a glyph settling, not a wrong button: stop and
         * pause do the same thing to something that has not started.
         */
        fun of(caps: MediaCapabilities): MediaKind = when {
            caps.canStep && !caps.canSkip -> SPOKEN
            caps.durationMs <= 0L && !caps.canSkip -> LIVE
            caps.canSeek && caps.durationMs >= SPOKEN_MIN_MS -> SPOKEN
            else -> MUSIC
        }
    }
}
