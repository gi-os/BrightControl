package com.gios.lightcontrol.hotspot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/** A paired device, reduced to what the UI and the trigger logic need. */
data class Bonded(val name: String, val address: String)

/**
 * The bond list. These are the phone's paired devices -- the iPad and the MacBook among
 * them -- and their addresses are the identity addresses the scanner has to match a
 * rotating advertising address back to. If Bluetooth is off or the permission is missing,
 * an empty list, never a throw: every caller here has a sensible empty path.
 */
object Bt {

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun bonded(context: Context): List<Bonded> = runCatching {
        adapter(context)?.bondedDevices.orEmpty().map {
            Bonded(name = it.name ?: it.address, address = it.address)
        }.sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    fun enabled(context: Context): Boolean = adapter(context)?.isEnabled == true
}
