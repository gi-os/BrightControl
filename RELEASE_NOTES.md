## BrightControl v3.14 — two features that were pretending to be finished

**Wi-Fi login and Hotspot now say they are unfinished, on the screen itself and in the README.**
Both of them sat in the SYSTEM section looking exactly like Colour and Lock screen look — a row,
a subtitle, a guide paragraph written in the same confident voice as everything else. Neither is
in that state, and the app was the last place you would have found that out.

The honest description of each is short.

**Wi-Fi login needs a system WebView, and whether this phone has one is unconfirmed.** The whole
feature is a WebView pinned to the captive network; on a build without one there is no page, no
error worth reading, and nothing the screen can do from its side. It was written against the
assumption that LightOS carries the standard component, which has never been checked on a device.

**The hotspot has three separate ways to quietly do nothing.** The Bluetooth pairing has to have
exchanged an identity key, or the iPad's rotating address never resolves and presence triggering
cannot work at all. The adb shell has to have survived the reboot, or `SoftAp` has nothing
privileged to call. And the iPad has to choose to join, which it only does for a network it
already knows — so the documented step 2, joining by hand once, is load-bearing and is the step
everybody skips. Any one of those leaves a feature that looks like it is running and is not.

Neither is being removed. They work when their preconditions hold, and the preconditions are
listed now rather than discovered. The rows read `IN DEVELOPMENT ·` before their subtitles, and
each screen opens with a paragraph naming what specifically can leave it dead.

**And the README was rewritten around what this app has become.** It still opened with "the
brightness wheel, camera button, and home button" and a Quick start whose first instruction was to
find a computer and whose sample command named `LightControl-v1.0.11.apk`. Since then the app has
grown per-app colour, a lock face, a volume HUD, a captive-portal browser, a hotspot, and the ADB
self-grant that means no computer is needed for any of it — and the `Layout` block still listed
`ui/SettingsScreen.kt`, which was split into a hub and eight section screens two minor versions
ago, and named none of `lock/`, `hotspot/`, `portal/`, `adb/` or `report/`.

It is now organised the way the app is: Controls, The screen, System, then the safety rules. Quick
start leads with the phone granting itself everything, because that is what actually happens, and
keeps the adb commands for the one grant that cannot be self-applied and for people who prefer a
cable.

No behaviour changed in this release outside those two guide strings and two subtitles.
