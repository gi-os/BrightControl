package com.gios.lightcontrol

import com.gios.lightcontrol.lock.CallWords
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guess in the call path: which button on a call notification answers it.
 *
 * There is no semantic action for "answer", and the order of a CallStyle notification's actions
 * is the dialer's business, so the card reads the words. Being wrong in the answer direction is
 * the expensive one -- a button labelled ANSWER that hangs up on the caller -- so the two lists
 * must never overlap, which is what the last test here holds.
 */
class CallWordsTest {

    @Test
    fun `the usual answer labels`() {
        listOf("Answer", "ANSWER", "Accept", "Pick up", " answer ", "Answer call")
            .forEach { assertTrue(it, CallWords.isAnswer(it)) }
    }

    @Test
    fun `the usual decline labels`() {
        listOf("Decline", "REJECT", "Hang up", "Dismiss", "End call", "Ignore")
            .forEach { assertTrue(it, CallWords.isDecline(it)) }
    }

    @Test
    fun `nothing else is a call button`() {
        listOf("Message", "Reply", "Mute", "Speaker", "Call back", "", null)
            .forEach {
                assertFalse(it.orEmpty(), CallWords.isAnswer(it))
                assertFalse(it.orEmpty(), CallWords.isDecline(it))
            }
    }

    @Test
    fun `no label is both`() {
        listOf(
            "Answer", "Accept", "Pick up", "Decline", "Reject", "Hang up", "Dismiss", "End call",
        ).forEach { assertFalse(it, CallWords.isAnswer(it) && CallWords.isDecline(it)) }
    }
}
