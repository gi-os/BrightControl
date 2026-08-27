package com.gios.lightcontrol.lock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.notify.AlertHandoff
import com.gios.lightcontrol.notify.Banners
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
    /**
     * The dialer's own call screen, as the dialer itself would raise it.
     *
     * A ringing call notification carries a full-screen intent -- that is how a dialer takes over a
     * sleeping phone -- and the ongoing one carries a content intent to the same screen. Holding
     * both is what lets the face hand the call back to LightOS the instant it is answered instead
     * of hoping the activity underneath is still where it was left. See `LockCall.openCallScreen`.
     */
    val fullScreen: PendingIntent?,
    val content: PendingIntent?,
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

    /**
     * Every package with something in the shade right now, filtered or not.
     *
     * Published from the raw list, *before* [LockNotifications.keep] has taken anything out, which
     * is the whole point: the settings screen that hides an app by name has to be able to name the
     * app whose notification is being hidden, and the interesting ones are exactly the ones the
     * filter is already dropping. Without this the list of sources would only ever contain the
     * notifications nobody wanted to hide.
     */
    private val seen = MutableStateFlow<Set<String>>(emptySet())
    val sources: StateFlow<Set<String>> = seen.asStateFlow()

    /**
     * Notifications waved off the face that the platform would not cancel.
     *
     * An app can mark a notification un-clearable, and the platform's way of refusing
     * `cancelNotification` is to simply not remove it — so a swipe on such a row did nothing at
     * all, the rebuild that followed still contained it, and the row sat there looking as though
     * the gesture had failed. LightOS's own always-running notice is one of these.
     *
     * So a swipe now always removes the row, and for these it is removed *here*: kept out of the
     * list for as long as the phone stays locked, and forgotten at the next unlock. Deliberately
     * not stored. A lock face keeping its own permanent record of what you had waved away is a
     * face that disagrees with the shade, with Glance and with the app that posted it — which is
     * the reason [dismiss] cancels for real wherever it can, and why hiding an app for good is a
     * setting the user makes rather than a side effect of a swipe.
     */
    @Volatile
    private var hiddenKeys: Set<String> = emptySet()

    internal fun keyHidden(key: String): Boolean = key in hiddenKeys

    /** Called at the unlock. Everything waved away while locked comes back to the shade. */
    fun clearSessionHides() {
        if (hiddenKeys.isEmpty()) return
        hiddenKeys = emptySet()
        rebuild()
    }

    /**
     * Read the shade again and republish.
     *
     * For the settings screen: hiding an app has to take the row off a face that may well be up
     * behind these settings, and nothing else would tell the listener that the rule it filters by
     * has changed. A no-op when nothing is bound.
     */
    fun rebuild() {
        runCatching { service?.refresh() }
    }

    internal fun publishSources(packages: Set<String>) {
        if (seen.value == packages) return
        seen.value = packages
    }

    /**
     * Told on the listener's thread whenever the list is rebuilt.
     *
     * The `StateFlow` above is still what the face reads; this is only the nudge to go and read it.
     * Without one the face repainted on the minute tick and nothing else, so a message arriving at
     * 10:00:05 was on screen at 10:01 -- and, after a swipe, the row it had just dismissed sat
     * there for most of a minute looking like the gesture had failed.
     *
     * Posted onto the main thread by whoever sets it. This fires on the listener's thread.
     */
    var onChange: (() -> Unit)? = null

    /**
     * The bound listener, or null.
     *
     * Held because `cancelNotification` is an instance method on the service and the face is not
     * the service: only the bound listener may dismiss anything, and it is the one object in this
     * process guaranteed to have been granted.
     */
    @Volatile
    private var service: LockNotifications? = null

    internal fun attach(listener: LockNotifications?) {
        service = listener
    }

    internal fun publish(list: List<LockNote>) {
        state.value = list
        runCatching { onChange?.invoke() }
    }

    /**
     * Swiped off the face: cancel it, everywhere.
     *
     * The real cancel rather than a local hide, because a lock face that quietly kept its own list
     * of what you had waved away would disagree with the shade, with Glance and with the app that
     * posted it -- and would bring the same notification back at the next unlock. Nothing is
     * remembered here; the removal comes back through [publish] like any other change.
     *
     * A notification whose app marked it un-clearable will not go, and the platform says so by
     * simply not removing it — the rebuild that follows still contains it, which for a whole
     * release read on the phone as the swipe not working. So the row is *also* held out locally
     * for the rest of the locked session; see [hiddenKeys] for why that is not stored.
     *
     * The return value is still whether the real cancel took, because that is what the log line
     * is for. The row goes either way.
     */
    fun dismiss(key: String): Boolean {
        val listener = service ?: return false
        val gone = runCatching { listener.cancelNotification(key); true }.getOrDefault(false)
        hiddenKeys = hiddenKeys + key
        // The cancel, if it worked, brings its own rebuild through onNotificationRemoved. This is
        // for the case where it did not.
        rebuild()
        return gone
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
        LockNotes.attach(this)
        // Before the first refresh, which hands over everything in the shade. Without this line a
        // reboot would draw a banner about whatever happened to be the newest thing from yesterday.
        Banners.listenerConnected()
        // The grant may have only just landed, which changes whether this app owns the on-screen
        // box -- and the other apps have no way of finding that out for themselves.
        runCatching { AlertHandoff.announce(this) }
        refresh()
    }

    override fun onListenerDisconnected() {
        // Cleared, unlike Glance's store: an unbound listener behind a live lock face would
        // otherwise leave yesterday's messages on screen with no way to notice they were stale.
        LockNotes.attach(null)
        LockNotes.publish(emptyList())
        LockCalls.publish(null)
        Banners.listenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = refresh()

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) = refresh()

    internal fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        val scratch = Ranking()
        val pm = packageManager
        // Read per rebuild rather than held. SharedPreferences is an in-memory map after the first
        // load, and a rule cached here would be a rule that needed the listener rebinding to
        // change — on a screen whose whole job is changing it.
        val prefs = Prefs(this)
        val hiddenApps = prefs.lockHiddenApps()
        val allowPersistent = prefs.lockPersistent

        val dialer = defaultDialer()
        val calls = active.filter { isCall(it, dialer) }
        val callKeys = calls.map { it.key }.toSet()

        // Every package with anything in the shade, before a single rule has been applied. See
        // [LockNotes.sources].
        LockNotes.publishSources(
            active.map { it.packageName }
                .filterTo(mutableSetOf()) { it != packageName && it != "android" },
        )

        // Held as the notifications rather than mapped straight to rows, because the banner needs
        // one thing a LockNote does not carry -- the contentIntent a tap sends. One filter pass
        // for both, so the face and the box can never disagree about what got through it.
        val kept = active
            .filter {
                keep(it, ranking, scratch, hiddenApps, allowPersistent) && it.key !in callKeys
            }
            .sortedByDescending { it.postTime }

        val notes = kept.map { sbn ->
            val extras = sbn.notification.extras
            LockNote(
                key = sbn.key,
                pkg = sbn.packageName,
                app = label(pm, sbn.packageName),
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                postedAt = sbn.postTime,
            )
        }
        LockNotes.publish(notes)
        LockCalls.publish(readCall(calls.maxByOrNull { it.postTime }))

        Banners.sync(
            live = kept.mapTo(mutableSetOf()) { it.key },
            // The newest that is not the always-running kind, whatever `allowPersistent` says. A
            // download or a navigation belongs on a face you choose to look at; something that has
            // been in the shade for an hour is not news and must not interrupt an app to say so.
            //
            // Skipped past rather than ruled out: testing only the newest row meant a navigation
            // re-posting every second was permanently `first`, so with permanent notifications
            // shown, no message could ever raise a banner again -- silently, for the whole drive.
            candidate = kept.firstOrNull {
                !NoteFilter.isPersistent(it.notification.flags, it.notification.category)
            }
                ?.let { sbn ->
                    val extras = sbn.notification.extras
                    Banners.Note(
                        key = sbn.key,
                        pkg = sbn.packageName,
                        app = label(pm, sbn.packageName),
                        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                        postedAt = sbn.postTime,
                        open = sbn.notification.contentIntent,
                    )
                },
            // The box is one reason to run this and the wake is the other. Gated on `banner`
            // alone, a phone with banners off never armed anything -- so `bannerWake`, which is on
            // by default, could not wake a phone for a notification however it was set.
            enabled = prefs.banner || (prefs.bannerWake && prefs.lockScreen),
            dwellMs = prefs.bannerDwellMs,
        )
    }

    /** The app's name as the user knows it, or the tail of its package when it has none. */
    private fun label(pm: android.content.pm.PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg.substringAfterLast('.')

    /**
     * Whether this notification is the phone ringing.
     *
     * `CATEGORY_CALL` was the whole test and it was not enough. It is what the platform's own
     * `CallStyle` sets and what a well-behaved dialer sets, and a dialer that sets neither still
     * rings the phone -- at which point the card had no name, no buttons and no idea a call was
     * happening, and fell back to the audio mode and the words "Incoming call". Four tests now,
     * any one of which is enough:
     *
     * 1. The category, which remains the common case.
     * 2. The `CallStyle` template, set by the style even where the category has been overwritten.
     * 3. `android.callType`, the extra that only a call notification carries.
     * 4. A notification from the **default dialer** with a button that answers or declines.
     *    Nothing else on the phone offers to answer anything.
     */
    private fun isCall(sbn: StatusBarNotification, dialer: String?): Boolean {
        val n = sbn.notification ?: return false
        if (n.category == Notification.CATEGORY_CALL) return true
        val extras = n.extras ?: return false
        val template = runCatching { extras.getString(Notification.EXTRA_TEMPLATE) }.getOrNull()
        if (template?.endsWith("CallStyle") == true) return true
        if (runCatching { extras.containsKey("android.callType") }.getOrDefault(false)) return true
        if (dialer != null && sbn.packageName == dialer) {
            val actions = n.actions?.filterNotNull().orEmpty()
            return actions.any { CallWords.isAnswer(it.title) || CallWords.isDecline(it.title) }
        }
        return false
    }

    /** Who the phone would ring through, asked once per rebuild rather than per notification. */
    private fun defaultDialer(): String? = runCatching {
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    /**
     * The call notification, flattened to what a lock face can draw and press.
     *
     * Newest wins, because a second call arriving during a first is the one you are being asked
     * about.
     *
     * ### Who is calling
     *
     * `EXTRA_TITLE` was the only thing read here, and on this phone it is empty. A `CallStyle`
     * notification does not put the caller in the title: it carries a [Person] under
     * `android.callPerson` and the platform builds the title from it at *draw* time, inside
     * SystemUI, which is a step a notification listener never sees. So the card asked the one
     * question the dialer had not answered, got nothing, and drew "Incoming call" over a phone
     * that knew perfectly well who it was.
     *
     * Six places are read now, best first, and the number is the last of them because a name is
     * what somebody wants at arm's length -- but a number beats a phrase every phone shows.
     */
    private fun readCall(sbn: StatusBarNotification?): LockCallNote? {
        sbn ?: return null
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
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
        val person = person(extras, "android.callPerson") ?: people(extras).firstOrNull()
        val who = CallWho.pick(
            listOf(
                person?.name?.toString(),
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
                n.tickerText?.toString(),
                CallWho.fromUri(person?.uri),
            ),
        )
        return LockCallNote(
            pkg = sbn.packageName,
            who = who,
            // Not repeated under the name. A CallStyle's text is very often the same phrase the
            // card's own label already says, and "Sarah / Incoming call / INCOMING CALL" is the
            // same word twice on a screen with room for three lines.
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                .orEmpty()
                .takeIf { it.isNotBlank() && !CallWho.isPlaceholder(it) && it != who }
                .orEmpty(),
            incoming = incoming,
            answer = answer,
            decline = decline,
            fullScreen = n.fullScreenIntent,
            content = n.contentIntent,
            postedAt = sbn.postTime,
        )
    }

    /** One [Person] out of the extras, across the two ways the platform hands them over. */
    private fun person(extras: Bundle, key: String): Person? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(key, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(key) as? Person
        }
    }.getOrNull()

    private fun people(extras: Bundle): List<Person> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
        }
    }.getOrNull().orEmpty()

    private fun keep(
        sbn: StatusBarNotification,
        ranking: RankingMap?,
        scratch: Ranking,
        hiddenApps: Set<String>,
        allowPersistent: Boolean,
    ): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName) return false
        if (sbn.packageName == "android") return false
        // Hidden by the user, by name. Above every other rule, because it is the one rule the user
        // stated in as many words.
        if (sbn.packageName in hiddenApps) return false
        // Waved off the face during this locked session. See [LockNotes.dismiss].
        if (LockNotes.keyHidden(sbn.key)) return false
        if (sbn.tag == "ranker_group") return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // The always-running kind. Dropped unless asked for -- and note that the test grew a third
        // flag: FLAG_NO_CLEAR is neither ongoing nor a foreground service, and it is what LightOS
        // puts on its own permanent notice. That notice therefore passed every check here, landed
        // on the face, and could not be swiped off, because the platform refuses to cancel an
        // un-clearable notification by not removing it. See [NoteFilter.isPersistent].
        if (NoteFilter.isPersistent(n.flags, n.category) && !allowPersistent) return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return false
        // A call has its own card on the face, with buttons. Listed as well it would be the same
        // call twice, once with something to press and once without.
        if (n.category == Notification.CATEGORY_CALL) return false
        // No ranking entry fails open. The checks above have already taken out the genuinely
        // noisy cases, and dropping something because the ranker had not caught up yet would
        // hide exactly the notification that just arrived.
        val ranked = ranking?.getRanking(sbn.key, scratch) ?: false
        if (!ranked) return true
        return scratch.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

}

