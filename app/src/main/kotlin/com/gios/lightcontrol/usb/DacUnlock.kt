package com.gios.lightcontrol.usb

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.SystemClock
import com.gios.lightcontrol.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raise a USB headphone adapter to its own maximum, once, each time one is plugged in.
 *
 * **Experimental.** See [UacVolume] for the measurement this exists because of, and the rest of
 * this comment for the three things about it that are not obvious.
 *
 * ### The kernel owns the interface, and that is the whole difficulty
 *
 * Reading the issue thread, this looked impossible from app space: the audio driver has the
 * adapter's interfaces claimed, so it is not ours to talk to. That is true of the *streaming*
 * interfaces and true of class requests aimed at an interface — usbfs refuses a control transfer
 * whose recipient is an interface claimed by a kernel driver. It is not the end of it. Claiming
 * an interface with `force = true` detaches the kernel driver from it for as long as we hold it,
 * which is what this does: claim, ask, write, release. The driver comes straight back.
 *
 * The cost is real and it is the reason this fires on **attach** rather than on demand: audio
 * playing through the adapter stops for the length of the claim. At the moment a dongle is
 * plugged in nothing is playing yet, so nobody hears the seam. Called mid-song, the song stops
 * and has to be restarted, which is why nothing calls it mid-song except the button on the
 * settings screen, where the user asked for it.
 *
 * ### Android hides audio devices from apps without a microphone permission
 *
 * `UsbManager.getDeviceList()` omits any device with an audio-class interface unless the caller
 * holds `RECORD_AUDIO`, and `openDevice` refuses it. This is a deliberate platform restriction —
 * a USB audio device is a microphone as often as it is a speaker, and enumerating one is being
 * near a microphone. So this feature needs a permission it does not use for its stated purpose,
 * which is a thing to say plainly on the settings screen rather than to obtain quietly. Nothing
 * here opens an audio input, and the permission is only ever requested when the feature is
 * switched on.
 *
 * ### It has to happen every single time
 *
 * The volume lives in the adapter's own RAM. Unplugging it forgets it. So there is no "set this
 * once and it stays" — there is a receiver, and a level re-asserted on every connect, forever.
 * That is the argument for this living in an app that is already always running rather than in a
 * utility you have to remember to open.
 */
object DacUnlock {

    /** Long enough for a device that is going to answer, short enough that a probe of a dozen
     * candidates is still faster than a plug-in takes. */
    private const val PROBE_MS = 250

    /** The write and its read-back get longer: this is the one transfer that matters. */
    private const val WRITE_MS = 1000

    /** Beyond this, stop probing and say so. A dongle that has answered nothing in three
     * seconds is a dongle whose volume is not where this is looking. */
    private const val BUDGET_MS = 3000L

    /** RANGE replies are a couple of dozen bytes at most; asking for more costs nothing. */
    private const val RANGE_BUF = 64

    /**
     * How far the media index is pulled down when the unlock lands on a phone that is already
     * playing. Not a general volume policy — see [trimIfPlaying].
     */
    private const val TRIM_FRACTION = 0.65f

