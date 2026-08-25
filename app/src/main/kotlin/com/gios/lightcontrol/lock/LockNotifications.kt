package com.gios.lightcontrol.lock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line on the lock face. */
data class LockNote(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

/**
 * A call, as the shade describes it, with the two intents that act on it.
 *
 * The notification is the source rather than telephony because it is the one source that needs no
 * grant this face does not already hold: `READ_PHONE_STATE` gives a number and no name, reading
 * the name needs `READ_CONTACTS`, and neither is required to answer a call. The dialer has already
 * done all of that work and written the answer in the title.
 */
data class LockCallNote(
    val pkg: String,
    val who: String,
    val text: String,
    val incoming: Boolean,
    val answer: PendingIntent?,
    val decline: PendingIntent?,
    val postedAt: Long,
)

/**
 * The call the phone is on, or null.
 *
 * A plain callback rather than a `StateFlow` like [LockNotes], because the one consumer is the
 * service and it is told, not asked: the moment that matters is the ring arriving, and a state
 * nobody is collecting at that instant is a call card that appears when something else happens to
 * repaint the face.
 */
object LockCalls {
    @Volatile
    var current: LockCallNote? = null
        private set

    /** Told on the listener's thread whenever the answer changes. */
    var onChange: ((LockCallNote?) -> Unit)? = null

    internal fun publish(call: LockCallNote?) {
        val before = current
        if (before?.pkg == call?.pkg &&
            before?.who == call?.who &&
            before?.incoming == call?.incoming &&
            before?.text == call?.text
        ) {
            // Same call, same state. The intents are refreshed anyway -- a PendingIntent from a
            // cancelled notification is inert, and sending an inert one is the failure that looks
            // like the button not working.
            current = call
            return
        }
        current = call
        runCatching { onChange?.invoke(call) }
    }
}

/**
 * What is currently worth showing, newest first.
 *
 * A `StateFlow` rather than a callback because the lock face is created and destroyed several
 * times a day and the listener outlives all of them: the list has to be readable the instant the
 * face composes, not merely pushed at whoever happened to be listening when it changed.
 */
object LockNotes {
    private val state = MutableStateFlow<List<LockNote>>(emptyList())
    val notes: StateFlow<List<LockNote>> = state.asStateFlow()

    internal fun publish(list: List<LockNote>) {
        state.value = list
    }

    /**
     * Whether the listener has actually been granted.
     *
     * Asked of the secure setting rather than of the service, because the interesting moment is
     * *before* anything has bound — a settings screen that says "off" until the first grant would
     * be right for the wrong reason. Both stored forms are handled: a service enabled over adb is
     * normally written short (`pkg/.Class`) and comparing that to a flattened name as text reports
     * OFF for a listener that is running. That exact mistake has already cost this codebase a
     * morning once, in `Grants.serviceEnabled`.
     */
    fun granted(context: Context): Boolean {
        val expected = ComponentName(context, LockNotifications::class.java)
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        return raw.split(':').any { entry ->
            val parsed = ComponentName.unflattenFromString(entry.trim()) ?: return@any false
            val cls = parsed.className
            val full = when {
                cls.startsWith(".") -> ComponentName(parsed.packageName, parsed.packageName + cls)
                !cls.contains('.') -> ComponentName(parsed.packageName, "${parsed.packageName}.$cls")
                else -> parsed
            }
            full == expected
        }
    }
}

/**
 * Reads the shade so the lock face can show it.
 *
 * The filter is lifted wholesale from Glance, which arrived at it by reading a real LPIII's
 * `dumpsys notification`: on LightOS everything user-facing lands at importance 3 or 4 and
 * everything ignorable lands at 2, so importance does nearly all the work and the flag checks only
 * catch group summaries — importance 4, but duplicating the children beneath them.
 *
 * Nothing here is stored or sent anywhere. The list lives in memory in this process and is
 * rebuilt from `activeNotifications` on every change, so a notification dismissed on another
 * surface is gone from this one too without any reconciliation.
 */
class LockNotifications : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onListenerDisconnected() {
        // Cleared, unlike Glance's store: an unbound listener behind a live lock face would
        // otherwise leave yesterday's messages on screen with no way to notice they were stale.
        LockNotes.publish(emptyList())
        LockCalls.publish(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val scratch = Ranking()
        val pm = packageManager

        val notes = active
            .filter { keep(it, ranking, scratch) }
            .sortedByDescending { it.postTime }
            .map { sbn ->
                val extras = sbn.notification.extras
                val app = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrNull() ?: sbn.packageName.substringAfterLast('.')
                LockNote(
                    key = sbn.key,
                    pkg = sbn.packageName,
                    app = app,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    postedAt = sbn.postTime,
                )
            }
        LockNotes.publish(notes)
        LockCalls.publish(readCall(active))
    }

    /**
     * The call notification, flattened to what a lock face can draw and press.
     *
     * Newest wins, because a second call arriving during a first is the one you are being asked
     * about. `CATEGORY_CALL` is the whole test: it is what a dialer sets, it is what the platform's
     * own `CallStyle` sets, and it is set on the ongoing call as well as the ringing one -- which
     * is why these are pulled out *before* the ongoing flag drops them from the list.
     */
    private fun readCall(active: Array<StatusBarNotification>): LockCallNote? {
        val sbn = active
            .filter { it.notification?.category == Notification.CATEGORY_CALL }
            .maxByOrNull { it.postTime } ?: return null
        val n = sbn.notification
        val extras = n.extras
        val actions = n.actions?.filterNotNull().orEmpty()
        val answer = actions.firstOrNull { CallWords.isAnswer(it.title) }?.actionIntent
        val decline = actions.firstOrNull { CallWords.isDecline(it.title) }?.actionIntent
        // `EXTRA_CALL_TYPE` is 1 for incoming, 2 for ongoing, 3 for screening, and only exists on
        // a CallStyle notification. An answer action is the same fact for every other dialer:
        // nothing offers to answer a call that has already been answered.
        val type = runCatching { extras.getInt("android.callType", 0) }.getOrDefault(0)
        val incoming = when {
            type == 1 -> true
            type >= 2 -> false
            else -> answer != null
        }
        return LockCallNote(
            pkg = sbn.packageName,
            who = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty(),
            incoming = incoming,
            answer = answer,
            decline = decline,
            postedAt = sbn.postTime,
        )
    }

    private fun keep(sbn: StatusBarNotification, ranking: RankingMap?, scratch: Ranking): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName) return false
        if (sbn.packageName == "android") return false
        if (sbn.tag == "ranker_group") return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return false
        // A call has its own card on the face, with buttons. Listed as well it would be the same
        // call twice, once with something to press and once without.
        if (n.category == Notification.CATEGORY_CALL) return false
        if (n.category == Notification.CATEGORY_SERVICE) return false
        // No ranking entry fails open. The checks above have already taken out the genuinely
        // noisy cases, and dropping something because the ranker had not caught up yet would
        // hide exactly the notification that just arrived.
        val ranked = ranking?.getRanking(sbn.key, scratch) ?: false
        if (!ranked) return true
        return scratch.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

}

/**
 * Which of a call notification's buttons answers, and which hangs up.
 *
 * By the words on them, because there is no semantic action for "answer" and the order of a
 * `CallStyle` notification's actions is an implementation detail of whichever dialer built it.
 * Matching text is a guess that fails *visibly* — the button is absent — rather than one that
 * hangs up a call somebody meant to take. `TelecomManager` is the fallback underneath it and needs
 * no words at all; see [LockCall].
 *
 * Its own object so it can be tested without a phone. This is the only guess in the call path.
 */
object CallWords {

    private val ANSWER = listOf("answer", "accept", "pick up", "take call")
    private val DECLINE = listOf("decline", "reject", "dismiss", "ignore", "hang up", "end call")

    fun isAnswer(title: CharSequence?): Boolean = has(title, ANSWER)

    fun isDecline(title: CharSequence?): Boolean = has(title, DECLINE)

    private fun has(title: CharSequence?, words: List<String>): Boolean {
        val t = title?.toString()?.lowercase()?.trim() ?: return false
        return words.any { t.contains(it) }
    }
}
