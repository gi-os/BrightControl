package com.gios.lightcontrol.notify

/**
 * What a notification actually says, out of the several places an app may have written it.
 *
 * The box and the lock face both read `EXTRA_TITLE` and `EXTRA_TEXT` and nothing else, and for a
 * large class of apps both of those are **empty**. A `MessagingStyle` notification does not fill
 * them in: it carries its conversation under `EXTRA_MESSAGES` and lets SystemUI build the two
 * lines at *draw* time, which is a step a notification listener never sees. Microsoft Teams,
 * WhatsApp, Signal, Messenger and Slack are all that shape. On this phone that meant a black box
 * with the app's name across the top and two blank lines under it -- the notification arrived,
 * the wake worked, the banner was drawn, and it said nothing.
 *
 * This is the same bug the incoming-call card had with `CallStyle` and it is fixed the same way,
 * with the same split: the *reading* of a `Bundle` stays at the call site in
 * [com.gios.lightcontrol.lock.LockNotifications], because a `Bundle` cannot be built in a unit
 * test, and the *choosing* lives here, where it can be.
 *
 * The order is deliberate. A style's own fields beat the base ones, because an app that filled in
 * both put the short version in the base fields; and the ticker is last because it is the
 * accessibility line, often the title and the text run together.
 */
object NoteText {

    /** One line of a `MessagingStyle` conversation, flattened to the two parts a box can draw. */
    data class Message(val sender: String?, val text: String?)

    /** The two lines. Either may be empty, and the box hides the ones that are. */
    data class Content(val title: String, val text: String)

    /**
     * Everywhere a notification might have put its words, in the order they should be believed.
     *
     * [messages] must arrive oldest first, which is the order
     * `Notification.MessagingStyle.Message.getMessagesFromBundleArray` returns and the order the
     * call site sorts into. The newest is the one being announced.
     */
    fun of(
        contentTitle: String? = null,
        bigTitle: String? = null,
        conversationTitle: String? = null,
        contentText: String? = null,
        bigText: String? = null,
        lines: List<String> = emptyList(),
        messages: List<Message> = emptyList(),
        summary: String? = null,
        subText: String? = null,
        ticker: String? = null,
    ): Content {
        conversation(conversationTitle, contentTitle, messages)?.let { return it }
        val title = firstReal(contentTitle, bigTitle, conversationTitle)
        // `subText` is dead last among the named fields: it is the qualifier apps put an account
        // name or a folder in, worth drawing only when nothing else was written at all.
        val text = firstReal(contentText, bigText, lines.firstOrNull(), summary, subText)
        if (title.isNotEmpty() || text.isNotEmpty()) return Content(title, text)
        // Nothing named. The ticker is a poor line -- it is written for a screen reader and often
        // runs the title into the text -- but a box with one real sentence in it beats a box with
        // an app name and two empty rows.
        return Content(clean(ticker), "")
    }

    /**
     * A `MessagingStyle` conversation, or null for a notification that is not one.
     *
     * Null rather than an empty [Content] so a messaging app that posted an empty conversation
     * still falls through to its base fields instead of drawing nothing.
     *
     * In a group chat the sender is the half of the message the title cannot carry: the title is
     * the room, so the name goes in front of the line. In a one-to-one there is no room and the
     * sender *is* the title, which is what every messaging app on every phone does.
     */
    private fun conversation(
        conversationTitle: String?,
        contentTitle: String?,
        messages: List<Message>,
    ): Content? {
        val last = messages.lastOrNull { clean(it.text).isNotEmpty() } ?: return null
        val body = clean(last.text)
        val room = clean(conversationTitle)
        val sender = clean(last.sender)
        val title = when {
            room.isNotEmpty() -> room
            sender.isNotEmpty() -> sender
            else -> clean(contentTitle)
        }
        val text = if (room.isNotEmpty() && sender.isNotEmpty()) "$sender: $body" else body
        return Content(title, text)
    }

    /**
     * A BrightSports alert, taken apart: the kind ("TD", "RED ZONE", "ONE-SCORE GAME"), the team
     * it is about, and what is left of the title (the score line).
     *
     * BrightSports 2.0 writes its titles in a fixed shape -- `TD SEA · NE 7 · SEA 14`,
     * `RED ZONE · SEA`, `ONE-SCORE GAME · NE 20 · SEA 24` -- so that a box can draw the kind
     * large and the rest small, the way its own box does. Only that package gets this reading;
     * a chat app whose message happens to start with "FG" keeps its title whole.
     */
    data class Kind(val label: String, val team: String?, val rest: String)

    fun sportsKind(pkg: String?, title: String?): Kind? {
        if (pkg != SPORTS_PKG) return null
        val t = clean(title)
        if (t.isEmpty()) return null
        SPORTS_PREFIX.matchEntire(t)?.let { m ->
            return Kind(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        if (t.startsWith("RED ZONE · ")) return Kind("RED ZONE", t.removePrefix("RED ZONE · "), "")
        if (t.startsWith("ONE-SCORE GAME · ")) {
            return Kind("ONE-SCORE GAME", null, t.removePrefix("ONE-SCORE GAME · "))
        }
        return null
    }

    const val SPORTS_PKG = "com.gios.lightsports"
    private val SPORTS_PREFIX = Regex("^(TD \\+2|TD|FG|SAFETY|PAT|SCORE) ([A-Z0-9&]{2,5}) · (.*)$")

    private fun firstReal(vararg candidates: String?): String {
        for (candidate in candidates) {
            val cleaned = clean(candidate)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return ""
    }

    /**
     * Trimmed, and with every run of whitespace flattened to one space.
     *
     * A notification's title is a single line by contract and its text is not: an app is free to
     * put newlines and runs of spaces in either, and both land in a `TextView` that is one line
     * tall for the title and two for the body. Flattening here means the ellipsis falls where the
     * words stop rather than at the first line break.
     */
    private fun clean(value: String?): String =
        value?.replace(WHITESPACE, " ")?.trim().orEmpty()

    private val WHITESPACE = Regex("""\s+""")
}
