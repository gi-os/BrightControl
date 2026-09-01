package com.gios.lightcontrol.usb

/**
 * The USB Audio Class volume control, as the numbers that go on the wire.
 *
 * ### Why this exists at all
 *
 * A USB-C to 3.5 mm adapter is a DAC with its own volume, and that volume is a standard USB
 * control — a Feature Unit on the adapter's audio control interface. Hosts are expected to set
 * it. macOS, Windows and iOS do: they drive the adapter to its top and then do their own mixing
 * in software, which is why the same adapter on an iPhone is loud. Android never touches it, so
 * the adapter sits at whatever its firmware powers up with, and on the Apple adapter that is
 * about 23.5 dB below where it can go. Measured, not guessed: lightphone/light-sdk#186 has 1 kHz
 * tones at three levels against an iPhone 16 Pro Max, and the gap is the same 23.5 dB at every
 * one of them — the signature of a fixed attenuation rather than of a limiter.
 *
 * Nothing on the Android side of that has anything left to give. Media volume is already at its
 * top index, the mixer is at unity, and the HAL's own signal-power history matches the source
 * sample for sample. A digital pre-gain in an app just clips. The missing decibels are past the
 * end of everything an app can normally reach — except this, which is not audio at all. It is a
 * two-byte control transfer to a USB device.
 *
 * ### The encoding, which is the part worth getting right
 *
 * Volume is a **signed** 16-bit value in units of 1/256 dB, little-endian on the wire. So
 * `0x0000` is 0 dB and `0xFF00` is −1 dB. Two consequences:
 *
 * - Reading a maximum into an unsigned Int and writing it back is how you write `+128 dB` to a
 *   device whose maximum is 0. [decode] returns a signed value on purpose.
 * - `0x8000` is not a very quiet volume. The spec reserves it for **silence**, and a device that
 *   reports it inside a range is not offering it as a level. [SILENCE] is rejected rather than
 *   written, because it is the one value here whose effect would be worse than doing nothing.
 *
 * ### Three generations, two request shapes
 *
 * UAC1 asks for each attribute with its own `bRequest` — `GET_MAX` is 0x83. UAC2 and UAC3
 * replaced that with one `CUR` request plus a `RANGE` request that returns every supported
 * subrange in a single reply. The Apple adapter enumerates as UAC3 BADD, so the second shape is
 * the one that matters here; the first costs four constants and some adapters still use it.
 *
 * Everything in this file is arithmetic, and it is here rather than inline in [DacUnlock] so it
 * can be tested without a phone, a dongle or a USB stack. The signed encoding and the range walk
 * are where a bug would be *silent*, and silent is exactly what "nothing got louder" looks like.
 */
object UacVolume {

    /** `bInterfaceClass` for audio. */
    const val CLASS_AUDIO = 0x01

    /** `bInterfaceSubClass` for the audio *control* interface — the one that owns the units. */
    const val SUBCLASS_AUDIOCONTROL = 0x01

    /** `bInterfaceProtocol`. UAC1 predates the field and leaves it zero. */
    const val PROTOCOL_UAC1 = 0x00
    const val PROTOCOL_UAC2 = 0x20
    const val PROTOCOL_UAC3 = 0x30

    /** IN | CLASS | INTERFACE. */
    const val TYPE_GET = 0xA1

    /** OUT | CLASS | INTERFACE. */
    const val TYPE_SET = 0x21

    /** Feature Unit control selectors. */
    const val CS_MUTE = 0x01
    const val CS_VOLUME = 0x02

    /** Channel 0 is the master. Individual channels start at 1. */
    const val CH_MASTER = 0x00

    // UAC1: one bRequest per attribute.
    const val UAC1_SET_CUR = 0x01
    const val UAC1_GET_CUR = 0x81
    const val UAC1_GET_MIN = 0x82
    const val UAC1_GET_MAX = 0x83

    // UAC2 / UAC3: one request per attribute kind, direction in bmRequestType.
    const val UAC2_CUR = 0x01
    const val UAC2_RANGE = 0x02

    /** Reserved for silence, and never a level to write. */
    const val SILENCE = -0x8000

    /** 0 dB — the top of every adapter seen so far, and the fallback when no range can be read. */
    const val ZERO_DB = 0

    /**
     * The quietest thing this will accept as a device's maximum.
     *
     * A device answering −60 dB to "how loud can you go" answered a different question than the
     * one asked, and writing that would make the phone quieter than it was found. Bounded rather
     * than trusted.
     */
    const val FLOOR_UNITS = -60 * 256

    /** `wValue`: control selector in the high byte, channel number in the low byte. */
    fun wValue(control: Int, channel: Int): Int = ((control and 0xFF) shl 8) or (channel and 0xFF)

    /** `wIndex`: unit id in the high byte, interface number in the low byte. */
    fun wIndex(unitId: Int, iface: Int): Int = ((unitId and 0xFF) shl 8) or (iface and 0xFF)

    /** A volume as the two little-endian bytes that go on the wire. */
    fun encode(dbUnits: Int): ByteArray =
        byteArrayOf((dbUnits and 0xFF).toByte(), ((dbUnits shr 8) and 0xFF).toByte())

    /**
     * Two little-endian bytes as the **signed** value they are.
     *
     * Null rather than a wrong number when there are not two bytes to read. A short reply is a
     * control transfer that half happened, and treating its missing tail as zero would read as
     * 0 dB — the one answer indistinguishable from success.
     */
    fun decode(bytes: ByteArray, offset: Int = 0): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        return raw.toShort().toInt()
    }

    /**
     * The loudest level a UAC2/UAC3 `RANGE` reply offers.
     *
     * Layout: `wNumSubRanges`, then that many `MIN, MAX, RES` triples of two bytes each. Most
     * devices report one subrange; the spec allows several with different step sizes, so the
     * answer is the highest MAX across all of them rather than the first one found.
     *
     * [length] is the count the transfer actually returned, which is not the buffer's size — the
     * buffer is oversized on purpose, because a reply's length is not known until it arrives.
     */
    fun maxFromRange(bytes: ByteArray, length: Int): Int? {
        if (length < 2 || bytes.size < 2) return null
        val count = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        if (count <= 0) return null
        var best: Int? = null
        var offset = 2
        repeat(count) {
            // Stop at the end of what arrived rather than reading past it: a truncated reply
            // still has usable subranges in front of the truncation.
            if (offset + 6 > length) return best
            val max = decode(bytes, offset + 2)
            val current = best
            if (max != null && max != SILENCE && (current == null || max > current)) best = max
            offset += 6
        }
        return best
    }

    /** Whether a value read back from a device is a level worth writing. */
    fun usableMax(value: Int?): Boolean =
        value != null && value != SILENCE && value > FLOOR_UNITS

    /** 1/256 dB units as dB, for a readout. */
    fun toDb(units: Int): Float = units / 256f

    /** "0.0 dB" / "−12.5 dB", for the settings screen. */
    fun formatDb(units: Int): String {
        val db = toDb(units)
        val text = String.format("%.1f", kotlin.math.abs(db))
        return (if (db < 0f) "−" else "") + text + " dB"
    }
}
