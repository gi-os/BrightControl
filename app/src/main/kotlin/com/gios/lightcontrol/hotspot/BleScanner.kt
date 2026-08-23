package com.gios.lightcontrol.hotspot

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.gios.lightcontrol.hotspot.Bt.adapter
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches the air for bonded devices and remembers when each was last heard.
 *
 * It does not decide anything -- it just keeps a fresh map of address to "seconds since I
 * last saw it", which the watcher polls each tick. Kept separate from the decision so the
 * decision stays pure and testable and this stays a thin wrapper over the platform scanner.
 *
 * The subtlety is address resolution. An iPad advertises under a rotating random address
 * that changes every fifteen minutes precisely so it cannot be tracked. Android's scanner
 * resolves that back to the device's identity address *only for bonded devices whose IRK it
 * holds* -- which is why the app pairs the iPad through system settings first, and why the
 * diagnostic screen exists to prove the resolution actually happens on this phone.
 */
class BleScanner(context: Context) {

    private val app = context.applicationContext
    private val seen = ConcurrentHashMap<String, Long>()
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    /** Optional firehose for the diagnostic screen: every result, resolved address and name. */
    @Volatile var onResult: ((address: String, name: String?, rssi: Int) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val s = adapter(app)?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            // Low power is the right default for a background presence check; the watcher
            // is not in a hurry and a constant high-duty scan would show up in the battery
            // graph. The trade is a few seconds of latency, which the timings absorb.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                seen[addr] = SystemClock.elapsedRealtime()
                onResult?.invoke(addr, runCatching { result.device.name }.getOrNull(), result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w("BrightHotspotScan", "scan failed: $errorCode")
            }
        }
        // No filters: the iPad's service data is not stable, and the address is what we
        // match on anyway. The result set on a phone is small.
        return runCatching {
            s.startScan(null, settings, cb)
            scanner = s
            callback = cb
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { callback?.let { scanner?.stopScan(it) } }
        callback = null
        scanner = null
    }

    /** Addresses heard within the last [windowMs], by the monotonic clock. */
    fun recentlySeen(windowMs: Long): Set<String> {
        val now = SystemClock.elapsedRealtime()
        return seen.filterValues { now - it <= windowMs }.keys.toSet()
    }

    fun clear() = seen.clear()
}
