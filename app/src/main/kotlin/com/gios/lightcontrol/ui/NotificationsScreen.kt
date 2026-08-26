package com.gios.lightcontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.lock.LockNotes
import com.gios.lightcontrol.notify.AlertHandoff

/**
 * Banners, and the rule that decides what this app draws anywhere.
 *
 * Its own section rather than more rows under Lock screen, because the filter is no longer the
 * lock face's own business: the same list of hidden apps now decides what interrupts you mid-app.
 * "Apps never shown" and "Permanent notifications" moved here from Lock screen for that reason --
 * a rule two features read should not live inside one of them, where the coupling is invisible and
 * the next change to the lock face quietly breaks the banner.
 */
@Composable
fun NotificationsScreen(onHiddenApps: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var banner by remember { mutableStateOf(prefs.banner) }
    var wake by remember { mutableStateOf(prefs.bannerWake) }
    var dwell by remember { mutableStateOf(prefs.bannerDwellMs) }
    var persistent by remember { mutableStateOf(prefs.lockPersistent) }
    val hiddenApps = prefs.lockHiddenApps().size
    val granted = LockNotes.granted(context)
    val consumers = remember { AlertHandoff.installed(context) }

    SectionScaffold(
        title = "Notifications",
        onBack = onBack,
        guide = "LightOS shows a new notification as a line in its own list and nothing else, so " +
            "something that arrives while you are in an app is something you find out about later. " +
            "A banner is the box over the top: the app, who it is from, and two lines of it. It " +
            "never buzzes -- the app that sent it already did -- and everything below decides what " +
            "is worth one.",
    ) {
        MenuRow(
            label = "Banners",
            detail = when {
                !banner -> "OFF"
                granted -> "ON"
                else -> "NO GRANT"
            },
            dim = banner && !granted,
            sub = when {
                banner && !granted ->
                    "adb shell cmd notification allow_listener " +
                        "com.gios.lightcontrol/.lock.LockNotifications"
                banner ->
                    "a box over whatever the phone is showing, for any app. Tap it to open the " +
                        "app, swipe up to send it away, or leave it and it goes on its own."
                else ->
                    "off. Nothing appears over the screen; notifications stay in LightOS's list " +
                        "and on the lock face, where they always were."
            },
            onClick = {
                banner = !banner
                prefs.banner = banner
                // The other apps have to be told either way round: switching this on asks them to
                // stand down, and switching it off has to give them their own box straight back.
                AlertHandoff.announce(context)
            },
        )
        if (banner) {
            MenuRow(
                label = "Wake the screen",
                detail = if (wake) "ON" else "OFF",
                sub = if (wake) {
                    "a banner on a sleeping phone turns the panel on for as long as the box is up. " +
                        "The lock face, if it is on, is what you land on -- the note is already a " +
                        "row on it, so no box is drawn over the top of the same thing twice."
                } else {
                    "off. The banner waits for the screen to already be on, so a phone face-down " +
                        "on a desk stays dark and you find out when you pick it up."
                },
                onClick = {
                    wake = !wake
                    prefs.bannerWake = wake
                },
            )
            MenuRow(
                label = "How long it stays",
                detail = "${dwell / 1000f}".removeSuffix(".0") + "s",
                sub = "long enough to read two lines, short enough not to sit in front of what " +
                    "you were doing",
                onClick = {
                    dwell = when (dwell) {
                        3_000L -> 4_500L
                        4_500L -> 7_000L
                        else -> 3_000L
                    }
                    prefs.bannerDwellMs = dwell
                },
            )
        }
        Rule()

        SectionLabel("WHAT IS WORTH ONE")
        MenuRow(
            label = "Apps never shown",
            detail = if (hiddenApps == 0) "NONE" else "$hiddenApps",
            sub = "hide a source by name. The same list the lock face reads -- hide it here and " +
                "it is gone from both. Nothing is cancelled and the shade is untouched.",
            onClick = onHiddenApps,
        )
        MenuRow(
            label = "Permanent notifications",
            detail = if (persistent) "SHOWN" else "HIDDEN",
            sub = if (persistent) {
                "shown. A recording, a download, a navigation -- and anything else an app keeps " +
                    "in the shade on purpose. These never raise a banner whatever this says: " +
                    "something that has been there for an hour is not news."
            } else {
                "hidden. The always-running kind, including LightOS's own notice about itself, " +
                    "which is the one that used to sit on the lock face with nothing that would " +
                    "remove it."
            },
            onClick = {
                persistent = !persistent
                prefs.lockPersistent = persistent
                // The lock face may well be up behind these settings, and nothing else would tell
                // the listener the rule it filters by has changed.
                LockNotes.rebuild()
            },
        )
        Rule()

        SectionLabel("THE OTHER APPS")
        MenuRow(
            label = "Their own box stands down",
            detail = when {
                consumers.isEmpty() -> "NONE"
                AlertHandoff.owned(context) -> "${consumers.size} TOLD"
                else -> "THEIRS"
            },
            dim = consumers.isEmpty(),
            sub = when {
                consumers.isEmpty() ->
                    "BrightChat and BrightSports each draw a box of their own. Neither is " +
                        "installed, so there is nothing to arrange."
                AlertHandoff.owned(context) ->
                    "told to stop drawing theirs, so a message is one box and not two. Their buzz " +
                        "and their notification are untouched, and turning banners off gives them " +
                        "straight back."
                else ->
                    "drawing their own, which is right while this one is off or ungranted. Switch " +
                        "banners on above and they are asked to stand down."
            },
        )
        Rule()

        SectionLabel("READING THE SHADE")
        GrantRow(
            label = "Notification listener",
            ok = granted,
            fix = "adb shell cmd notification allow_listener " +
                "com.gios.lightcontrol/.lock.LockNotifications",
            sub = "the same grant the lock face uses. Nothing is stored, nothing leaves the " +
                "phone, and the list is rebuilt from the shade on every change.",
        )
        MenuRow(
            label = "No overlay grant needed",
            sub = "the banner is an accessibility window, which is how it draws above the lock " +
                "screen as well. SYSTEM_ALERT_WINDOW is not involved.",
            dim = true,
        )
    }
}
