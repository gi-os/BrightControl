package com.gios.lightcontrol.keys

/**
 * What the phone is doing that the key filter has to respect.
 *
 * @see KeyHush
 */
enum class Hush {
    /** Nothing. The filter works normally. */
    None,

    /** An alarm or a ringing phone. The filter takes its hands off every key. */
    Ring,

    /** A call already answered. The filter keeps working. */
    Call,
}

/**
 * Ringing and being on a call are two different facts, and only one of them is a reason to stop.
 *
 * The filter has always stood down for "ringing", and the reason is exact: an alarm or an incoming
 * call puts a screen up whose whole job is one dismiss gesture, and that screen must own every key
 * on the phone. Getting clever about which key it needs is the kind of guess that fails at 6am,
 * which it duly did.
 *
 * **A call in progress is not that.** Nothing is demanding to be dismissed; the call was answered,
 * LightOS's in-call screen is a touch screen, and no hardware key on this phone ends a call. But
 * the audio mode a call runs in -- `MODE_IN_CALL`, `MODE_IN_COMMUNICATION`, voice-communication
 * playback -- was folded into the same answer as the ringtone, so **every button on the phone went
 * dead for the length of every call**, and stayed dead through the thirty-second grace afterwards.
 * The wheel, the camera button, home, every binding on them. Reported from the other side as "the
 * buttons switch layers fine, but not during a call" -- which is what a filter refusing upstream of
 * every log line looks like from a thumb.
 *
 * So the question is answered in three parts rather than two, and the caller decides what each one
 * costs. [Hush.Ring] is the old behaviour, unchanged. [Hush.Call] is new and costs nothing.
 *
 * ### The grace window, and the one thing allowed to end it early
 *
 * Sampling only at key events means the moment an alarm is *silenced* looks identical to silence,
 * while the screen with STOP on it is still up and being pressed at. Half a minute of hands-off
 * after a ring covers the whole of that, and it is why the grace exists at all.
 *
 * A ringtone that stops because the call was **answered** is the one case with positive evidence
 * instead of a timer: the ring's screen is gone, and a call is what replaced it. So an answered
 * call ends a *ringtone's* grace -- otherwise the first thirty seconds of every call would still
 * have no buttons, which is most short calls. It does **not** end an *alarm's* grace, because
 * nothing observable says an alarm's STOP screen has been dealt with. Hence two windows, not one.
 *
 * Pure, and tested, because every branch here decides whether a phone still has working buttons
 * and not one of them can be seen from the phone.
 */
object KeyHush {

    /**
     * @param alarming an alarm is playing right now.
     * @param ringing the phone is ringing right now -- a ringtone playing, or `MODE_RINGTONE`.
     * @param inCall a call is up -- `MODE_IN_CALL`/`MODE_IN_COMMUNICATION`, or voice-communication
     *   playback.
     * @param withinAlarmGrace an alarm played within the grace window and may still be on screen.
     * @param withinRingGrace the phone rang within the grace window.
     */
    fun of(
        alarming: Boolean,
        ringing: Boolean,
        inCall: Boolean,
        withinAlarmGrace: Boolean,
        withinRingGrace: Boolean,
    ): Hush = when {
        // Something is making the noise now. Nothing below outranks this, a call included: an
        // alarm going off mid-call is still an alarm with a STOP button on it.
        alarming || ringing -> Hush.Ring
        // An alarm that stopped may be an alarm still asking. Only the timer can say.
        withinAlarmGrace -> Hush.Ring
        // Answered. The ring is over, and it was ended by the one gesture that could end it.
        inCall -> Hush.Call
        // Rang, stopped, was not answered: a missed-call screen may be up.
        withinRingGrace -> Hush.Ring
        else -> Hush.None
    }
}
