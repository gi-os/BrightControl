package com.gios.lightcontrol.keys

/**
 * Whether a volume press is worth putting on screen. No Android types, so it can be tested.
 *
 * Small enough to inline and separate anyway, because it is the rule that decides whether reading a
 * book flashes a volume strip over every page — and a rule that only exists inside a method called
 * from a key filter is a rule nothing will notice regressing.
 *
 * The premise is that **a key this app sees is not a key the system acted on.** The accessibility
 * filter runs ahead of the app in front, so a press is noted whether or not anything happened, and
 * there is no API that answers "did the app in front swallow that". There does not need to be: the
 * strip exists to report a *change*, and a level that did not change is not one. The broadcast path
 * has always dropped exactly this case — `prev == value` — and this is the same rule for the path
 * that reads the level back after a key.
 */
object VolumeNews {

    /**
     * @param stream the stream the keys resolve to now, and [beforeStream] what they resolved to
     *   before the press. A press that changed which stream is in play is always news.
     * @param beforeMax the top of the scale, or 0 when it could not be read.
     * @param up which way the press pushed. The one press that moves nothing and still deserves a
     *   strip is the one at the end of the scale: pressing up at maximum is a question the full bar
     *   answers, and an unanswered key reads as a broken one.
     */
    fun worthShowing(
        stream: Int,
        level: Int,
        beforeStream: Int,
        beforeLevel: Int,
        beforeMax: Int,
        up: Boolean,
    ): Boolean {
        // No baseline was taken — this app moved the volume itself, or the read failed. Nothing to
        // be suspicious of.
        if (beforeStream < 0) return true
        if (stream != beforeStream || level != beforeLevel) return true
        return if (up) beforeMax > 0 && level >= beforeMax else level <= 0
    }
}
