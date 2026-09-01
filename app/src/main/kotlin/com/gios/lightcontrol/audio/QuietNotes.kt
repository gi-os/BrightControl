package com.gios.lightcontrol.audio

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import com.gios.lightcontrol.Prefs

/**
 * Silent notifications on a phone that will still ring.
 *
 * ### Why this is not a volume setting
 *
 * Because there is no notification volume to set. `STREAM_NOTIFICATION` is aliased onto
 * `STREAM_RING` in the ROM — see [SplitDecision] for the whole of that — so the number a person
 * would reach for to make text messages quieter is the same number that decides whether they hear
 * the phone ring. There is no index that means "loud calls, silent texts".
 *
 * Do Not Disturb is a different axis entirely, and it is the one that has the distinction already
 * built in: a priority filter is a list of *what is allowed to make a sound*, and calls and messages
 * are separate entries on it. Allow calls, deny the rest, and the phone rings at its normal ring
 * volume while everything else lands in the shade without a sound. Nothing is turned down. This is
 * the exact answer to the question, and it needs no ringtone timing, no restore, and no marker.
 *
 * ### An owned rule, not the global switch
 *
 * `setInterruptionFilter` is one setting shared by everything on the phone, and this codebase has
 * already paid for writing one of those from two places — see the two-writer note in
 * `color.ColorService`. `addAutomaticZenRule` gives this app a rule of its own with a policy of its
 * own, which the framework combines with whatever else is in force. Switching it on and off is then
 * a state change on our rule rather than an assignment to a global, and a Do Not Disturb somebody
 * turned on by hand is not silently overwritten by this one turning off.
 *
 * The rule is registered with a `configurationActivity` and no owner, which is what the API 29
 * constructor is for: an owner is a `ConditionProviderService` the system binds to, and this app
 * does not need the system to ask it anything. It only needs to be able to say so, which the
 * package that added a rule always can.
 *
 * [Route.Filter] is the fallback for a build that refuses the rule. It writes the global filter and
 * the global policy, so it also captures the four numbers of the policy it displaced and puts them
 * back on the way out — persisted, because the process that has to put them back is not always the
 * process that took them.
 *
 * ### What it needs
 *
 * Notification policy access, the same grant `audio.WifiRinger` needs to mute:
 * `cmd notification allow_dnd com.gios.lightcontrol`. Without it every call in here throws and the
 * feature is inert, which the settings screen says rather than leaving it to be discovered.
 */