/**
 * Which notifications are the always-running kind.
 *
 * Its own object so it can be tested without a phone, and so the three flags that mean "permanent"
 * are named in one place. They are not interchangeable and the list was short by one:
 *
 *  - `FLAG_ONGOING_EVENT` — the app says something is in progress.
 *  - `FLAG_FOREGROUND_SERVICE` — the platform adds it to whatever a foreground service posts.
 *  - `FLAG_NO_CLEAR` — **the one that was missing.** It says nothing about progress; it says the
 *    notification cannot be dismissed. LightOS's own permanent notice sets this and neither of the
 *    others, so it passed every check the filter had, landed on the lock face at full importance,
 *    and then could not be swiped away: `cancelNotification` on an un-clearable notification is
 *    refused by the platform simply not removing it, so the rebuild brought the row straight back.
 *    Reported from a real phone as the lock screen showing a LightOS notification that would not
 *    go.
 *
 * `CATEGORY_SERVICE` is here too, for an app that describes itself that way without setting a
 * flag. `CATEGORY_TRANSPORT` deliberately is **not**: what is playing has its own row on the face,
 * with controls, and it is dropped from the list whether or not persistent notifications are
 * wanted.
 */
object NoteFilter {

    fun isPersistent(flags: Int, category: String?): Boolean {
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_NO_CLEAR != 0) return true
        return category == Notification.CATEGORY_SERVICE
    }
}

