## BrightControl v4.4 — wired headphones, 23.5 dB louder (experimental)

**Volume → Wired headphones. Off by default, and read the caveats before switching it on.**

Someone on the Light forum measured what everyone using a USB-C to 3.5 mm adapter already suspected.
1 kHz sine tones at three levels, an LPIII and an iPhone 16 Pro Max, the same Apple adapter and the
same cable into the same interface, both phones at maximum: the LPIII came out **23.5 dB quieter, at
every level**, reproducing the steps between the test files to a tenth of a decibel. Not a limiter,
not compression — a fixed attenuation of about fourteen fifteenths of the voltage.
See lightphone/light-sdk#186 for the raw numbers.

The Android side of that has nothing left to give, which the same investigation established: media
volume already at its top index, master and track gains at unity, and the HAL's signal-power history
matching the source sample for sample. Full-level audio is reaching the USB port. A digital pre-gain
in an app just clips.

**The missing decibels are in the adapter.** A USB-C headphone adapter is a DAC with a volume control
of its own — a standard USB Audio Class Feature Unit — and hosts are expected to set it. macOS,
Windows and iOS all drive it to the top and mix in software underneath, which is why the same adapter
on an iPhone is loud. Android never touches it, so it sits at whatever its firmware powers up with.

So this release sets it. Plug an adapter in and BrightControl asks the device where its volume control
is, reads the maximum the device itself reports, writes that, and reads it back to confirm the write
landed. The level lives in the adapter's RAM and is forgotten the moment you unplug it, so this
happens on every single connect — which is the argument for it living here, in something already
running, rather than in a utility you have to remember to open.

### What it costs, stated plainly

- **It needs the microphone permission.** Android hides any USB device with an audio interface from
  apps that lack `RECORD_AUDIO`, and refuses to open it — a USB audio device is a microphone as often
  as it is a speaker, and the platform is right to gate it. This app records nothing, has no
  `AudioRecord` or `MediaRecorder` anywhere in it, and asks for the permission only from the DAC
  screen after you have switched the feature on. It is deliberately **not** in the ADB screen's
  run-every-grant list: a button that grants everything at once must not be how this app gets a
  microphone.
- **The first connect asks which app should handle the adapter.** Answer with this app and tick
  "use by default". That dialog *is* the permission — answering "just once" means the next connect
  asks again.
- **Audio stops for a moment while it runs.** The kernel driver has the adapter's interface, and
  taking it back for the length of a few control transfers is the only way to reach the control. On
  a fresh connect nothing is playing yet, so nobody hears the seam. The "raise it now" button on the
  DAC screen is the one place it can happen mid-song, and it says so.
- **It can turn your phone down, once, on purpose.** About 23 dB arriving in headphones already in
  your ears at full volume is the one way this could hurt somebody, and plugging an adapter in while
  a podcast plays is the normal way to use one. So when the unlock lands while audio is playing and
  media volume is high, media volume drops to roughly two thirds — once, on that connect, never
  re-asserted. Turn it back up and it stays up. Nothing playing means nothing is moved. Toggleable.

### Why experimental

The mechanism is proven: the same trick works from a standalone app
([polhdez/usbDacVolumeAndroid](https://github.com/polhdez/usbDacVolumeAndroid)), and a second person
in the thread has since reproduced the underlying fault across three different adapters. What is not
proven is this implementation against adapters other than the Apple one.

The Apple adapter enumerates as UAC3 BADD, a profile whose topology is defined by the spec rather
than described in the device's own descriptors — so there is frequently no Feature Unit descriptor to
parse, and the unit has to be found by asking the device and seeing what answers. That works, and it
is a guess with a verification step rather than a certainty. Adapters that leave the control out
entirely, or that already power up at full, both read as "no volume control found" on the DAC screen,
which is honest but not informative.

The screen therefore reports what actually happened on the last connect, in words: which unit
answered, what it was set to, and whether the device read the value back. A verified write is proof
the control took the bytes. It is not proof of how much louder anything got — only a measurement is
that, and if you have an interface and a test tone, that measurement would be worth having.

**Calls are not affected and cannot be.** A call's audio comes off the modem and its gain lives in
the phone's own audio HAL, nowhere near the adapter's volume. Music, podcasts and video go through
this; a phone call does not. "Loud speakerphone", one screen up, is still the only lever there.

Not a fix for the platform bug — LightOS should be setting this control itself, on every route, for
every adapter, and this app should not need to exist for it. Filed upstream; this is what can be done
from a sideloaded APK in the meantime.