class QuietNotes(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext

    /** How the silence is actually being applied, for the settings screen to admit to. */
    enum class Route { Rule, Filter, None }

    private val nm: NotificationManager?
        get() = runCatching { app.getSystemService(NotificationManager::class.java) }.getOrNull()

    /** Whether Do Not Disturb is ours to touch at all. */
    fun granted(): Boolean = runCatching {
        nm?.isNotificationPolicyAccessGranted == true
    }.getOrDefault(false)

    /**
     * Put the phone in the state the setting asks for. Idempotent, and safe to call at any time.
     *
     * Called from `keys.ControlService.onCreate` as well as from the settings screen, which is the
     * guard against the worst failure this feature has: LightOS ships no Do Not Disturb screen, so
     * a phone left in DND by a crashed process could not be got out of it by any means except this
     * app. Every start therefore asserts the *off* state as firmly as the on state.
     */
    fun sync(on: Boolean) {
        if (!granted()) {
            prefs.quietRoute = Route.None.name
            return
        }
        if (on) turnOn() else turnOff()
    }

    private fun turnOn() {
        val manager = nm ?: return
        val viaRule = runCatching {
            val id = ruleId(manager) ?: return@runCatching false
            manager.setAutomaticZenRuleState(id, Condition(CONDITION, SUMMARY, Condition.STATE_TRUE))
            prefs.quietRuleId = id
            true
        }.getOrDefault(false)
        if (viaRule) {
            prefs.quietRoute = Route.Rule.name
            return
        }
        runCatching {
            // Remember what was there before this displaced it. Four ints, because that is what a
            // Policy is, and a Parcelable is not a thing to keep in SharedPreferences.
            val had = manager.notificationPolicy
            if (had != null && !prefs.quietHeldPolicy) {
                prefs.quietPolicyCategories = had.priorityCategories
                prefs.quietPolicyCallSenders = had.priorityCallSenders
                prefs.quietPolicyMessageSenders = had.priorityMessageSenders
                prefs.quietPolicyVisual = had.suppressedVisualEffects
                prefs.quietHeldPolicy = true
            }
            manager.notificationPolicy = NotificationManager.Policy(
                CATEGORIES,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                // Nothing hidden. A notification that made no sound is still a notification, and
                // this app's own lock face and banners are drawing it.
                0,
            )
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            prefs.quietRoute = Route.Filter.name
        }
    }

    private fun turnOff() {
        val manager = nm ?: return
        // Both routes are unwound whatever the last one was. A build that started on the rule and
        // fell back to the filter after an update would otherwise leave one of them latched.
        runCatching {
            val id = prefs.quietRuleId.ifBlank { existingRuleId(manager) }
            if (id.isNotBlank()) {
                manager.setAutomaticZenRuleState(
                    id,
                    Condition(CONDITION, SUMMARY, Condition.STATE_FALSE),
                )
            }
        }
        if (prefs.quietHeldPolicy) {
            runCatching {
                manager.notificationPolicy = NotificationManager.Policy(
                    prefs.quietPolicyCategories,
                    prefs.quietPolicyCallSenders,
                    prefs.quietPolicyMessageSenders,
                    prefs.quietPolicyVisual,
                )
            }
            prefs.quietHeldPolicy = false
        }
        if (prefs.quietRoute == Route.Filter.name) {
            runCatching {
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        prefs.quietRoute = Route.None.name
    }

    /**
     * Take the rule off the phone entirely.
     *
     * For an uninstall-shaped moment — the feature being switched off is [turnOff], which leaves the
     * rule in place and merely false. A rule costs nothing switched off and keeping it means the
     * next switch-on is one call rather than three.
     */
    fun forget() {
        runCatching {
            val manager = nm ?: return
            turnOff()
            val id = prefs.quietRuleId.ifBlank { existingRuleId(manager) }
            if (id.isNotBlank()) manager.removeAutomaticZenRule(id)
        }
        prefs.quietRuleId = ""
    }

    /** Whether the silence is in force right now, asked of the phone rather than of a pref. */
    fun active(): Boolean = runCatching {
        val manager = nm ?: return false
        when (prefs.quietRoute) {
            Route.Rule.name -> manager.currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
            Route.Filter.name -> manager.currentInterruptionFilter ==
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else -> false
        }
    }.getOrDefault(false)

    fun route(): Route = runCatching { Route.valueOf(prefs.quietRoute) }.getOrDefault(Route.None)

    /** Our rule's id, added if this phone has not got one yet. Null when the phone refused it. */
    private fun ruleId(manager: NotificationManager): String? {
        existingRuleId(manager).takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            manager.addAutomaticZenRule(
                AutomaticZenRule(
                    NAME,
                    null,
                    ComponentName(app.packageName, "com.gios.lightcontrol.MainActivity"),
                    CONDITION,
                    policy(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    true,
                ),
            )
        }.getOrNull()
    }

    private fun existingRuleId(manager: NotificationManager): String = runCatching {
        manager.automaticZenRules
            ?.entries
            ?.firstOrNull { it.value?.conditionId == CONDITION }
            ?.key
            .orEmpty()
    }.getOrDefault("")

    /**
     * What is still allowed to make a noise.
     *
     * Alarms, media and system are on this list and their absence is the trap. A `ZenPolicy` is a
     * whitelist, so a policy written to silence text messages and nothing else silences an alarm
     * clock and the music as well, which on a phone that runs BrightMusic is a feature that appears
     * to break the audio.
     */
    private fun policy(): ZenPolicy {
        val builder = ZenPolicy.Builder()
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowReminders(false)
            .allowEvents(false)
            .allowAlarms(true)
            .allowMedia(true)
            .allowSystem(true)
            // Everything still appears. This silences a phone; it does not hide anything from it.
            .showAllVisualEffects()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
        }
        return builder.build()
    }

    private companion object {
        const val NAME = "BrightControl · silent notifications"
        const val SUMMARY = "Calls ring, everything else is silent"

        val CONDITION: Uri =
            Uri.parse("condition://com.gios.lightcontrol/notifications-quiet")

        /** The fallback route's `Policy`, which is the same whitelist as [policy] in int form. */
        const val CATEGORIES = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or
            NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM
    }
}