/**
 * Who is calling, out of everything a call notification might have written it in.
 *
 * The card read `EXTRA_TITLE` and nothing else, and on this phone that is empty: a `CallStyle`
 * notification carries a `Person` and lets SystemUI build the title from it at draw time, which is
 * a step a notification listener never sees. So the ordering of candidates lives at the call site
 * and the *choosing* lives here, where it can be tested without a phone.
 */
object CallWho {

    /**
     * Phrases that are not a caller.
     *
     * Every one of these is something a dialer writes when it has nothing better, and each one is
     * worth *less* than the next candidate down the list -- a phone number under "Unknown" is the
     * one the card should draw. They are only skipped while something else is left to try: a call
     * that really is anonymous still says so, because "Private number" is a fact and an empty line
     * is a bug.
     */
    private val PLACEHOLDERS = listOf(
        "unknown",
        "unknown caller",
        "unknown number",
        "private",
        "private number",
        "no caller id",
        "restricted",
        "incoming call",
        "ongoing call",
        "call in progress",
        "calling",
        "null",
        "-",
    )

    fun isPlaceholder(text: String?): Boolean {
        val t = text?.trim()?.lowercase() ?: return true
        return t.isEmpty() || t in PLACEHOLDERS
    }

    /**
     * The best of what the notification offered, or "" for a notification that offered nothing.
     *
     * Two passes on purpose. The first takes the first real name or number; only if every
     * candidate is a placeholder does the second pass take the first of those, so an anonymous
     * call reads as the dialer described it rather than as a blank line. The caller supplies the
     * order; this decides nothing about which source is better.
     */
    fun pick(candidates: List<String?>): String {
        val cleaned = candidates.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return cleaned.firstOrNull { !isPlaceholder(it) } ?: cleaned.firstOrNull().orEmpty()
    }

    /**
     * A number out of a `Person` URI.
     *
     * `tel:+15551234567` is what a dialer attaches when the contacts lookup came up empty, which
     * is exactly the call the card most needs to say something about. `sip:` gets the same
     * treatment; a `content://contacts` URI is a row id and is dropped -- an id on a lock screen
     * is worse than nothing.
     */
    fun fromUri(uri: String?): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val body = when {
            raw.startsWith("tel:", ignoreCase = true) -> raw.substring(4)
            raw.startsWith("sip:", ignoreCase = true) -> raw.substring(4).substringBefore('@')
            else -> return null
        }
        // The plus is escaped before decoding, not after. `URLDecoder` is a *form* decoder: it
        // reads a literal `+` as a space, so `tel:+15551234567` came back as a number with the
        // country code silently removed and a space where it had been. Escaping it first means the
        // decoder never sees one, and a real `%2B` still decodes, because `%2B` contains no plus.
        val decoded = runCatching {
            java.net.URLDecoder.decode(body.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(body)
        return decoded.trim().takeIf { it.isNotEmpty() }
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
 * It also decides, in [LockNotifications.isCall], whether a notification from the default dialer
 * is a call at all when nothing else on it says so.
 *
 * Its own object so it can be tested without a phone.
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
