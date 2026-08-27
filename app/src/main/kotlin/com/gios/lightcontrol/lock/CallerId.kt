package com.gios.lightcontrol.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager

/**
 * Who is calling, out of telephony, for a phone whose dialer says nothing.
 *
 * ### Why this exists at all
 *
 * The card's first source is the call notification, and on this phone that came back empty twice
 * over: no title, then no `Person`, then -- the actual answer -- **no notification**. LightOS's
 * dialer is a system app showing its own activity; it does not have to go through the shade to
 * take over the screen, and it does not. So a notification listener is the wrong window to be
 * watching, and every name the card could have drawn was in a process this app cannot read.
 *
 * The platform will still say who is calling to anything holding the right two permissions.
 * `ACTION_PHONE_STATE_CHANGED` carries the number on the ringing broadcast, `PhoneLookup` turns
 * that into the name the user gave the contact, and neither needs this app to be the dialer,
 * the screener, or anything else it cannot be.
 *
 * ### The grants
 *
 * `READ_PHONE_STATE` delivers the broadcast, `READ_CALL_LOG` is what makes the number in it
 * non-empty -- Android P moved it behind that permission -- and `READ_CONTACTS` is the name.
 * All three are runtime permissions with no LightOS screen to grant them, so they go the way
 * everything else in this app goes: the ADB screen, in one run. Without them this is inert and
 * the card falls back to the words it had before, which is the same failure as today rather than
 * a new one.
 *
 * Nothing is stored. The number and name live in this object for the length of the call and are
 * dropped when the line goes idle.
 */
class CallerId(private val context: Context) {

    /**
     * What the line is doing, as telephony last said it.
     *
     * The state, kept apart from the number, because they do not arrive together and only one of
     * them is guaranteed. `ACTION_PHONE_STATE_CHANGED` always says RINGING; the number on it is
     * empty whenever the caller withheld it, whenever the network sent none, and whenever
     * `READ_CALL_LOG` is missing. This class used to drop the entire broadcast in that case --
     * `raw.isNullOrEmpty() -> return` -- so a call from a number the phone could not name told
     * nothing downstream that a call was happening at all, and the lock face showed the clock
     * through the whole ring. The number is a detail of the call. That there *is* a call is the
     * thing the face cannot find out anywhere else on this phone.
     */
    enum class Line { Idle, Ringing, Offhook }

    @Volatile
    var line: Line = Line.Idle
        private set

    /**
     * When [line] last changed, on the monotonic clock.
     *
     * Read by [LockCall] so a ring that telephony alone is asserting cannot hold the card up for
     * ever. A missed IDLE broadcast is a lock screen that says the phone is ringing until reboot,
     * and a stuck lock face is the worst failure this app can produce.
     */
    @Volatile
    var lineAt: Long = 0L
        private set

    /** The name from contacts, or null: no contact, no grant, or nothing to look up yet. */
    @Volatile
    var name: String? = null
        private set

    /** The number as dialed, formatted for reading. */
    @Volatile
    var number: String? = null
        private set

    /** Told when either of the two changes, on the receiver's thread. */
    var onChange: (() -> Unit)? = null

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val next = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> Line.Ringing
                TelephonyManager.EXTRA_STATE_OFFHOOK -> Line.Offhook
                else -> Line.Idle
            }
            if (next == Line.Idle) {
                val had = line != Line.Idle
                forget()
                if (had) runCatching { onChange?.invoke() }
                return
            }
            var changed = false
            if (next != line) {
                line = next
                lineAt = SystemClock.elapsedRealtime()
                changed = true
            }
            @Suppress("DEPRECATION")
            val raw = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.trim()
            // Kept, not cleared, when the extra is absent. Only the ringing broadcast carries the
            // number; the off-hook one that follows it does not, and answering a call must not be
            // what erases the name from the screen. What is *not* done any more is dropping the
            // broadcast itself -- see [Line].
            if (!raw.isNullOrEmpty()) {
                number = pretty(raw)
                name = lookup(raw)
                changed = true
            }
            if (changed) runCatching { onChange?.invoke() }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A protected system broadcast, so exported is the only sensible flag -- and from
                // Android 14 one of the two flags is compulsory or the call throws.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)
        registered = ok
    }

    fun stop() {
        if (registered) {
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
        }
        forget()
    }

    /** Drop the call. Called when the line goes idle and when the face lets go. */
    fun forget() {
        name = null
        number = null
        line = Line.Idle
        lineAt = SystemClock.elapsedRealtime()
    }

    /**
     * A ring with nobody's number on it, on a phone that would have been told one.
     *
     * The grants are part of the question. Without them an empty number means "we were not
     * allowed to know", which is this app's problem and not worth saying on a lock screen; with
     * them it means the network sent none, which is a fact about the caller and worth drawing.
     */
    fun withheld(): Boolean = line == Line.Ringing && number == null && granted()

    /** Whether the phone will actually tell us the number, for the diagnostics log. */
    fun granted(): Boolean =
        granted(android.Manifest.permission.READ_PHONE_STATE) &&
            granted(android.Manifest.permission.READ_CALL_LOG)

    private fun lookup(number: String): String? {
        if (!granted(android.Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * The number, grouped the way the phone's own region groups them.
     *
     * Cosmetic, and worth it: a lock screen is read at arm's length, and eleven unbroken digits at
     * that distance is a smear. Falls back to exactly what arrived, which is always readable.
     */
    private fun pretty(raw: String): String = runCatching {
        val iso = context.getSystemService(TelephonyManager::class.java)
            ?.networkCountryIso
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
        PhoneNumberUtils.formatNumber(raw, iso ?: "US")?.takeIf { it.isNotBlank() } ?: raw
    }.getOrDefault(raw)

    private fun granted(permission: String): Boolean = runCatching {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}

/**
 * The two lines of the call card, chosen from everything that might know a caller.
 *
 * Pure, and separate from both sources, because this is the part that has been wrong twice and
 * neither time was the bug in how a number was read -- it was in what got drawn when one source
 * was empty. Here it can be held to the answer without a phone in the room.
 */
object CallerText {

    /** What the card draws: [who] large, [sub] under it, either possibly empty. */
    data class Lines(val who: String, val sub: String)

    /**
     * @param noteWho the name off the call notification, when there is one.
     * @param noteText that notification's second line.
     * @param name the contact name telephony and contacts agreed on.
     * @param number the number that is calling.
     * @param ringing whether this is a ring or a call in progress, for the last-resort wording.
     * @param withheld the phone was told a call was coming and told no number with it.
     *
     * Order: the dialer's own name for the caller, then the contact name, then the number. The
     * notification comes first where it exists because a dialer that bothered to write a name has
     * usually done something this app cannot -- a business lookup, a spam label, a SIM name.
     *
     * The second line is never the first line again, and never a phrase the card's own heading
     * already says. A number under a name is the useful case, and it is the common one.
     */
    fun of(
        noteWho: String?,
        noteText: String?,
        name: String?,
        number: String?,
        ringing: Boolean,
        withheld: Boolean = false,
    ): Lines {
        // "Incoming call" under a card whose own label already reads INCOMING CALL is the same
        // words twice and says nothing. Where telephony announced the ring and sent no number
        // with it, there is something better to say, and it is the truth: nobody's number came.
        val nothing = when {
            withheld -> "Unknown number"
            ringing -> "Incoming call"
            else -> "On a call"
        }
        val who = CallWho.pick(listOf(noteWho, name, number)).ifBlank { nothing }
        val sub = listOf(noteText, number)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it != who && !CallWho.isPlaceholder(it) }
            .orEmpty()
        return Lines(who, sub)
    }
}
