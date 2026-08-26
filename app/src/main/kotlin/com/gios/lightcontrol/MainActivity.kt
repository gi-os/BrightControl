package com.gios.lightcontrol

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.gios.lightcontrol.ui.GrantRequestScreen
import com.gios.lightcontrol.ui.HomeScreen
import com.gios.lightcontrol.ui.IntroScreen
import com.gios.lightcontrol.ui.LockBackgroundScreen
import com.gios.lightcontrol.ui.LockScreenScreen
import com.gios.lightcontrol.ui.PickerScreen
import com.gios.lightcontrol.ui.ResumeAppsScreen
import com.gios.lightcontrol.ui.ResumeFallbackScreen
import com.gios.lightcontrol.ui.SetupScreen
import com.gios.lightcontrol.ui.VolumeScreen
import com.gios.lightcontrol.ui.LocalCursor
import com.gios.lightcontrol.ui.WheelCursor
import com.gios.lightcontrol.ui.WheelScreen
import com.gios.lightcontrol.ui.HotspotScreen
import com.gios.lightcontrol.ui.WifiLoginScreen
import com.gios.lightcontrol.ui.theme.LightControlTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    data object Hotspot : Screen
    data object Diagnostics : Screen

    /**
     * Another app's ADB setup, handed over by BrightMarket. Carries the request rather than
     * reading it again, so what the user approves is exactly what arrived.
     */
    data class GrantRequestFor(
        val label: String,
        val pkg: String,
        val lines: List<String>,
        /**
         * Minutes this request spent waiting for a connection, when it is one the ADB screen handed
         * back rather than one an app just sent. Null for a fresh arrival.
         */
        val heldMinutes: Long? = null,
    ) : Screen

    /** [fromSettings] only decides where Back returns the picker to. */
    data class Pick(
        val button: Button,
        val gesture: Gesture,
        val fromSettings: Boolean = false,
    ) : Screen
}

class MainActivity : ComponentActivity() {

    private val notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    /**
     * The wheel's highlight, held by the activity rather than by the composition.
     *
     * [dispatchKeyEvent] is where the wheel click arrives, and it is not a composable — so the
     * one object both halves need lives out here. See [WheelCursor].
     */
    private val cursor = WheelCursor()

