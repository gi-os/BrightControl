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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import com.gios.lightcontrol.keys.LightKey
import com.gios.lightcontrol.keys.LightKeys
import com.gios.lightcontrol.keys.OwnWindow
import com.gios.lightcontrol.ui.AdbScreen
import com.gios.lightcontrol.ui.AppListScreen
import com.gios.lightcontrol.ui.BrightnessScreen
import com.gios.lightcontrol.ui.ButtonsScreen
import com.gios.lightcontrol.ui.ColorAppListScreen
import com.gios.lightcontrol.ui.ColorScreen
import com.gios.lightcontrol.ui.DiagnosticsScreen
import com.gios.lightcontrol.ui.HomeScreen
import com.gios.lightcontrol.ui.IntroScreen
import com.gios.lightcontrol.ui.LockBackgroundScreen
import com.gios.lightcontrol.ui.LockScreenScreen
import com.gios.lightcontrol.ui.PickerScreen
import com.gios.lightcontrol.ui.ResumeAppsScreen
import com.gios.lightcontrol.ui.ResumeFallbackScreen
import com.gios.lightcontrol.ui.SetupScreen
import com.gios.lightcontrol.ui.VolumeScreen
import com.gios.lightcontrol.ui.WheelScreen
import com.gios.lightcontrol.ui.WifiLoginScreen
import com.gios.lightcontrol.ui.theme.LightControlTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.gios.lightcontrol.report.CrashLog
import com.gios.lightcontrol.report.ReportOverlay

/**
 * The settings, as a hub and a set of section screens rather than one long scroll.
 *
 * [Screen.Home] is the root. Each door off it is one level deep, except the pickers that hang off
 * a list ([Screen.Pick] under Buttons, [Screen.ResumeFallback] under ResumeApps) — [parentOf]
 * encodes where Back lands, so the whole thing needs no nav library. A first install opens on
 * [Screen.Intro] instead of Home.
 */
private sealed interface Screen {
    data object Intro : Screen
    data object Home : Screen
    data object Setup : Screen
    data object Buttons : Screen
    data object Wheel : Screen
    data object PerAppWheel : Screen
    data object Brightness : Screen
    data object Volume : Screen
    data object Color : Screen
    data object PerAppColor : Screen
    data object Lock : Screen
    data object Background : Screen
    data object ResumeApps : Screen
    data object ResumeFallback : Screen
    data object Adb : Screen
    data object WifiLogin : Screen
    data object Diagnostics : Screen

    /** [fromSettings] only decides where Back returns the picker to. */
    data class Pick(
        val button: Button,
        val gesture: Gesture,
        val fromSettings: Boolean = false,
    ) : Screen
}

class MainActivity : ComponentActivity() {

    private val notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContent {
            LightControlTheme {
                CompositionLocalProvider(LocalNotches provides notches.asSharedFlow()) {
                    val context = LocalContext.current
                    val prefs = remember { Prefs(context) }
                    var screen by remember {
                        mutableStateOf<Screen>(if (prefs.introSeen) Screen.Home else Screen.Intro)
                    }
                    val home = { screen = Screen.Home }

                    BackHandler(enabled = screen != Screen.Home && screen != Screen.Intro) {
                        screen = parentOf(screen)
                    }

                    when (val current = screen) {
                        Screen.Intro -> IntroScreen(
                            onSetup = { screen = Screen.Setup },
                            onDone = {
                                prefs.introSeen = true
                                screen = Screen.Home
                            },
                        )

                        Screen.Home -> HomeScreen(
                            onGuide = { screen = Screen.Intro },
                            onButtons = { screen = Screen.Buttons },
                            onWheel = { screen = Screen.Wheel },
                            onBrightness = { screen = Screen.Brightness },
                            onColor = { screen = Screen.Color },
                            onLock = { screen = Screen.Lock },
                            onVolume = { screen = Screen.Volume },
                            onAdb = { screen = Screen.Adb },
                            onWifiLogin = { screen = Screen.WifiLogin },
                            onSetup = { screen = Screen.Setup },
                            onDiagnostics = { screen = Screen.Diagnostics },
                        )

                        Screen.Setup -> SetupScreen(
                            onAdb = { screen = Screen.Adb },
                            onBack = home,
                        )

                        Screen.Buttons -> ButtonsScreen(
                            onPick = { button, gesture -> screen = Screen.Pick(button, gesture) },
                            onResumeApps = { screen = Screen.ResumeApps },
                            onBack = home,
                        )

                        Screen.Wheel -> WheelScreen(
                            onPerApp = { screen = Screen.PerAppWheel },
                            onBack = home,
                        )

                        Screen.PerAppWheel -> AppListScreen(onBack = { screen = Screen.Wheel })

                        Screen.Brightness -> BrightnessScreen(onBack = home)

                        Screen.Volume -> VolumeScreen(onBack = home)

                        Screen.Color -> ColorScreen(
                            onPerApp = { screen = Screen.PerAppColor },
                            onAdb = { screen = Screen.Adb },
                            onBack = home,
                        )

                        Screen.PerAppColor -> ColorAppListScreen(onBack = { screen = Screen.Color })

                        Screen.Lock -> LockScreenScreen(
                            onBackground = { screen = Screen.Background },
                            onResumeApps = { screen = Screen.ResumeApps },
                            onResumeFallback = { screen = Screen.ResumeFallback },
                            onBack = home,
                        )

                        Screen.Background -> LockBackgroundScreen(onClose = { screen = Screen.Lock })

                        Screen.ResumeApps -> ResumeAppsScreen(
                            onBack = home,
                            onChooseFallback = { screen = Screen.ResumeFallback },
                        )

                        Screen.ResumeFallback -> ResumeFallbackScreen(
                            onBack = { screen = Screen.ResumeApps },
                        )

                        Screen.Adb -> AdbScreen(onBack = home)

                        Screen.WifiLogin -> WifiLoginScreen(onBack = home)

                        Screen.Diagnostics -> DiagnosticsScreen(onBack = home)

                        is Screen.Pick -> PickerScreen(
                            button = current.button,
                            gesture = current.gesture,
                            onDone = {
                                screen = if (current.fromSettings) Screen.Home else Screen.Buttons
                            },
                            onChooseResumeApps = { screen = Screen.ResumeApps },
                        )
                    }
                }
                ReportOverlay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        OwnWindow.resumed = true
    }

    override fun onPause() {
        super.onPause()
        OwnWindow.resumed = false
    }

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

/** Where Back goes from each screen. Most return to Home; a few hang off a parent list. */
private fun parentOf(screen: Screen): Screen = when (screen) {
    Screen.PerAppWheel -> Screen.Wheel
    Screen.PerAppColor -> Screen.Color
    Screen.Background -> Screen.Lock
    Screen.ResumeFallback -> Screen.ResumeApps
    is Screen.Pick -> if (screen.fromSettings) Screen.Home else Screen.Buttons
    else -> Screen.Home
}

/** Wheel notches, for whichever list is on screen. */
val LocalNotches = staticCompositionLocalOf<SharedFlow<Int>?> { null }
