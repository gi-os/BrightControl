package com.gios.lightcontrol.lock

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the top bar says. Everything here is readable without a runtime permission. */
data class LockStatus(
    val network: String,
    val battery: Int,
    val charging: Boolean,
    val alarm: String?,
)

/**
 * The top bar, assembled from the three things the phone will tell a sideloaded app for free.
 *
 * Deliberately not signal *bars*. `TelephonyManager.getSignalStrength` needs `READ_PHONE_STATE`,
 * which is a runtime permission LightOS has no Settings screen to grant — so bars would mean
 * either an adb line for cosmetics or, worse, a bar count that silently reads empty on a phone
 * with full signal. The connectivity manager answers the question that actually matters, which is
 * whether anything is going to arrive, and it answers it without asking for anything.
 */
@Composable
fun rememberLockStatus(): State<LockStatus> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf(readStatus(context)) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                state.value = readStatus(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_TIME_TICK)
        }
        // ACTION_BATTERY_CHANGED is sticky, so registering is also how the first reading is taken
        // — there is no "ask once" API for it that does not go through a broadcast anyway.
        runCatching { context.registerReceiver(receiver, filter) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

private fun readStatus(context: Context): LockStatus {
    val battery = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    val charging = runCatching {
        context.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    val network = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "NO SERVICE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val carrier = context.getSystemService(TelephonyManager::class.java)
                    ?.networkOperatorName.orEmpty().trim()
                carrier.ifEmpty { "CELLULAR" }.uppercase()
            }
            else -> "NO SERVICE"
        }
    }.getOrDefault("NO SERVICE")

    val alarm = runCatching {
        val next = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        next?.let { SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(it.triggerTime)) }
    }.getOrNull()

    return LockStatus(network = network, battery = battery, charging = charging, alarm = alarm)
}