    /**
     * A grant request that arrived by intent while this activity was already on screen.
     *
     * `launchMode="singleTop"` means a second launch does not run [onCreate] again — it calls
     * [onNewIntent], and until this existed nothing was listening. So an app tapping "set me up"
     * while BrightControl happened to be open got BrightControl's **home page**, on the page
     * whoever used it last had left it on, and the request was never parsed at all. It looked
     * exactly like the request being ignored, because it was.
     *
     * State rather than a callback: the composition reads it, acts on it, and clears it, which
     * works whether the intent lands before or after the first composition.
     */
    private var arrived by mutableStateOf<Screen?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Kept as *the* intent, because [callerOf] reads the referrer off it and [grantRequestFrom]
        // is asked again on rotation and on any later recomposition.
        setIntent(intent)
        grantRequestFrom(intent)?.let { arrived = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The crash handler is installed by [App], which runs before this and before the
        // accessibility service. Installing it here as well is what made a crash in the service
        // invisible until somebody had opened these settings at least once — light-reports#12.
        setContent {
            LightControlTheme {
                CompositionLocalProvider(
                    LocalNotches provides notches.asSharedFlow(),
                    LocalCursor provides cursor,
                ) {
                    val context = LocalContext.current
                    val prefs = remember { Prefs(context) }
                    LaunchedEffect(Unit) { cursor.enabled = prefs.wheelCursor }
                    // A grant request arriving by intent opens straight onto it, ahead of the
                    // intro: the user did not come here to read a guide, they tapped a button in
                    // another app and expected this one to answer for it.
                    var screen by remember {
                        mutableStateOf<Screen>(
                            grantRequestFrom(intent)
                                ?: if (prefs.introSeen) Screen.Home else Screen.Intro,
                        )
                    }
                    val home = { screen = Screen.Home }

                    // A request that arrived while this was already open. Answered here rather than
                    // in [onNewIntent] because the screen state lives in the composition, and a
                    // request is the one thing that always wins the page: nobody pressed a button
                    // in another app in order to look at this one's home screen.
                    LaunchedEffect(arrived) {
                        arrived?.let {
                            screen = it
                            arrived = null
                        }
                    }

                    // Every screen starts with nothing highlighted. The rows behind it are gone,
                    // and a selection carried across a screen change is a click aimed at whatever
                    // now happens to be in that position.
                    LaunchedEffect(screen) { cursor.reset() }

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
                            onHotspot = { screen = Screen.Hotspot },
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

                        Screen.Adb -> AdbScreen(
                            onBack = home,
                            // A request that was waiting for a connection now has one. Going
                            // straight to it beats leaving somebody on a setup screen whose only
                            // button sets up a different app.
                            onCarriedRequest = { pkg, lines, held ->
                                screen = Screen.GrantRequestFor(
                                    label = labelOf(pkg),
                                    pkg = pkg,
                                    lines = lines,
                                    heldMinutes = held,
                                )
                            },
                        )

                        Screen.WifiLogin -> WifiLoginScreen(onBack = home)
                        Screen.Hotspot -> HotspotScreen(onBack = home)

                        is Screen.GrantRequestFor -> GrantRequestScreen(
                            appLabel = current.label,
                            pkg = current.pkg,
                            lines = current.lines,
                            heldMinutes = current.heldMinutes,
                            onBack = home,
                            onAdb = { screen = Screen.Adb },
                        )

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

    /**
     * A finger on the glass ends wheel selection. See [WheelCursor.selecting].
     *
     * Read here rather than in the composition because it has to be every touch, on every screen,
     * before anything else has a chance to consume it — including the row that is about to be
     * clicked, which is the one touch that most needs the highlight gone.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cursor.touched()
        return super.dispatchTouchEvent(event)
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
            // The click opens whatever the wheel has highlighted. Only when something *is*
            // highlighted: with nothing selected the key belongs to whoever wanted it next,
            // which on this phone is the flashlight.
            LightKey.WheelClick -> {
                if (!cursor.enabled) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_UP) {
                    if (!cursor.click()) return super.dispatchKeyEvent(event)
                }
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

/**
 * The grant request carried by a launching intent, if there is one.
 *
 * Sent by BrightMarket when an app in the catalog declares ADB setup in its README.
 *
 * ## Who is asking is decided here, not stated in the intent
 *
 * This used to read the requesting package out of [EXTRA_PACKAGE] — a string put there by
 * whoever sent the intent — and hand it to [com.gios.lightcontrol.adb.GrantRequest.parse] as the
 * package every command is then checked against. Checking an attacker's lines against an
 * attacker's package name proves nothing: any app on the phone could name any other, and the
 * screen said in as many words that this "is checked here, not taken on trust".
 *
 * The caller's identity now comes from the platform:
 *
 *  1. [Activity.getCallingActivity], which only exists for `startActivityForResult` and is the
 *     strongest answer when it is there.
 *  2. Otherwise the referrer, which the system sets from the launching package. [callerOf] strips
 *     `EXTRA_REFERRER` and `EXTRA_REFERRER_NAME` off the intent first, because
 *     [Activity.getReferrer] prefers those extras over the system's own value and they are as
 *     forgeable as anything else in a Bundle.
 *
 * No verifiable caller means no screen. There is no third source and deliberately no fallback to
 * the extra.
 *
 * ## Brokering, which is what the extra is actually for
 *
 * BrightMarket does not ask for grants for itself; it relays the ones an app in its catalogue
 * declares, and launches this with `startActivity`. So one package — and only that one — may
 * name a different target in [EXTRA_PACKAGE]. Everyone else is pinned to itself: a request from
 * `com.evil.app` naming `com.gios.roll` becomes a request from `com.evil.app` naming
 * `com.gios.roll`, and every line of it is refused, loudly, on the screen built to say so.
 *
 * [EXTRA_LABEL] is ignored outright. The words above the commands come from
 * [PackageManager.getApplicationLabel] for the package that will actually be named in them, so
 * "Set up Roll" is a fact about this phone rather than a claim in a Bundle.
 */
private fun MainActivity.grantRequestFrom(intent: android.content.Intent?): Screen? {
    if (intent?.action != ACTION_RUN_GRANTS) return null
    val caller = callerOf(intent) ?: return null
    val lines = intent.getStringArrayListExtra(EXTRA_COMMANDS)?.filter { it.isNotBlank() }
        ?: return null
    if (lines.isEmpty()) return null
    val asked = intent.getStringExtra(EXTRA_PACKAGE)?.takeIf { it.isNotBlank() }
    // Only the broker may speak for somebody else. Anyone else asking for a package that is not
    // their own is left pointed at their own, so GrantRequest.parse refuses the lot and names
    // the mismatch.
    val target = if (caller == BROKER_PKG && asked != null) asked else caller
    return Screen.GrantRequestFor(label = labelOf(target), pkg = target, lines = lines)
}

/**
 * The package that launched this activity, as the platform knows it, or null.
 *
 * The extras are removed before [Activity.getReferrer] is asked, because that method returns
 * `EXTRA_REFERRER` ahead of the system-set value and both extras travel in the same Bundle as
 * everything else the sender chose. What is left is `mReferrer`, filled in by the activity
 * manager from the launching package.
 */
private fun MainActivity.callerOf(intent: android.content.Intent): String? {
    callingActivity?.packageName?.takeIf { it.isNotBlank() }?.let { return it }
    intent.removeExtra(android.content.Intent.EXTRA_REFERRER)
    intent.removeExtra(android.content.Intent.EXTRA_REFERRER_NAME)
    // `this.referrer` spelled out: a local named `referrer` would shadow the property it is
    // being initialised from.
    val from = runCatching { this.referrer }.getOrNull() ?: return null
    if (from.scheme != "android-app") return null
    return from.host?.takeIf { it.isNotBlank() }
}

/** An installed app's own name, falling back to its package id when it is not installed. */
private fun MainActivity.labelOf(pkg: String): String = runCatching {
    val info = packageManager.getApplicationInfo(pkg, 0)
    packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: pkg
}.getOrDefault(pkg)

/**
 * The one app allowed to ask on another app's behalf. See [grantRequestFrom].
 *
 * A package name, not a signature. Two packages with the same id cannot both be installed, so
 * this is not spoofable while BrightMarket is on the phone — and if it is not, there is no
 * brokered request to honour in the first place. Pinning its certificate here as well would be
 * stronger and would also mean BrightMarket rotating its own key silently kills this screen, so
 * it is written down as the next step rather than done blind.
 */
private const val BROKER_PKG = "com.gios.brightmarket"

const val ACTION_RUN_GRANTS = "com.gios.lightcontrol.action.RUN_GRANTS"
const val EXTRA_PACKAGE = "com.gios.lightcontrol.extra.PACKAGE"
const val EXTRA_LABEL = "com.gios.lightcontrol.extra.LABEL"
const val EXTRA_COMMANDS = "com.gios.lightcontrol.extra.COMMANDS"

/** Wheel notches, for whichever list is on screen. */
val LocalNotches = staticCompositionLocalOf<SharedFlow<Int>?> { null }
