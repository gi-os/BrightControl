package com.gios.lightcontrol

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.gios.lightcontrol.keys.LightKey
import com.gios.lightcontrol.keys.LightKeys
import com.gios.lightcontrol.keys.OwnWindow
import com.gios.lightcontrol.ui.AppListScreen
import com.gios.lightcontrol.ui.ButtonsScreen
import com.gios.lightcontrol.ui.PickerScreen
import com.gios.lightcontrol.ui.ResumeAppsScreen
import com.gios.lightcontrol.ui.ResumeFallbackScreen
import com.gios.lightcontrol.ui.SettingsScreen
import com.gios.lightcontrol.ui.theme.LightControlTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay

/** Six screens, one level deep each — a nav library would be more code than this. */
private sealed interface Screen {
    data object Settings : Screen
    data object Buttons : Screen
    data object Apps : Screen
    data object ResumeApps : Screen
    data object ResumeFallback : Screen
    /** [fromSettings] is only so Back returns where the picker was opened from. */
    data class Pick(
        val button: Button,
        val gesture: Gesture,
        val fromSettings: Boolean = false,
    ) : Screen
}

/**
 * The settings screens, and the one place the wheel is handled in-app rather than in the
 * service.
 *
 * LightControl resolves to `ScrollThrough` for itself — it's a `com.gios.` package — so the
 * service passes turns straight here. Which is the point being demonstrated: an app that
 * handles the wheel scrolls per notch, and one that doesn't gets brightness or a swipe.
 */
class MainActivity : ComponentActivity() {

    private val notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        LightReport.install(
            context = this,
            appName = "LightControl",
            label = "control",
            token = BuildConfig.REPORT_TOKEN,
        )
        setContent {
            LightControlTheme {
                CompositionLocalProvider(LocalNotches provides notches.asSharedFlow()) {
                    var screen by remember { mutableStateOf<Screen>(Screen.Settings) }
                    val home = { screen = Screen.Settings }

                    BackHandler(enabled = screen != Screen.Settings) {
                        // The picker belongs to the buttons screen, unless it was opened straight
                        // from the settings screen's home-button row.
                        val current = screen
                        screen = when {
                            current is Screen.Pick && !current.fromSettings -> Screen.Buttons
                            current is Screen.ResumeFallback -> Screen.ResumeApps
                            else -> Screen.Settings
                        }
                    }

                    when (val current = screen) {
                        Screen.Settings -> SettingsScreen(
                            onButtons = { screen = Screen.Buttons },
                            onPerApp = { screen = Screen.Apps },
                            onHomeTap = {
                                screen = Screen.Pick(Button.Home, Gesture.Tap, fromSettings = true)
                            },
                            onResumeApps = { screen = Screen.ResumeApps },
                        )

                        Screen.Buttons -> ButtonsScreen(
                            onPick = { button, gesture -> screen = Screen.Pick(button, gesture) },
                            onBack = home,
                        )

                        Screen.Apps -> AppListScreen(onBack = home)

                        Screen.ResumeApps -> ResumeAppsScreen(
                            onBack = home,
                            onChooseFallback = { screen = Screen.ResumeFallback },
                        )

                        // Back from here returns to the resume list rather than all the way out,
                        // because it was opened from a row on it.
                        Screen.ResumeFallback -> ResumeFallbackScreen(
                            onBack = { screen = Screen.ResumeApps },
                        )

                        is Screen.Pick -> PickerScreen(
                            button = current.button,
                            gesture = current.gesture,
                            onDone = {
                                screen =
                                    if (current.fromSettings) Screen.Settings else Screen.Buttons
                            },
                            onChooseResumeApps = { screen = Screen.ResumeApps },
                        )
                    }
                }
                // Shake to report, the crash offer on next launch, and the app's own noticed
                // failures. A sibling, not a wrapper — the sheet is its own window, so it covers
                // the app whether or not it contains it.
                ReportOverlay()
            }
        }
    }

    /** The service reads this rather than trusting a window-state event from our overlay. */
    override fun onResume() {
        super.onResume()
        OwnWindow.resumed = true
    }

    override fun onPause() {
        super.onPause()
        OwnWindow.resumed = false
    }

    /**
     * The wheel, in here. The service passes turns through for this package, and the activity
     * is the only thing that sees them before the view hierarchy does.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) notches.tryEmit(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) notches.tryEmit(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Wheel notches, for whichever list is on screen. */
val LocalNotches = staticCompositionLocalOf<SharedFlow<Int>?> { null }
