package com.gios.lightcontrol

import com.gios.lightcontrol.notify.NoteText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the eight places an app may write its words wins.
 *
 * The case that mattered is [teamsStyleMessageIsRead]: a `MessagingStyle` notification carries no
 * title and no text, and reading only those two drew a box with an app name and two blank lines.
 */
class NoteTextTest {

    @Test
    fun plainNotificationIsUnchanged() {
        val said = NoteText.of(contentTitle = "Obtainium", contentText = "1 update available")
        assertEquals("Obtainium", said.title)
        assertEquals("1 update available", said.text)
    }

    /** Teams, WhatsApp, Signal: both base fields empty, everything under EXTRA_MESSAGES. */
    @Test
    fun teamsStyleMessageIsRead() {
        val said = NoteText.of(
            contentTitle = null,
            contentText = null,
            messages = listOf(NoteText.Message("Alex", "are you on the 4pm?")),
        )
        assertEquals("Alex", said.title)
        assertEquals("are you on the 4pm?", said.text)
    }

    /** A group chat names the room, so the sender has to go in front of the line. */
    @Test
    fun groupChatKeepsTheSender() {
        val said = NoteText.of(
            conversationTitle = "Fulfilment - Q3",
            messages = listOf(
                NoteText.Message("Alex", "first"),
                NoteText.Message("Francis", "pallets land Thursday"),
            ),
        )
        assertEquals("Fulfilment - Q3", said.title)
        assertEquals("Francis: pallets land Thursday", said.text)
    }

    /** The newest is the one being announced, whatever order the array arrived in. */
    @Test
    fun theLastMessageWins() {
        val said = NoteText.of(
            messages = listOf(
                NoteText.Message("Alex", "one"),
                NoteText.Message("Alex", "two"),
            ),
        )
        assertEquals("two", said.text)
    }

    /** An empty conversation falls through rather than drawing nothing. */
    @Test
    fun emptyConversationFallsBackToTheBaseFields() {
        val said = NoteText.of(
            contentTitle = "Microsoft Teams",
            contentText = "2 new messages",
            messages = listOf(NoteText.Message("Alex", "   ")),
        )
        assertEquals("Microsoft Teams", said.title)
        assertEquals("2 new messages", said.text)
    }

    @Test
    fun bigTextIsUsedWhenThereIsNoContentText() {
        val said = NoteText.of(contentTitle = "Gmail", bigText = "the whole mail")
        assertEquals("the whole mail", said.text)
    }

    @Test
    fun inboxLinesAreUsedWhenThereIsNothingElse() {
        val said = NoteText.of(contentTitle = "Gmail", lines = listOf("Alex - lunch?", "older"))
        assertEquals("Alex - lunch?", said.text)
    }

    @Test
    fun tickerIsTheLastResort() {
        val said = NoteText.of(ticker = "Sam: on my way")
        assertEquals("Sam: on my way", said.title)
        assertEquals("", said.text)
    }

    @Test
    fun aNotificationWithNoWordsAtAllIsEmpty() {
        val said = NoteText.of()
        assertEquals("", said.title)
        assertEquals("", said.text)
    }

    /** Both lines are single-line TextViews or close to it. A newline must not end the sentence. */
    @Test
    fun whitespaceIsFlattened() {
        val said = NoteText.of(
            contentTitle = "  Slack  ",
            contentText = "line one" + Char(10) + "line two   and   three",
        )
        assertEquals("Slack", said.title)
        assertEquals("line one line two and three", said.text)
    }

    /** A style's own field beats the base one, which is where the short version goes. */
    @Test
    fun conversationBeatsContentTitle() {
        val said = NoteText.of(
            contentTitle = "Alex",
            conversationTitle = "Ops",
            messages = listOf(NoteText.Message("Alex", "hi")),
        )
        assertEquals("Ops", said.title)
    }
}
