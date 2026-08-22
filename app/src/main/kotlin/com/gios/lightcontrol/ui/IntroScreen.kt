package com.gios.lightcontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.ui.theme.Dim

/**
 * The first thing a new install shows: what the app is for, the one grant that matters, and how
 * to give it. Reachable again any time from Setup. Kept to a single scroll — an ADHD-friendly
 * "here is the whole thing" rather than a wizard you tap through blind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroScreen(onSetup: () -> Unit, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Welcome", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            Para(
                "BrightControl makes the Light Phone III's wheel and side buttons work inside " +
                    "apps Light didn't write — and adds a few things LightOS leaves out.",
            )
            Heading("What it does")
            Bullet("The wheel", "changes brightness, or scrolls, in any app.")
            Bullet("The buttons", "the wheel click and camera key become bindings you choose.")
            Bullet("The home button", "can open Luma or any launcher instead of LightOS.")
            Bullet("Color", "force full color in the apps you pick, on a phone that is mono " +
                "everywhere else.")
            Bullet("A lock face", "a Light-style clock, notifications and your own photo over the " +
                "stock lock screen.")
            Bullet("Readouts", "a brightness and a volume bar, which LightOS never shows.")

            Heading("The one thing to do first")
            Para(
                "This works by watching key presses, which needs the accessibility service turned " +
                    "on. LightOS has no screen to turn it on, so it is granted once over adb — " +
                    "either from a computer, or by the phone itself on the ADB screen.",
            )
            Para(
                "Until the service is on, nothing here does anything. Everything else — brightness, " +
                    "overlays, color — is a second set of grants the same two ways can give.",
            )

            Heading("If a button ever misbehaves")
            Para(
                "There is an EVERYTHING OFF switch at the top of the app. It hands every key back " +
                    "to the phone at once, so a bad binding is never something you're stuck with.",
            )

            Gap(8)
            SetupCta(onSetup)
            Gap(4)
            DoneCta(onDone)
            Gap(28)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Gap(20)
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Gap(4)
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun Bullet(label: String, rest: String) {
    Text(
        "· $label — $rest",
        style = MaterialTheme.typography.bodyMedium,
        color = Dim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SetupCta(onSetup: () -> Unit) {
    MenuRow(
        label = "Set it up now",
        detail = "›",
        sub = "grants, with the adb lines and the on-phone shortcut",
        onClick = onSetup,
    )
}

@Composable
private fun DoneCta(onDone: () -> Unit) {
    MenuRow(
        label = "Go to the app",
        detail = "→",
        sub = "you can reopen this from Setup any time",
        onClick = onDone,
    )
}
