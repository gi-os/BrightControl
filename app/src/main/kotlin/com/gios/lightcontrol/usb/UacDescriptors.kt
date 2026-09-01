package com.gios.lightcontrol.usb

/**
 * Which interface on a USB audio device owns the volume, and which unit id to name.
 *
 * ### Why the descriptors have to be walked by hand
 *
 * Android's [android.hardware.usb.UsbInterface] answers class, subclass and protocol, which is
 * enough to find the audio control interface. It does not expose the *class-specific* descriptors
 * that sit inside that interface, and the Feature Unit id lives in one of those. The only route to
 * them is `UsbDeviceConnection.getRawDescriptors()` — the configuration blob as the device reported
 * it — and a TLV walk over it.
 *
 * ### And why the walk is not the whole answer
 *
 * The Apple adapter enumerates as **UAC3 BADD**. BADD is the Basic Audio Device Definition: a set
 * of fixed profiles whose topology is defined by the *spec* rather than described by descriptors,
 * so a BADD configuration can legally ship without the class-specific unit descriptors this walk
 * looks for. There is nothing to parse and the device is not at fault.
 *
 * So this returns candidates, not conclusions. Parsed unit ids come first because a device that
 * described itself should be believed; after them come the ids BADD profiles use, and then the
 * rest of the low numbers. [DacUnlock] settles it by asking the device — a read that returns
 * something is a unit that exists, and a read that fails costs one control transfer.
 *
 * Guessing is the honest shape here. The alternative is a table of adapters by vendor id, which
 * would work for the Apple adapter and for nothing else anybody plugs in, and would go stale the
 * first time Apple revised the part.
 */
object UacDescriptors {

    private const val DESC_INTERFACE = 0x04
    private const val DESC_CS_INTERFACE = 0x24

    /**
     * `bDescriptorSubtype` for a Feature Unit. 0x06 in UAC1 and UAC2; UAC3 renumbered the
     * subtypes and puts it at 0x07. Both are collected — a wrong id fails one read and costs
     * nothing, and the cost of missing the right one is the whole feature.
     */
    private val FEATURE_UNIT_SUBTYPES = intArrayOf(0x06, 0x07)

    /**
     * Unit ids to try when the descriptors named none.
     *
     * The BADD profiles put the output Feature Unit at a fixed id, and 2 is the one the Apple
     * adapter reports when asked over adb. The rest are here because "low, and a handful of them"
     * costs a few milliseconds of control transfers and covers the adapters nobody has tested yet.
     */
    private val FALLBACK_UNIT_IDS = intArrayOf(2, 5, 9, 1, 3, 4, 6, 7)

    /**
     * One audio control interface, and the units it might have.
     *
     * [protocol] is `bInterfaceProtocol`, which decides the request shape: UAC1 has one bRequest
     * per attribute, UAC2 and UAC3 share a different pair. See [UacVolume].
     */
    data class AudioControl(
        val interfaceNumber: Int,
        val protocol: Int,
        /** Unit ids the descriptors actually named, in the order they appeared. */
        val declaredUnits: List<Int>,
    ) {
        /** Declared ids first, then the fallbacks, each appearing once. */
        fun candidateUnits(): List<Int> {
            val out = ArrayList<Int>(declaredUnits.size + FALLBACK_UNIT_IDS.size)
            for (id in declaredUnits) if (id !in out) out.add(id)
            for (id in FALLBACK_UNIT_IDS) if (id !in out) out.add(id)
            return out
        }

        /** True when the device described its own topology, so the first candidate is a fact. */
        val declared: Boolean get() = declaredUnits.isNotEmpty()
    }

    /**
     * Walk a raw configuration descriptor and return every audio control interface in it.
     *
     * The walk is deliberately forgiving. Descriptors come off a device, some devices lie about
     * lengths, and a blob that stops making sense halfway through still described a usable
     * interface in its first half. A zero or absurd `bLength` ends the walk rather than looping
     * forever on it; an unrecognised descriptor type is skipped by its own length, which is the
     * whole point of the format.
     *
     * Alternate settings are not distinguished: a control interface has one, and collecting the
     * same interface number twice would only add a duplicate candidate.
     */
    fun parse(raw: ByteArray?): List<AudioControl> {
        if (raw == null || raw.size < 2) return emptyList()
        val found = LinkedHashMap<Int, MutableList<Int>>()
        val protocols = HashMap<Int, Int>()

        // Which interface the descriptors being walked currently belong to. Class-specific
        // descriptors follow their interface descriptor and carry no back-reference, so position
        // is the only thing that says whose they are.
        var currentAudioControl: Int? = null

        var offset = 0
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xFF
            // A length of 0 or 1 cannot be a descriptor and would not advance the walk.
            if (length < 2 || offset + length > raw.size) break
            when (raw[offset + 1].toInt() and 0xFF) {
                DESC_INTERFACE -> {
                    currentAudioControl = null
                    if (length >= 9) {
                        val number = raw[offset + 2].toInt() and 0xFF
                        val cls = raw[offset + 5].toInt() and 0xFF
                        val subclass = raw[offset + 6].toInt() and 0xFF
                        val protocol = raw[offset + 7].toInt() and 0xFF
                        if (cls == UacVolume.CLASS_AUDIO &&
                            subclass == UacVolume.SUBCLASS_AUDIOCONTROL
                        ) {
                            currentAudioControl = number
                            found.getOrPut(number) { ArrayList() }
                            protocols[number] = protocol
                        }
                    }
                }

                DESC_CS_INTERFACE -> {
                    val iface = currentAudioControl
                    if (iface != null && length >= 4) {
                        val subtype = raw[offset + 2].toInt() and 0xFF
                        if (subtype in FEATURE_UNIT_SUBTYPES) {
                            val unitId = raw[offset + 3].toInt() and 0xFF
                            // Unit id 0 is not a unit — it is what a terminal source is written
                            // as, and a Feature Unit reporting it is a descriptor to ignore.
                            if (unitId != 0) found.getValue(iface).add(unitId)
                        }
                    }
                }
            }
            offset += length
        }

        return found.map { (number, units) ->
            AudioControl(
                interfaceNumber = number,
                protocol = protocols[number] ?: UacVolume.PROTOCOL_UAC1,
                declaredUnits = units.toList(),
            )
        }
    }
}
