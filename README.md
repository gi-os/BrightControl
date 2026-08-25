# BrightControl

The Light Phone III's hardware working everywhere, and the settings LightOS never shipped
a screen for.

It started as the wheel and the camera button in sideloaded apps. It is now the layer the
phone is missing: per-app colour, a lock face, a volume readout, a captive-portal browser,
a hotspot that raises itself — and, underneath all of it, a phone that grants itself the
permissions those need without a computer in the room.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightControl.png" alt="Scan to open BrightControl in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightControl there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

**Current version: v3.14.** See [Version history](#version-history).

## What it does

| | |
|---|---|
| **Controls** | The wheel, the camera button, the home button and the volume keys, each with a tap and a hold, bindable to any installed app |
| **Colour** | Per-app colour on a phone with one global monochrome switch |
| **Lock screen** | A Light-style lock face with notifications, signal, battery and a photo background |
| **Volume** | The on-screen volume level LightOS ships without |
| **ADB & grants** | The phone granting itself every permission it needs, over its own wireless debugging |
| **Wi-Fi login** | A captive-portal sign-in page — **in development, may not work** |
| **Hotspot** | Raises the hotspot when a paired iPad is near — **in development, may not work** |
| **Diagnostics** | Shake to file a report, the last crash, and a log of what the key filter did |

Out of the box:

| Gesture | Does |
|---|---|
| Turn the wheel | Brightness — or a per-notch scroll, or passed through, per app. On LightOS's own screens it stays LightOS's, unless you switch that off |
| Double tap the wheel | Switches turning between brightness and scrolling, and says which |
| Tap the wheel | Flashlight |
| Hold the wheel | nothing — bind it to any app |
| Tap the camera button | The Light camera |
| Hold the camera button | nothing — bind it to any app |
| Tap the home button | Home — whichever launcher is default |
| Hold the home button | LightOS's dashboard, by name — rebind it to anything |
| Wake the phone | The stock lock screen — or a Light face over it, once you turn it on |
| Volume keys, tap or hold | passed through, but bindable. The level appears on screen |

## What this is and why

Every wheel notch, camera-button press and home-button press on the LPIII arrives at
whichever app has focus as an ordinary `KeyEvent` — Light patched
`/system/usr/keylayout/Generic.kl`, the keylayout every input device on the phone loads,
so the brightness ramp and flashlight you get in Light's own tools are just app-layer
code in those tools:

```
key 19    WHEEL_CCW      # wheel up       (Pixart pat9126ja, was R)
key 20    WHEEL_CW       # wheel down     (Pixart pat9126ja, was T)
key 66    WHEEL_CLICK    # wheel press    (gpio-keys, was F8)
key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
```

Nothing intercepts these in `PhoneWindowManager`. In any sideloaded app the keys arrive
and nothing listens. BrightControl is that missing layer for everything else — an
`AccessibilityService` with `flagRequestFilterKeyEvents`, the only way to see a key you
don't have window focus for (`INJECT_EVENTS` is signature-only; this is the same
mechanism [LightVoice](https://github.com/gi-os/LightVoice) uses for its push-to-talk).
It declares exactly one event type (`typeWindowStateChanged`, to track the foreground
package) and `canRetrieveWindowContent="false"` — screen content is unreachable by
declaration, not merely by promise.

The wheel itself is not a rotary encoder. It's an optical sensor (`Pixart pat9126ja`)
firing one discrete DOWN+UP pair per notch, ~35–60 ms apart, so `AXIS_SCROLL` and
`onRotaryScrollEvent` never see a thing — this has to be built on raw key events.
`WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` aren't AOSP keycodes; they're resolved by
label at runtime via `KeyEvent.keyCodeFromString` and fall back to the raw Linux
scancode, gated on the input device name so a paired Bluetooth keyboard's `r` can't dim
the screen.

Every button carries a **tap** and a **hold**, bound separately, and either can open any
installed app. Light's own tools are left strictly alone by default, and any app can be
overridden individually. Tap and hold are told apart by time, not by key repeat — a held
key on this phone never repeats — and **the release is what decides**. Nothing fires
mid-press. That ordering matters more than it sounds: a hold that goes off while the button
is still down brings an app to the front *during* the press, and the rest of the press then
belongs to a foreground that changed underneath it. One press, one decision, dispatched
once, after the key is already accounted for. It costs the feeling of a hold going off in
your hand; it buys a button that does one thing.

## Quick start

Install it, open it, and follow the intro screen. **You do not need a computer.**

That sentence is the whole of v3.x. LightOS grants nothing this app needs through any
on-screen setting — the accessibility service, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`,
`WRITE_SECURE_SETTINGS`, the notification listener — so historically every one of them
meant plugging into a computer, and a reinstall dropped the appops and secure-settings
grants and meant plugging in *again*. That was the single most common way this app
arrived on a phone looking broken.

**ADB & grants** removes the computer. Android's own wireless-debugging daemon listens on
a TCP port on the device; the app connects to it over loopback with a client certificate
and runs `pm grant` and `appops set` against itself. Pairing happens once, from a floating
panel that sits *on top of* the Settings pairing dialog — the dialog only keeps its session
alive while it is on screen, so an overlay is the only way to read the six digits and use
them without killing the thing that issued them. After that it is discovery, not pairing:
the daemon re-advertises `_adb-tls-connect._tcp` on a fresh port after every boot and the
app finds it again by itself.

If you would rather do it from a computer, every grant is listed with its exact command in
**Setup & guide**, and the ADB screen reads each one back off the phone afterwards rather
than trusting what the command printed — the adb `shell:` service merges stdout and stderr
and carries no exit status, so "printed nothing" is not the same as "worked".

```bash
# the one that cannot be self-granted, because it is what everything else runs through
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1
```

That setting is a **list, not a flag** — writing it replaces whatever was there, so if you
also run LightVoice's push-to-talk, colon-join the two components instead.

## Controls

### Defaults, and why

| Apps | Turning the wheel | Everything else |
|---|---|---|
| `com.lightos*`, `com.thelightphone.*`, `com.lightphone.*`, the launcher, SystemUI, Camera2 | untouched | untouched |
| `com.gios.brightrecorder` | untouched | untouched |
| `com.gios.*`, `com.lightfastread`, `com.lightrss.reader` | goes to the app, which scrolls per notch | ours |
| everything else | brightness (or `SWIPE`, per app) | ours |

The wheel's mode is global and outranks that table's scroll-aware row: in brightness mode a turn
changes brightness in *every* app, including the ones carrying `hw/`. That is the point of putting
it here rather than in the apps — they never need to know the mode exists, and switching it doesn't
mean shipping sixteen releases. In scroll mode those apps get their turns back and scroll per
notch, which is still better than any synthetic finger.

Light's own tools are hands-off because the wheel already works there — anything
intercepted would be a feature *removed*. Apps carrying a `hw/` scroll module get their
turns passed through, because per-notch scrolling inside the app beats anything
reachable from outside it.

BrightRecorder gets the stronger treatment because its wheel *press* is a control too — it is a
tape recorder, and pressing the wheel plays and stops it. Turns-through-with-our-buttons is not
enough there: the click's default is the torch, so the press would light the flashlight and be
consumed before the app ever saw it, with nothing the app could do from its side. So it is
hands-off outright, and the trade is that the torch and the camera key do nothing of ours while
it is in front. The list is `ownsWheelPrefixes`, consulted before the scroll-aware one because
`com.gios.brightrecorder` sits inside `com.gios.` and would otherwise be claimed by it. Per-app
overrides cycle on tap through `AUTO → BRIGHT → SWIPE → APP → OFF`, and rows left on `AUTO` show
what it resolved to, so this table is visible in the UI, not folklore.

### The home button

**Tap goes home. Hold opens LightOS's dashboard by name** — `com.lightos/.MainActivity`,
not `CATEGORY_HOME` — because the thing a sideloaded LPIII loses is Light's own home
screen: install a launcher that can see your APKs, make it the default, and the dashboard
becomes unreachable. Both are rebindable to any installed app.

This is the one button where the mechanism is worth understanding, because it is the one
where the app can make the phone worse. Timing a hold means swallowing the DOWN and
starting a clock, and a consumed key cannot be handed back once you know it was a tap —
so from the moment the hold is bound, **BrightControl is what makes the home button work**.
That promise is one it refuses to make in five situations, each of which falls through to
the same place:

| Situation | Why |
|---|---|
| The takeover is off | By hand, or because it disarmed itself |
| Screen off, or the phone is locked | That press is a wake or an unlock, not yours — and a background activity start is dropped behind a keyguard anyway |
| LightOS is in front | Its dashboard and lock screen are one activity; home already goes there, so swallowing the key can only lose. Holds even with **LightOS screens** on |
| A clock is in front, or something is ringing | The same rule every key follows — see [Failing safe](#failing-safe) |
| The hold needs to start an activity and the overlay appop is missing | Without it Android 14 drops the start *in silence*, so the press would be consumed and nothing would happen |

Two rules keep one press to one action, and both were learned from the same bug — holding
home brought LightOS's dashboard over and then carried on into its menu.

**The release decides; nothing fires mid-press.** The hold used to go off on a timer while
the button was still down, which put an activity in front of the user in the middle of their
own press.

**A press already taken is a press owned to the end.** Every one of those checks asks about the
app in front, and the app in front changes *because of what the service just did* — the hold
fires, the dashboard comes over, and the release arrives to different answers than the press
did. Re-deciding at that point is how a consumed DOWN grew an unconsumed UP: LightOS got a lone
home release, read it as a home press of its own, and holding home brought the dashboard over
and then walked on into the menu. So only a fresh DOWN consults the rules; everything after it
belongs to the press. Hold once and the dashboard comes over, and only the *next* hold — which
is a fresh press, with LightOS now in front — reaches LightOS's own menu.

The fallback — "shadow" mode — consumes **nothing**. LightOS sees the entire press, long
presses behave exactly as they do with this app uninstalled, and the tap binding fires on
top afterwards if the press was short. It can't offer a hold, because by the time you know
a press was long it has already been delivered; firing home twice over is invisible, which
is why this shape is the home button's alone.

**Home is an activity start, not a synthetic key press.** This one cost a release to learn.
`performGlobalAction(GLOBAL_ACTION_HOME)` looks like the tidy accessibility-native answer —
no permission, no background activity start — but it doesn't start the home activity at all:
AOSP implements it as `sendDownAndUpKeyEvents(KEYCODE_HOME)`, an *injected key*. Injecting a
home key hands the press to whatever already has focus, and LightOS reads a home press as
"back to the idle face" — so on LightOS's own screens v1.0.21 flashed the dashboard and
bounced to the lock screen while the default launcher was never reached. A `CATEGORY_HOME`
intent doesn't ask anyone's opinion. The global action stays only as the fallback for when
there's no resolvable home activity or no overlay appop to start one with.

The other half of that lesson: **synthetic keys are refused at the door.** All five LPIII
controls are physical — a named input device, a nonzero Linux scancode — while an injected
key has `VIRTUAL_KEYBOARD` for a device and scancode 0. `LightKeys` checks that before
anything else, because a home binding that can see its own injected home press is a binding
that feeds itself. (`FLAG_FROM_SYSTEM` is not the test; real hardware keys carry it too.)

**And it disarms itself.** Two home dispatches in a row that report failure and the takeover
switches off permanently, handing the key back to the system and saying so on the front
screen with a `RETRY` next to it. Worth knowing how much that can catch, though: a blocked
background start is dropped silently and the global action returns true for "injected", not
for "went home", so this is the last guard rather than the first. The pre-flight refusals
above are what actually keep the key safe.

### Back to where you were

A home-button action, off until you bind it. Set **Home button opens** to *Back to where you
were*, then tick the apps that qualify in **Resume apps**. Sleep in one of them and the next
home press brings it back; the press after that goes home, and so does every press once you
have opened something else yourself.

### Scrolling apps that never heard of the wheel

Nothing lets a normal app inject a scroll — `INJECT_EVENTS` is signature-only. Two
routes exist from out here, and only one is acceptable: `dispatchGesture`, a synthetic
finger-drag needing only `canPerformGestures` (which does *not* imply reading the
screen) — this is what `SWIPE` uses — versus `ACTION_SCROLL_FORWARD` on an accessibility
node, which is precise but moves a whole screenful per notch and requires
`canRetrieveWindowContent`, i.e. the service could read everything on screen. Not worth
it for a scroll.

`SWIPE` is off by default and set per app. It's **one finger that never lifts**, not a
series of flicks: a `StrokeDescription` marked `willContinue` leaves the finger down,
and `continueStroke` moves it as the wheel turns — 64dp a notch, coalesced while a
stroke is in flight, capped at 0.6 screen or it reads as a fling. A continuation has to
start exactly where the last one ended, and the finger stays inside the middle 18–82% of
the screen — away from the edges where a drag becomes the system back/home gesture —
relifting and starting a fresh stroke from the middle when it reaches the end of that
band. That relift is the one visible seam and is unavoidable: a real thumb has the same
limit. A real finger touching the screen mid-scroll cancels the synthetic one rather
than fighting it. It's still a synthetic finger and never as good as an app scrolling
itself — apps that want per-notch scrolling implement it with the `hw/` module instead,
four files and no permissions, scrolling a `WebView` properly because it lives inside
the app that owns it.

### Camera-in-front

Anything registered for `STILL_IMAGE_CAMERA` gets both camera-button stages untouched
(memoised per package), so a third-party camera's shutter is never dead and "open the
camera" never fires again from inside a camera that's already open. An explicit per-app
rule of `OFF` still wins over this. The volume keys are bindable but pass through by
default — they're the one pair that already works, so consuming them out of the box
would be taking a function away to add one.

## The screen

### Colour, per app

LightOS forces the whole system to monochrome through the accessibility **daltonizer** —
two secure settings, `ENABLED` (0/1) and `MODE` (0 being monochromacy). BrightControl drives
those two off whichever app is in front: a package with a `Color` rule turns the daltonizer
off while it is on screen, `Mono` turns it on, `Default` restores the baseline the phone had
before any rule fired.

**It is written as state, never as a transition.** `applyFor` says what the daltonizer should
be *right now* for the given package and makes it so, idempotently. That is the whole design.
A transition-based version — "turn colour on when entering, restore when leaving" — has a
restore that can fire holding nothing, and there is no way back from it; a state-based one
corrects itself on the next event whatever happened in between.

Rules cycle `AUTO → COLOR → MONO → PASS`. **PASS writes nothing at all**, and exists for apps
that hold `WRITE_SECURE_SETTINGS` and set their own colour — [Roll](https://github.com/gi-os/Roll)
and [BrightChat](https://github.com/gi-os/BrightChat) both do. AUTO was never "no opinion"; it
resolves through a built-in table, and for those two the honest answer is "leave them to it".

Needs `WRITE_SECURE_SETTINGS`, which the ADB screen can grant. **Color → what happened** logs
every write and its read-back, so a rule that got overwritten by something else names the app
that did it.

### The lock screen

LightOS's lock screen isn't a keyguard window — it's a view inside LightOS's own single
activity, so locked or on the dashboard the focused window is
`com.lightos/com.lightos.MainActivity` either way, with no observable event marking the
difference. `ControlService` calls `KeyguardManager.isKeyguardLocked()` per key event
instead.

The optional Light face is **a window owned by the accessibility service, not an activity**,
and that distinction is the entire feature. v2.5 and v2.6 drew it as an activity marked
`showWhenLocked`, and the fingerprint reader stopped working — that flag marks the keyguard
*occluded*, and AOSP arms its fingerprint listener while occluded only for under-display
sensors, a dreaming device, or a bouncer already up. The LPIII's reader is in the power
button, so the face switched the sensor off by existing. A window at
`TYPE_ACCESSIBILITY_OVERLAY`, **layer 31, above the keyguard's 17**, never occludes anything:
the keyguard is showing, visible and listening exactly as always, and the power button
unlocks the phone untouched.

It draws the clock and date, notifications, four signal bars and a battery outline — both
glyphs rather than text, because "T-MOBILE" and "85%" are things you read and bars and a
fill are things you glance at. Type comes from LightOS's own scale ported from
`light-sdk`: named sizes scaled by screen height, spacing in 27-wide grid units, no
hardcoded sp or dp anywhere on the screen.

The background is a photo with a filter stack you assemble yourself — ordered Bayer dither
to halftone at a chosen cell size, black & white, opacity, corner blur, corner fade —
reorderable and repeatable, with a live preview at the panel's own aspect. It walks DCIM and
Pictures directly rather than using the system picker, because **nothing on LightOS keeps
MediaStore current**: there is no media provider doing the scanning a normal Android build
does, so a photo taken minutes ago is simply not offered by any picker. A directory listing
cannot go stale.

**On the lock screen** (off by default) takes the **buttons only** — turns still go to
LightOS untouched, since it already puts brightness there on both screens. The first version
took turns too and made LightOS unstable. **LightOS brightness** (on by default) is the one
lever over those turns and it only has an off position: on, LightOS gets the notch and dims
the screen; off, the notch is swallowed and *nothing* acts on it, not BrightControl's
brightness either. That is why it can ship — what made LightOS unstable was two owners
writing the same system brightness value a notch apart, and dropping a key has no second
writer in it.

### Volume

LightOS ships no volume UI at all. The keys work and media responds, but nothing on screen
says so, so the only way to find a level is to keep pressing until it is too loud and come
back down. On the ring and alarm streams there is no feedback whatsoever: a silent phone and
a phone at one notch look identical until something arrives.

So there is a HUD at the top of the screen, and it is **deliberately only a HUD — it reports,
it never adjusts.** Nothing here consumes a key, which is what makes it safe to put on the
volume keys. It listens to `android.media.VOLUME_CHANGED_ACTION`, so it also catches a media
app's own slider, a headset button, and a Bluetooth device turning itself down on connect.

## System

### Wi-Fi login — in development

> **This is unfinished and may not work.** It needs a system WebView to draw the login page
> in, and whether LightOS ships one is still unconfirmed. On a phone without one the page
> will not render and there is nothing this screen can do about it. Treat it as an
> experiment, not a feature.

A hotel or café network answers every request with its own login page until you submit it —
and on a phone with no browser there is nothing to submit it *with*, so the network connects
and then never validates. `PortalActivity` is that missing piece: a WebView **pinned to the
captive network** with `bindProcessToNetwork`, since an unvalidated Wi-Fi network is exactly
what Android routes around by default. It opens from the settings screen or from the system's
own `ACTION_CAPTIVE_PORTAL_SIGN_IN` flow, and closes itself once real traffic flows.

It does not join networks. Picking a network and typing its password is still LightOS
Settings. The state row reads the same capability bits the platform sets —
`NET_CAPABILITY_CAPTIVE_PORTAL` when a portal announced itself, `VALIDATED` once traffic
flows — so it agrees with what the system concluded rather than running a probe of its own.

### Hotspot — in development

> **This is unfinished and may not work.** It depends on Bluetooth pairing having exchanged
> an identity key, on the shell surviving a reboot, and on the iPad choosing to join. Any of
> those can leave the hotspot never coming up, or coming up and being ignored.

Folded in from [BrightHotspot](https://github.com/gi-os/BrightHotspot), which was a good app
with one fatal setup step. Raising an access point is `signature|privileged` and needs a
shell UID; BrightHotspot borrowed one from Shizuku, and Shizuku's way in is the
wireless-debugging pairing flow that Android tears down on every reboot. A setup step you
repeat forever is not a setup step, it is a fault. This app has held a shell the whole time
by a route that reconnects itself over mDNS with no re-pairing, so the same feature has
nothing to redo.

It watches for a paired device advertising over BLE and, when it is near and the phone is not
on a network you have marked as trusted, guesses that it wants a connection and raises the
hotspot. The device answers the guess by joining or not — a join confirms it, three minutes
of silence refutes it and earns a backoff, so a café with good Wi-Fi does not make the phone
flap. All of that lives in `hotspot/TriggerEngine.kt`, which has no Android in it and a test
beside it.

Three things are set up once. **The order matters — step 2 is the one everybody skips and it
is the one that makes the rest automatic.**

**1. Pair the two — from the phone.** iOS lists only the accessories it knows how to be, so
an **Android phone never appears in the iPad's Bluetooth list**, however long you stare at it.
Open Settings → Bluetooth on the iPad and leave it on that screen — that is what makes the
iPad advertise — then scan from LightOS's Bluetooth settings and accept the code on both
sides. This is not a formality: an iPad advertises under an address that rotates every few
minutes so it cannot be followed, and the identity key exchanged during pairing is the only
thing that turns that back into "this is my iPad". **Hotspot → CAN IT HEAR THE IPAD?** tells
you whether it worked.

**2. Join the hotspot by hand, once.** Turn the phone's hotspot on from LightOS, join it from
the iPad the ordinary way, and check *Auto-Join* is on for it. iOS will only join a network it
already knows without being asked, so this one manual join is what buys every automatic one
afterwards. Skip it and the hotspot will come up faithfully every time and the iPad will sit
there ignoring it.

**3. Tell BrightControl.** Set the network name and password to match exactly what the iPad
joined in step 2, pick the device to watch for, and add your home Wi-Fi under *leave it alone
here*.

**When it does not work.** Hotspot comes up and the iPad ignores it: step 2 was skipped, or
the name and password do not match, or Auto-Join is off. Hotspot never comes up: try *START
HOTSPOT NOW*, which says what the shell said — no adb connection is the usual answer. iPad
joins but has no internet: the phone has nothing to share, so either it is not on cellular or
the plan does not allow tethering. "Hearing other devices, but none of them are yours": the
pairing did not exchange the identity key, and presence triggering cannot work as built.

### ADB & grants

See [Quick start](#quick-start). Two details are worth knowing because they are what make it
safe rather than merely clever:

**The pairing reader cannot see anything else.** Reading the six digits off the Settings
dialog is a *separate* accessibility service declared with `packageNames="com.android.settings"`,
so it is structurally incapable of seeing another app's screen. `ControlService` keeps its
`canRetrieveWindowContent="false"` promise untouched.

**Nothing that arrives from another app is ever executed.** BrightMarket can send a list of
grants an app's README asks for, so a user does not have to find a computer for someone
else's app either. That string is **parsed, not run**: each line is matched against the small
set of things an app is allowed to need, and the command that actually runs is rebuilt here
from the parsed pieces. A request naming a package other than the sender is refused loudly,
because that is the shape an attack takes. The user sees the exact commands and nothing runs
until they say so.

### Diagnostics

Shake the phone and a report sheet comes up; it files a GitHub issue against the private
tracker with the build, the firmware, free space, heap, and the last crash. The gesture counts
*reversals* rather than force, which is what separates a deliberate rattle from a phone being
set down hard or carried. The accelerometer is registered on resume and dropped on pause, so
it is not a battery question.

A sideloaded app on a phone with no developer tools is otherwise a black box: it either works
or it "just closes", and the stack trace is in a logcat nobody has a cable for. So the crash
handler writes the stack to disk on the way out and the next launch offers to send it. The
default handler is still called afterwards — this records the crash, it does not swallow it.

## Privacy

`ControlService` declares one event type and `canRetrieveWindowContent="false"`. What it can
observe is exactly two things: key codes, and the package name of the app that came to the
front, which rides along with the event and needs no node access. Screen content is not
reachable, by declaration rather than by promise. The one service that *does* read a screen
is the ADB pairing reader, which is restricted to `com.android.settings` in the manifest.

## Failing safe

**One switch turns everything off**, first thing on the home screen, checked before anything
else in `onKeyEvent`: after it, the app is indistinguishable from uninstalled. It exists because
the only other way to stop an accessibility service is a computer, which is not what you have at
7am with an alarm going off.

A key filter is the one kind of app that can make a phone worse by breaking. Swallow a press and
then throw, and the key is simply gone. So:

**A clock keeps every key, always.** Anything registered for `SHOW_ALARMS` or `SET_ALARM` is
passed through entirely. The ringing check below only catches the moment audio is playing, and a
silenced alarm, a pre-alarm screen or a snooze countdown are all a clock in front with something
urgent to dismiss and no sound to detect.

**Nothing is intercepted while something is ringing — or for thirty seconds afterwards.** Any
active playback carrying a ring-ish usage, or the ringer or call audio mode being set, and every
key goes straight through. The grace window is there because the moment an alarm is *silenced*
looks identical to silence, while the screen with the stop button on it is still up.

**Four presses of the same binding and the service stands down.** Someone pressing the same
button over and over is someone whose phone is not doing what they asked.

**One activity start a second, at most.** The activity this most often starts is a launcher, and
launchers here run as uid 1000.

**Every fault answers "pass the key through".** `onKeyEvent` runs inside a catch that returns
false, because passing a key on is always safe and consuming one is not.

**Three faults in a minute and the service goes quiet** until the app is opened again. A dormant
filter is indistinguishable from an uninstalled one, which is the right thing to degrade into.
The last fault is shown on the front screen, because buttons that silently stopped working are
worse than buttons that say why.

## Layout

```
Bindings.kt                buttons, gestures, actions, and the out-of-the-box defaults
Prefs.kt                   settings, plus the table that decides untouched apps
MainActivity.kt            the settings hub and its section screens; parentOf encodes Back

keys/LightKeys.kt          the keycodes, resolved by label then by scancode
keys/ControlService.kt     the filter service: gesture split, consume rules, foreground app
keys/Brightness.kt         system brightness with a derived scale
keys/ColorMode.kt          per-app colour, written as state and never as a transition
keys/WheelSwipe.kt         the synthetic finger: one continued stroke, tracked and relifted
keys/Readout.kt            the brightness level, as one reused overlay window
keys/VolumeHud.kt          the volume level; reports, never adjusts
keys/Grants.kt             what's granted, and the volatile own-window flag

lock/LockOverlay.kt        the Light face as a service-owned window at layer 31
lock/LockBackground.kt     the photo and its filter stack
lock/LockGallery.kt        DCIM walked directly, because MediaStore is never current here
lock/LightType.kt          light-sdk's type scale and grid, for plain Views

adb/AdbManager.kt          the phone talking ADB to itself over loopback
adb/AdbPairOverlay.kt      pairing without leaving the Settings dialog
adb/GrantCheck.kt          whether a grant landed, asked of the phone not of the command
adb/GrantRequest.kt        another app's grant list, parsed and rebuilt, never executed

hotspot/TriggerEngine.kt   the raise/lower decision, with no Android in it
hotspot/SoftAp.kt          the access point, over the shell this app already holds
portal/PortalActivity.kt   the captive-portal WebView, bound to the captive network
report/                    shake to report, the crash log, and the queue
```

## Gotchas, in the order they'll bite

- **The accessibility setting is a list, not a flag.** `settings put secure
  enabled_accessibility_services` overwrites whatever was there — enabling BrightControl
  naively turns off LightVoice's push-to-talk unless both components are colon-joined.
- **Consuming a key is the dangerous half.** The service consumes only what it actually
  acted on, so a bug here can't trap you.
- **The click is a modifier and a button at once.** A held `WHEEL_CLICK` produces no key
  repeat, so the press is remembered on DOWN and the torch only fires on UP if no notch
  arrived in between.
- **The camera button sends two scancodes**, and the order flips between presses —
  `FOCUS` sometimes first, `CAMERA` other times. Only `CAMERA` triggers a binding;
  `FOCUS` is swallowed alongside it so the app never sees half a press.
- **The readout overlay raises its own window-state events.** Trusting those would
  rewrite "the app in front" to BrightControl mid-turn. Events from this package are
  ignored; the settings activity reports itself through a volatile flag instead.
- **Brightness has no fixed scale.** 255 is common; 1023, 2047 and 4095 all ship. It's
  derived from `screen_brightness` divided by `screen_brightness_float`.
- **The daltonizer's off state is not `mode = 0`.** `enabled = 0` with `mode = 0` still
  reads as monochrome; off is mode `-1`.
- **The adb shell service carries no exit status.** It merges stdout and stderr and returns
  no code, so "printed nothing" covers both success and a command that never ran. Every
  grant is read back off the phone instead.
- **A BLE scan with no permission does not throw.** It returns nothing, forever, which is
  indistinguishable from an iPad that is not there.

## Not doing

- **Node-based scrolling.** `ACTION_SCROLL_FORWARD` is precise but needs
  `canRetrieveWindowContent` — reading the whole screen to scroll a list isn't a trade
  worth making.
- **Remapping the power button.** Long-press is the hardware power menu, below the
  framework.
- **A launcher tile per app.** The point is that the phone behaves consistently, not
  that there's more to configure.

## Building

```bash
./gradlew :app:assembleDebug
```

## Contributing

Solo repo, no PR workflow: commits go straight to `main`, and every push to `main`
triggers CI, which builds, signs, and publishes a GitHub Release. **A push is a
release, not a cosmetic action** — verify before pushing, not after. Documentation-only
pushes are excluded by `paths-ignore`.

The keystore is committed under `keystore/`, so every build carries the same signing
certificate and upgrades install over the top; CI pins the certificate's SHA-256 in
`signing-fingerprint.txt` and fails on drift. `versionCode` is the workflow run number;
`versionName` in the committed `build.gradle.kts` is only the `major.minor` base — CI
stamps `major.minor.RUN` at build time and tags it `vX.Y.Z`.

## Version history

Real tags, newest first. `RELEASE_NOTES.md` holds the full entry for the current release.

| Version | What changed |
| --- | --- |
| v3.14 | **Wi-Fi login and Hotspot are labelled as unfinished, in the app and here.** Both shipped looking like finished features and neither is: the portal needs a system WebView this phone may not have, and the hotspot depends on a BLE identity key, a shell that survives a reboot, and an iPad that chooses to join. A feature that might not work is fine; one that does not say so is not. Also a README rewritten around what the app now is — six subsystems rather than the wheel and two buttons — with a Quick start that no longer opens by telling you to find a computer, and a Layout block that no longer names files that were deleted two releases ago |
| v3.13 | **The apps that ship on PASS could not be tapped off it.** Roll and BrightChat both hold the daltonizer grant and set their own colour, so both ship as PASS in Color → Per-app rules — and both rows sat there unchanged however often they were tapped. The step after PASS is AUTO, AUTO stores nothing, and storing nothing resolves back through the preset table to PASS: two correct rules that cancelled out, on exactly the two apps the feature is for. The step is now picked by what it resolves to, so those rows cycle PASS → COLOR → MONO |
| v3.12 | **The ADB connection reconnects itself.** The daemon's TLS listener does not survive leaving the Wireless-debugging screen and comes back on a new port, so the connection made during setup was dead by the time anyone walked back to the button that needed it — six grants, six `Stream closed`. The pairing is what needs a human; the port is discovery. Every batch now reconnects in front of itself |
| v3.11 | **The colour log names the app in full.** Package ids were cut to their last segment, so the line that mattered read as a bare word like `edgegestures`: nothing you can look up or grant a rule to |
| v3.10 | **PASS, presets, and grants that say whether they worked.** A fourth colour rule that writes nothing at all, for apps that hold `WRITE_SECURE_SETTINGS` and set their own colour. And every ADB grant is read back off the phone rather than judged by what the command printed |
| v3.9 | **The colour diagnostic was reporting on a phone that no longer existed.** The log was read once at composition, so coming back from the app you had just tested showed "Nothing applied yet" over six applied rules. Both the screen and the send title re-read on resume |
| v2.15 | **Apps that own the whole wheel.** BrightRecorder is hands-off outright: its wheel press is play/stop, and the click's default is the torch, so the press would have been eaten before the app saw it |
| v2.14 | **The camera button works from the lock face.** At layer 31 the face is above even an app that has just come to the front, so the shutter fired and the viewfinder was never visible. Any binding that brings something forward now takes the face down with it |
| v2.13 | **The face holds black for half a second, then fades up.** Painting a lock screen only to remove it inside the unlock window is a flicker that reads as a fault |
| v2.12 | **BrightChat's photo grid, not the system picker.** Nothing on LightOS keeps MediaStore current, so a photo taken minutes ago is not offered. The editor now walks DCIM and Pictures itself |
| v2.11 | **The background editor, on the lock screen.** Dither, black & white, opacity, corner blur, corner fade — reorderable and repeatable, with a live preview at the panel's own aspect |
| v2.10 | **Two defects in v2.9's unlock watch, one of which ran all night.** The 300 ms poll was tied to the face being up rather than to the screen being on, so it ticked three times a second for every hour the phone spent asleep |
| v2.9 | **The face comes down on unlock, and unlocking opens something.** Neither `ACTION_USER_PRESENT` nor the keyguard listener was arriving, so nothing noticed the phone had opened |
| v2.8 | The lock face uses **LightOS's own type scale and grid**, ported from `light-sdk`. No hardcoded sp or dp anywhere on the screen |
| v2.7 | **The thumb works.** The lock face stopped being an activity — `showWhenLocked` marks the keyguard occluded, which switches off a power-button fingerprint reader. It is now a service-owned window at layer 31, above the keyguard's 17 |
| v2.6 | **The lock face, actually working.** Three bugs, two of them one bug: the bouncer stops the occluding activity, so the unlock broadcast arrived after the receiver was gone |

## Licence

MIT. The icons and design tokens come from
[`lightphone/light-sdk`](https://github.com/lightphone/light-sdk), MIT, © The Light Phone —
the 27×31 grid, the type scale and the haptics. See `LICENSE-light-sdk`.

<!-- bright-footer:begin -->
---

## Bright\*

**It's not Light, it's Bright.**

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightNotebook](https://github.com/gi-os/BrightNotebook) · **BrightControl** (you are here) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->