    /** One at a time, off the caller's thread, and never two claims of the same interface. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dac-unlock").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)

    /** Whether this build can even ask. Android 12 and up is where the restriction bites. */
    fun hasRecordAudio(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Switch the attach hook on or off.
     *
     * The activity that carries the USB permission is declared disabled in the manifest and
     * enabled here, rather than being always-present and checking a preference. A manifest
     * `USB_DEVICE_ATTACHED` filter is how an app is *offered* a device — with the component
     * live, LightOS asks the user which app should handle every adapter they plug in, forever,
     * whether or not this feature is on. Off has to mean off, including in a dialog the user
     * never asked to see.
     */
    fun setEnabled(context: Context, on: Boolean) {
        runCatching {
            val component = ComponentName(context, DacAttachActivity::class.java)
            context.packageManager.setComponentEnabledSetting(
                component,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    /**
     * Try to raise [device], or every audio device attached if it is null.
     *
     * Returns immediately; the work is on [worker]. [why] appears in the log line and in the
     * settings screen's readout, because "it did nothing" has several causes and they are not
     * distinguishable from the phone.
     */
    fun apply(context: Context, device: UsbDevice?, why: String, log: (String) -> Unit = {}) {
        val app = context.applicationContext
        worker.execute {
            if (!running.compareAndSet(false, true)) {
                DacSignals.note("another attempt was already in flight")
                return@execute
            }
            try {
                val line = runCatching { attempt(app, device, why) }
                    .getOrElse { e -> "usb dac · failed · ${e.javaClass.simpleName}" }
                log(line)
            } finally {
                running.set(false)
            }
        }
    }

    /** A device went away. The next one starts from nothing. */
    fun onDetached() {
        DacSignals.onDetached()
    }

    private fun attempt(context: Context, only: UsbDevice?, why: String): String {
        val prefs = Prefs(context)
        if (!prefs.dacUnlock) {
            DacSignals.note("switched off")
            return "usb dac · off"
        }
        if (!hasRecordAudio(context)) {
            // Stated rather than retried. Without it the device list is empty and every other
            // diagnostic below would report "no adapter found" on a phone with one plugged in.
            DacSignals.note("needs the microphone permission — see the DAC screen")
            return "usb dac · no RECORD_AUDIO"
        }
        val usb = context.getSystemService(UsbManager::class.java)
            ?: return "usb dac · no USB service".also { DacSignals.note("no USB service") }

        val devices = if (only != null) listOf(only) else runCatching {
            usb.deviceList.values.toList()
        }.getOrDefault(emptyList())

        val audio = devices.filter { hasAudioControl(it) }
        if (audio.isEmpty()) {
            DacSignals.note("no USB audio device attached")
            return "usb dac · nothing to raise ($why)"
        }

        val lines = ArrayList<String>()
        for (device in audio) {
            if (!usb.hasPermission(device)) {
                // The manifest filter is what grants this, at attach, and only for a device the
                // filter matched. Nothing here prompts: a dialog raised from a broadcast lands on
                // whatever app is in front, which is not where a user is expecting one.
                DacSignals.note("no USB permission for ${name(device)} — replug it and allow")
                lines.add("usb dac · ${name(device)} · not permitted")
                continue
            }
            lines.add(raise(context, usb, device, prefs, why))
        }
        return lines.joinToString(" · ")
    }

    private fun raise(
        context: Context,
        usb: UsbManager,
        device: UsbDevice,
        prefs: Prefs,
        why: String,
    ): String {
        val connection: UsbDeviceConnection = runCatching { usb.openDevice(device) }.getOrNull()
            ?: return "usb dac · ${name(device)} · would not open"
                .also { DacSignals.note("the device would not open") }

        try {
            val controls = UacDescriptors.parse(runCatching { connection.rawDescriptors }.getOrNull())
            if (controls.isEmpty()) {
                DacSignals.note("no audio control interface in the descriptors")
                return "usb dac · ${name(device)} · no control interface"
            }
            val started = SystemClock.elapsedRealtime()
            for (control in controls) {
                val iface = interfaceNumbered(device, control.interfaceNumber) ?: continue
                // force = true: the audio driver has this interface, and it does not share.
                val claimed = runCatching { connection.claimInterface(iface, true) }
                    .getOrDefault(false)
                if (!claimed) {
                    DacSignals.note("interface ${control.interfaceNumber} could not be claimed")
                    continue
                }
                try {
                    val hit = probe(connection, control, started)
                    if (hit != null) {
                        // Only now, and only if something is actually playing. See below.
                        val trimmed = trimIfPlaying(context, prefs)
                        val ok = write(connection, control, hit)
                        DacSignals.onUnlocked(
                            device = name(device),
                            unit = hit.unitId,
                            channel = hit.channel,
                            target = hit.max,
                            verified = ok,
                            trimmed = trimmed,
                        )
                        val level = UacVolume.formatDb(hit.max)
                        return "usb dac · ${name(device)} · unit ${hit.unitId} → $level" +
                            (if (ok) "" else " · UNVERIFIED") +
                            (if (trimmed) " · media trimmed" else "") +
                            " ($why)"
                    }
                } finally {
                    // Always. Holding this is holding the audio driver off the adapter, and a
                    // leaked claim is an adapter that stays silent until it is unplugged.
                    runCatching { connection.releaseInterface(iface) }
                }
            }
            DacSignals.note("no volume control answered on ${name(device)}")
            return "usb dac · ${name(device)} · no volume control found"
        } finally {
            runCatching { connection.close() }
        }
    }

    private class Hit(val unitId: Int, val channel: Int, val max: Int)

    /**
     * Ask the device which unit and channel actually has a volume, and how loud it goes.
     *
     * A read that returns bytes is a control that exists. This is the part that makes the feature
     * work on adapters nobody has tested: BADD devices describe no units at all, so the id has to
     * come from asking rather than from parsing. See [UacDescriptors].
     *
     * Master channel first, for every candidate, before any per-channel attempt — a device with a
     * master control is the common case and finding it costs one transfer per id, where walking
     * every channel of every id first would spend the whole budget in the uncommon one.
     */
    private fun probe(
        connection: UsbDeviceConnection,
        control: UacDescriptors.AudioControl,
        started: Long,
    ): Hit? {
        val units = control.candidateUnits()
        for (channel in intArrayOf(UacVolume.CH_MASTER, 1, 2)) {
            for (unit in units) {
                if (SystemClock.elapsedRealtime() - started > BUDGET_MS) {
                    DacSignals.note("gave up probing after ${BUDGET_MS / 1000}s")
                    return null
                }
                val max = readMax(connection, control, unit, channel)
                if (UacVolume.usableMax(max)) return Hit(unit, channel, max!!)
            }
        }
        return null
    }

    /**
     * The device's own maximum, in 1/256 dB.
     *
     * Both request shapes are tried whatever the protocol byte says. `bInterfaceProtocol` is
     * meant to settle this, and mostly does, but it is one byte of a descriptor written by the
     * cheapest part in the signal chain — a device that reports UAC1 and answers UAC2 requests is
     * not a device worth failing on. The declared shape goes first.
     */
    private fun readMax(
        connection: UsbDeviceConnection,
        control: UacDescriptors.AudioControl,
        unit: Int,
        channel: Int,
    ): Int? {
        val iface = control.interfaceNumber
        return if (control.protocol == UacVolume.PROTOCOL_UAC1) {
            readMaxUac1(connection, iface, unit, channel)
                ?: readMaxUac2(connection, iface, unit, channel)
        } else {
            readMaxUac2(connection, iface, unit, channel)
                ?: readMaxUac1(connection, iface, unit, channel)
        }
    }

    private fun readMaxUac1(
        connection: UsbDeviceConnection,
        iface: Int,
        unit: Int,
        channel: Int,
    ): Int? {
        val buffer = ByteArray(2)
        val read = transfer(
            connection,
            UacVolume.TYPE_GET,
            UacVolume.UAC1_GET_MAX,
            UacVolume.wValue(UacVolume.CS_VOLUME, channel),
            UacVolume.wIndex(unit, iface),
            buffer,
            buffer.size,
            PROBE_MS,
        )
        return if (read >= 2) UacVolume.decode(buffer) else null
    }

    private fun readMaxUac2(
        connection: UsbDeviceConnection,
        iface: Int,
        unit: Int,
        channel: Int,
    ): Int? {
        val buffer = ByteArray(RANGE_BUF)
        val read = transfer(
            connection,
            UacVolume.TYPE_GET,
            UacVolume.UAC2_RANGE,
            UacVolume.wValue(UacVolume.CS_VOLUME, channel),
            UacVolume.wIndex(unit, iface),
            buffer,
            buffer.size,
            PROBE_MS,
        )
        if (read < 2) return null
        return UacVolume.maxFromRange(buffer, read)
    }

    /**
     * Write the level, then read it back.
     *
     * The read-back is the difference between "we sent two bytes" and "the adapter took them",
     * and those are not the same event — a control transfer can be accepted by the stack and
     * ignored by the firmware. What it cannot do is prove anybody got louder; only a measurement
     * does that, which is why the release notes ask for one.
     */
    private fun write(
        connection: UsbDeviceConnection,
        control: UacDescriptors.AudioControl,
        hit: Hit,
    ): Boolean {
        val request = if (control.protocol == UacVolume.PROTOCOL_UAC1) {
            UacVolume.UAC1_SET_CUR
        } else {
            UacVolume.UAC2_CUR
        }
        val payload = UacVolume.encode(hit.max)
        val sent = transfer(
            connection,
            UacVolume.TYPE_SET,
            request,
            UacVolume.wValue(UacVolume.CS_VOLUME, hit.channel),
            UacVolume.wIndex(hit.unitId, control.interfaceNumber),
            payload,
            payload.size,
            WRITE_MS,
        )
        if (sent < 0) return false

        val readRequest = if (control.protocol == UacVolume.PROTOCOL_UAC1) {
            UacVolume.UAC1_GET_CUR
        } else {
            UacVolume.UAC2_CUR
        }
        val back = ByteArray(2)
        val read = transfer(
            connection,
            UacVolume.TYPE_GET,
            readRequest,
            UacVolume.wValue(UacVolume.CS_VOLUME, hit.channel),
            UacVolume.wIndex(hit.unitId, control.interfaceNumber),
            back,
            back.size,
            WRITE_MS,
        )
        return read >= 2 && UacVolume.decode(back) == hit.max
    }

    private fun transfer(
        connection: UsbDeviceConnection,
        type: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
        timeout: Int,
    ): Int = runCatching {
        connection.controlTransfer(type, request, value, index, buffer, length, timeout)
    }.getOrDefault(-1)

    /**
     * Pull the media index down, but only if something is playing right now.
     *
     * The unlock is worth about 23 dB. Landing that on headphones already in somebody's ears at
     * full volume is the one way this feature could hurt a person, and it is not hypothetical:
     * plugging an adapter in while a podcast plays is the normal way to use one.
     *
     * So the guard is narrow on purpose. Nothing playing means nothing to protect and no reason
     * to move a level the user set — a phone that quietly turned itself down every time a cable
     * went in would be a worse bug than the one being fixed. Something playing, and above the
     * threshold, gets one trim, once, on this connect. Turn it back up and it stays up: this
     * never re-asserts, for the same reason [com.gios.lightcontrol.keys.CallAudio] does not.
     */
    private fun trimIfPlaying(context: Context, prefs: Prefs): Boolean {
        if (!prefs.dacTrim) return false
        val audio = runCatching { context.getSystemService(AudioManager::class.java) }
            .getOrNull() ?: return false
        val playing = runCatching { audio.isMusicActive }.getOrDefault(false)
        if (!playing) return false
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        if (max <= 0) return false
        val now = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        val target = (max * TRIM_FRACTION).toInt().coerceIn(1, max)
        if (now <= target) return false
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            true
        }.getOrDefault(false)
    }

    /** Whether this device has an audio control interface at all — the only ones worth opening. */
    fun hasAudioControl(device: UsbDevice): Boolean = runCatching {
        (0 until device.interfaceCount).any { index ->
            val iface = device.getInterface(index)
            iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == UacVolume.SUBCLASS_AUDIOCONTROL
        }
    }.getOrDefault(false)

    private fun interfaceNumbered(device: UsbDevice, number: Int): UsbInterface? = runCatching {
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == number }
    }.getOrNull()

    /** Something a person would recognise, for a log line. */
    fun name(device: UsbDevice): String {
        val product = runCatching { device.productName }.getOrNull()
        if (!product.isNullOrBlank()) return product
        return String.format("%04x:%04x", device.vendorId, device.productId)
    }
}
