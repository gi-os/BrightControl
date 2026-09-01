package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.SplitMode
import com.gios.lightcontrol.audio.QuietNotes
import com.gios.lightcontrol.audio.RingerSplit
import kotlinx.coroutines.delay

/**
 * Calls apart from everything else.
 *
 * The screen has one job beyond the three-way choice, and it is to be honest about the constraint:
 * this phone has one volume for a ring and a text message, that is a decision in the ROM, and
 * neither of the two things offered here is a notification volume slider because there is no such
 * number to slide. Saying so is not an apology. A person who knows why the obvious control is
 * missing stops looking for it.
 *
 * There is deliberately no field to type the two levels into. See `audio.SplitDecision.learn`.
 */
@Composable
fun RingerSplitScreen(onBack: () -> Unit, onAdb: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val split = remember { RingerSplit(context, prefs) {} }
    val quiet = remember { QuietNotes(context, prefs) }

    var mode by remember { mutableStateOf(prefs.splitMode) }
    var ringLevel by remember { mutableStateOf(prefs.splitRingLevel) }
    var notifyLevel by remember { mutableStateOf(prefs.splitNotifyLevel) }
    var now by remember { mutableStateOf(split.level()) }
    var down by remember { mutableStateOf(split.ringerDown()) }
    var quietOn by remember { mutableStateOf(quiet.active()) }
    val max = remember { split.maxLevel() }
    // Read on every composition rather than remembered: the grant can land while this screen is
    // open, from the ADB screen one tap away.
    val granted = quiet.granted()

    // The levels are learned from the hardware keys, so this screen is a place people will stand
    // while pressing them. Without the poll the numbers it teaches would not move as you taught it.
    LaunchedEffect(mode) {
        while (true) {
            now = split.level()
            down = split.ringerDown()
            ringLevel = prefs.splitRingLevel
            notifyLevel = prefs.splitNotifyLevel
            quietOn = quiet.active()
            delay(1_000)
        }
    }

    fun choose(next: SplitMode) {
        mode = next
        prefs.splitMode = next
        if (next == SplitMode.TwoLevels) split.seed()
        // Whichever one is being left has to be unwound, and it has to be unwound here rather than
        // at the next service event: a Do Not Disturb left on by a screen somebody walked away from
        // is one this phone has no other way to switch off.
        runCatching { quiet.sync(next == SplitMode.Quiet) }
        // This instance is not the one the service runs and does not know a call is happening. It
        // must therefore not be the thing that decides a boost is over — the service's copy owns
        // that, and calling in here mid-ring would put the level down while the phone was ringing.
        if (!prefs.splitBoosted) split.apply("settings")
        ringLevel = prefs.splitRingLevel
        notifyLevel = prefs.splitNotifyLevel
        now = split.level()
        quietOn = quiet.active()
    }

    SectionScaffold(
        title = "Calls apart",
        onBack = onBack,
        guide = "This phone gives a call and a text message the same voice, and that is not a " +
            "setting anywhere. Android ties the notification volume to the ring volume in the " +
            "ROM, so there is one number and both use it. What can be separated is when it " +
            "applies, and there are two honest ways to do that.",
    ) {
        SectionLabel("CHOOSE ONE")
        MenuRow(
            label = "Off",
            detail = if (mode == SplitMode.Off) "ON" else "",
            sub = "one volume for both, which is what the phone does on its own",
            dim = mode != SplitMode.Off,
            onClick = { choose(SplitMode.Off) },
        )
        MenuRow(
            label = "Calls ring, the rest is silent",
            detail = if (mode == SplitMode.Quiet) "ON" else "",
            sub = "Do Not Disturb with calls on the allow list. Notifications still arrive and " +
                "still appear on the lock face — they make no sound. Alarms and music are " +
                "untouched. Nothing is turned down, so there is nothing to put back.",
            dim = mode != SplitMode.Quiet,
            onClick = { choose(SplitMode.Quiet) },
        )
        MenuRow(
            label = "Two levels",
            detail = if (mode == SplitMode.TwoLevels) "ON" else "",
            sub = "notifications stay audible and quieter. The level goes up when the phone " +
                "rings and back down when the call ends, so the first moment of a ring can be " +
                "quiet — the volume applies to a ringtone already playing.",
            dim = mode != SplitMode.TwoLevels,
            onClick = { choose(SplitMode.TwoLevels) },
        )
        Rule()

        if (mode == SplitMode.TwoLevels) {
            SectionLabel("THE TWO LEVELS")
            GuideText(
                "There is nothing to set here. The volume keys already move this number and " +
                    "always have — all this adds is remembering which of the two you meant. " +
                    "Press them while the phone is ringing and you have set the ring. Press them " +
                    "at any other time and you have set everything else.",
            )
            MenuRow(
                label = "A ring",
                detail = "$ringLevel / $max",
                sub = "where the level goes for an incoming call",
                dim = true,
            )
            MenuRow(
                label = "Everything else",
                detail = "$notifyLevel / $max",
                sub = "where it sits the rest of the time, and where the end of a call puts it " +
                    "back to",
                dim = true,
            )
            MenuRow(
                label = "Right now",
                detail = if (down) "SILENT" else "$now / $max",
                sub = if (down) {
                    "the phone is on vibrate or silent, so nothing here applies. This never " +
                        "raises a ringer that is not already up."
                } else if (prefs.splitBoosted) {
                    "raised for a call. It goes back when the call ends."
                } else {
                    "the live ring level. Press a volume key and watch which of the two above " +
                        "follows it."
                },
                dim = true,
            )
            Gap(8)
            BigButton(
                label = "FORGET BOTH LEVELS",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                prefs.splitRingLevel = 0
                prefs.splitNotifyLevel = 0
                prefs.splitLast = ""
                split.seed()
                ringLevel = prefs.splitRingLevel
                notifyLevel = prefs.splitNotifyLevel
            }
            Rule()
        }

        if (mode == SplitMode.Quiet) {
            SectionLabel("RIGHT NOW")
            MenuRow(
                label = "Notifications",
                detail = if (quietOn) "SILENT" else "AUDIBLE",
                sub = when {
                    !granted -> "not silent — the grant below is missing, so nothing was applied"
                    quietOn && quiet.route() == QuietNotes.Route.Rule ->
                        "through a Do Not Disturb rule this app owns, which leaves any Do Not " +
                            "Disturb you set yourself alone"
                    quietOn -> "through the phone's own Do Not Disturb switch. This build would " +
                        "not take a rule of its own, so the setting it displaced is remembered " +
                        "and put back when you switch this off."
                    else -> "the rule is registered and not in force"
                },
                dim = true,
            )
            MenuRow(
                label = "Calls, alarms and music",
                sub = "on the allow list, all three. A Do Not Disturb policy is a list of what " +
                    "may still make a sound, so leaving music off it would silence BrightMusic " +
                    "as a side effect of quieting a text message.",
                dim = true,
            )
            Rule()
        }

        if (mode != SplitMode.Off) {
            SectionLabel("WHAT IT NEEDS")
            GrantRow(
                label = "Do Not Disturb access",
                ok = granted,
                fix = "adb shell cmd notification allow_dnd com.gios.lightcontrol",
                sub = when {
                    granted && mode == SplitMode.Quiet ->
                        "granted — the silence can actually be applied"
                    granted -> "granted. Two levels does not need it, but the ringer rules " +
                        "elsewhere in this app do."
                    mode == SplitMode.Quiet ->
                        "not granted, so this does nothing at all. Android counts silencing " +
                            "notifications as a Do Not Disturb operation."
                    else -> "not granted. Two levels works without it — this is here because " +
                        "switching to the other mode would need it."
                },
            )
            if (!granted) {
                MenuRow(
                    label = "Let the phone grant itself",
                    detail = "›",
                    sub = "the ADB screen runs the line above over this phone's own shell",
                    onClick = onAdb,
                )
            }
            Rule()
        }

        SectionLabel("WHY THERE IS NO SLIDER")
        GuideText(
            "Android maps the notification stream onto the ring stream on any phone with a radio " +
                "in it. It is a value compiled into the ROM, not a setting, and no permission an " +
                "app can hold changes it — this app already holds the strongest one there is. So " +
                "a notification volume control would be a second handle on the ring volume, " +
                "which is worse than not having one.",
        )
        if (prefs.splitLast.isNotBlank() && mode == SplitMode.TwoLevels) {
            MenuRow(label = "Learned", sub = prefs.splitLast, dim = true)
        }
    }
}
