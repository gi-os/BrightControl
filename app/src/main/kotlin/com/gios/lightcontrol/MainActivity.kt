package com.gios.lightcontrol

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
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
import com.gios.lightcontrol.ui.SettingsScreen
import com.gios.lightcontrol.ui.theme.LightControlTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The settings screen, and the one place the wheel is handled in-app rather than in the
 * service.
 *
 * LightControl resolves to `ScrollThrough` for itself — it's a `com.gios.` package — so the
 * service passes turns straight here. Which is the point being demonstrated: an app that
 * handles the wheel gets per-notch scrolling, and one that doesn't gets brightness.
 */
class MainActivity : ComponentActivity() {

    private val notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LightControlTheme {
                CompositionLocalProvider(LocalNotches provides notches.asSharedFlow()) {
                    var showApps by remember { mutableStateOf(false) }
                    if (showApps) {
                        AppListScreen(onBack = { showApps = false })
                    } else {
                        SettingsScreen(onPerApp = { showApps = true })
                    }
                }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (LightKeys.of(event)) {
                LightKey.WheelUp -> {
                    notches.tryEmit(1)
                    return true
                }
                LightKey.WheelDown -> {
                    notches.tryEmit(-1)
                    return true
                }
                else -> Unit
            }
        }
        // Swallow the matching UP so a turn can't also register as a keypress somewhere.
        if (event.action == KeyEvent.ACTION_UP) {
            when (LightKeys.of(event)) {
                LightKey.WheelUp, LightKey.WheelDown -> return true
                else -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Wheel notches, for whichever list is on screen. */
val LocalNotches = staticCompositionLocalOf<SharedFlow<Int>?> { null }
