package com.gios.lightcontrol.lock

import android.app.Notification
import android.app.NotificationManager
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
        if (n.category == Notification.CATEGORY_SERVICE) return false
        // No ranking entry fails open. The checks above have already taken out the genuinely
        // noisy cases, and dropping something because the ranker had not caught up yet would
        // hide exactly the notification that just arrived.
        val ranked = ranking?.getRanking(sbn.key, scratch) ?: false
        if (!ranked) return true
        return scratch.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }
}
