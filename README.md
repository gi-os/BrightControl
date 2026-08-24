# BrightControl

The Light Phone III's brightness wheel, camera button, and home button, working inside
apps Light didn't write.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightControl.png" alt="Scan to open BrightControl in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightControl there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html)**.

**Current version: v3.9.** See [Version history](#version-history).

| Gesture | Out of the box |
|---|---|
| Turn the wheel | Brightness — or a per-notch scroll, or passed through, per app. On LightOS's own screens it stays LightOS's, unless you switch that off |
| Double tap the wheel | Switches turning between brightness and scrolling, and says which |
| Tap the wheel | Flashlight |
| Hold the wheel | nothing — bind it to any app |
| Tap the camera button | The Light camera |
| Hold the camera button | nothing — bind it to any app |
| Tap the home button | Home — whichever launcher is default |
| Wake the phone | The stock lock screen — or a Light face over it, once you turn it on |
| Hold the home button | LightOS's dashboard, by name — rebind it to anything |
| Volume keys, tap or hold | passed through, but bindable |

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

```bash
adb install -r LightControl-v1.0.11.apk

# 1. the key service. This *replaces* the enabled list, so if you also run
#    LightVoice's push-to-talk, join them with a colon instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# 2. brightness
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow

# 3. the level readout, and letting the camera button start an activity from a service
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

The app's first screen re-checks all three grants and shows the exact command for
whichever is missing, so this is recoverable without re-reading the README. Without (2)
the wheel can't change brightness at all — a service has no window of its own, so
there's no per-app fallback. Without (3) the readout silently doesn't appear and the
camera button does nothing; brightness still works.

## Configuration and usage

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
`com.gios.brightrecorder` sits inside `com.gios.` and would otherwise be claimed by it. Per-app overrides cycle on tap through
`AUTO → BRIGHT → SWIPE → APP → OFF`, and rows left on `AUTO` show what it resolved to,
so this table is visible in the UI, not folklore.

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

### The lock screen

LightOS's lock screen isn't a keyguard window — it's a view inside LightOS's own single
activity, so locked or on the dashboard the focused window is
`com.lightos/com.lightos.MainActivity` either way, with no observable event marking the
difference. `ControlService` calls `KeyguardManager.isKeyguardLocked()` per key event
instead. **On the lock screen** (off by default) takes the **buttons only** — turns
still go to LightOS untouched, since it already puts brightness there on both screens.
The first version took turns too and made LightOS unstable. An app binding still waits
for an unlock: a background activity start behind a lock is dropped unless the target
declares `showWhenLocked`, which isn't BrightControl's to declare.

**LightOS brightness** (on by default) is the one lever over those turns, and it only has
an off position. On, LightOS gets the notch and dims the screen. Off, the notch is
swallowed and *nothing* acts on it — not BrightControl's brightness either. That is the
whole reason it can ship: what made LightOS unstable the first time was two owners writing
the same system brightness value a notch apart, and dropping a key has no second writer in
it. The switch is for keeping a brightness where you put it — a wheel that lives under a
thumb in a pocket is a wheel that dims the screen on its own. It applies on both LightOS
screens whether or not **LightOS screens** is on, since it takes behavior away rather than
claiming any, and it changes nothing in any other app.

### Camera-in-front

Anything registered for `STILL_IMAGE_CAMERA` gets both camera-button stages untouched
(memoised per package), so a third-party camera's shutter is never dead and "open the
camera" never fires again from inside a camera that's already open. An explicit per-app
rule of `OFF` still wins over this. The volume keys are bindable but pass through by
default — they're the one pair that already works, so consuming them out of the box
would be taking a function away to add one.

## Hotspot: connecting the iPad

Three things are set up once and then never again. The order matters — step 2 is the one
everybody skips and it is the one that makes the rest automatic.

**1. Pair the two — from the phone.** This is the wrong way round from how it sounds, and it is
the only way it works: iOS lists only the accessories it knows how to be, so an **Android phone
never appears in the iPad's Bluetooth list**, however long you stare at it.

Open Settings → Bluetooth on the iPad and leave it on that screen — that is what makes the iPad
advertise. Then scan from LightOS's Bluetooth settings: the iPad turns up there. Tap it, accept
the six-digit code on both sides.

This is not a formality. An iPad advertises under an address that rotates every few minutes so it
cannot be followed, and the identity key exchanged during pairing is the only thing that turns
that back into "this is my iPad". No pairing, no recognition, and nothing this app can do from its
side. **BrightControl → Hotspot → CAN IT HEAR THE IPAD?** tells you whether it worked.

**2. Join the hotspot by hand, once.** Turn the phone's hotspot on from LightOS, join it from the
iPad the ordinary way, and check *Auto-Join* is on for it in Settings → Wi-Fi.

This is the whole trick. iOS will only join a network it already knows without being asked, so
this one manual join is what buys every automatic one afterwards. Skip it and the hotspot will
come up faithfully every time and the iPad will sit there ignoring it.

**3. Tell BrightControl.** Hotspot →

- **THE NETWORK IT RAISES** — READ FROM PHONE, or type the name and password. They have to match
  what the iPad joined in step 2 exactly, or it meets a network it has never seen.
- **WATCH FOR** — turn on the iPad.
- **LEAVE IT ALONE HERE** — add your home Wi-Fi, so it does not raise a hotspot where everything
  already has internet.
- **AUTO** — turn on *Watch for the device*.

Then: open the iPad near the phone, off home Wi-Fi, and the hotspot comes up within a scan window
— 15 to 45 seconds — and the iPad joins on its own. Nothing to tap on the iPad, nothing installed
on it. Nobody joins within three minutes and it stands back down and stops guessing for a while,
so a cafe with good Wi-Fi does not make the phone flap.

### When it does not work

**Hotspot comes up, iPad ignores it.** Step 2 was skipped, the name or password does not match
what the iPad knows, or Auto-Join is off for that network on the iPad.

**Hotspot never comes up.** Try *START HOTSPOT NOW*. It says what the shell said. No adb
connection is the usual answer, and the ADB screen is where to fix it.

**iPad joins, but has no internet.** Nothing to do with any of this — the phone has nothing to
share. Either it is not on cellular data, or the carrier does not allow tethering on that plan.
Worth knowing when travelling: a SIM that tethers at home may not while roaming.

**"Hearing other devices, but none of them are yours."** The pairing did not exchange the identity
key on this phone. Presence triggering cannot work as built; the fallbacks are matching Apple's
Continuity advertisements instead (looser, still needs nothing on the iPad) or a small trigger on
the iPad side.

## Privacy

The service declares one event type and `canRetrieveWindowContent="false"`. What it can
observe is exactly two things: key codes, and the package name of the app that came to
the front, which rides along with the event and needs no node access. Screen content is
not reachable, by declaration rather than by promise.

## Layout

```
Bindings.kt              buttons, gestures, actions, and the out-of-the-box defaults
keys/LightKeys.kt        the seven keycodes, resolved by label then by scancode
keys/ControlService.kt   the filter service: gesture split, consume rules, foreground app
keys/Brightness.kt       system brightness with a derived scale
keys/WheelSwipe.kt       the synthetic finger: one continued stroke, tracked and relifted
keys/Readout.kt          the level, as one reused overlay window
keys/Grants.kt           what's granted, and the volatile own-window flag
Prefs.kt                 settings, plus the table that decides untouched apps
ui/SettingsScreen.kt     grants first, then the mapping
ui/ButtonsScreen.kt      every button's tap and hold, side by side
ui/PickerScreen.kt       what one gesture does: four fixed choices, then every app
ui/AppListScreen.kt      every launchable app, tap to cycle its rule
```

## Failing safe

**One switch turns everything off**, first thing on the settings screen, checked before anything
else in `onKeyEvent`: after it, the app is indistinguishable from uninstalled. It exists because the
only other way to stop an accessibility service is

```bash
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
```

— a computer, in other words, which is not what you have at 7am with an alarm going off. (Re-enabling
takes both services colon-joined, since that setting overwrites the whole list:
`com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService:com.gios.lightvoice/com.gios.lightvoice.ptt.PttService`.)

A key filter is the one kind of app that can make a phone worse by breaking. Swallow a press and
then throw, and the key is simply gone — which on the wrong morning is an alarm that won't turn
off. So three rules, all of them learned the hard way:

**A clock keeps every key, always.** Anything registered for `SHOW_ALARMS` or `SET_ALARM` — on this
phone `com.android.deskclock`, which is not in the hands-off prefix list — is passed through
entirely. The ringing check below only catches the moment audio is playing, and a silenced alarm, a
pre-alarm screen or a snooze countdown are all a clock in front with something urgent to dismiss and
no sound to detect. An alarm is the one thing here where being clever costs you a morning.

**Nothing is intercepted while something is ringing — or for thirty seconds afterwards.** Any active
playback carrying a ring-ish usage (alarm, ringtone, notification, voice call), or the ringer or call
audio mode being set, and every key goes straight through. Whatever is making the noise owns the
dismiss gesture, and guessing which key it wants is exactly the guess that fails at 6am. The grace
window is there because sampling only at key events means the moment an alarm is *silenced* looks
identical to silence, while the screen with the stop button on it is still up and being pressed at.

**Four presses of the same binding and the service stands down.** Someone pressing the same button
over and over is someone whose phone is not doing what they asked; whatever the service thinks is
happening, it is wrong, and the useful thing it can do is stop. A fight with a key filter is one the
phone loses.

**One activity start a second, at most.** The activity this most often starts is a launcher, and
launchers here run as uid 1000. A launcher restarted repeatedly while it is showing something modal
is a system process being asked to do something no user could ask it to do.

**Every fault answers "pass the key through".** `onKeyEvent` runs inside a catch that returns
false, because passing a key on is always safe and consuming one is not.

**The home takeover disarms itself.** The one binding that has to swallow the home button is
also the one that can leave you unable to get home, so it is the only one that watches whether it
worked: two presses in a row where nothing reported success and it hands the key back to the
system for good, until you tap `RETRY`. See [The home button](#the-home-button).

**Three faults in a minute and the service goes quiet** until the app is opened again. Retrying
forever is how a single bug becomes a phone you can't dismiss an alarm on; a dormant filter is
indistinguishable from an uninstalled one, which is the right thing to degrade into. The last
fault is shown on the front screen, because buttons that silently stopped working are worse than
buttons that say why.

The gesture path got the same treatment specifically. `StrokeDescription`, `continueStroke` and
`addStroke` all throw `IllegalArgumentException` on a path the framework dislikes — a continuation
that doesn't begin where the last ended, a coordinate off the display — and the lift runs from a
Handler where no key-event catch can reach it.

### Back to where you were

A home-button action, off until you bind it. Set **Home button opens** to *Back to where you
were*, then tick the apps that qualify in **Resume apps**. Sleep in one of them and the next
home press brings it back; the press after that goes home, and so does every press once you
have opened something else yourself.

The rules are deliberately narrow, because this is the home button:

- **Opt-in per app.** Not "whatever you were last in" — a home button that sometimes goes home
  and sometimes returns you to Settings is one you cannot trust. With nothing ticked, or nothing
  to go back to, the action *is* `Home`.
- **Spent on use.** The first press consumes the offer, which is what makes the second press
  mean something else without a second binding or a timer.
- **The second press is yours to set.** **Otherwise open**, on the Resume apps screen: Home, or
  any launchable app. This is not a nicety — LightOS has to hold the HOME role or it crash-loops,
  so plain "home" means LightOS rather than whatever launcher you use, and without this the
  action would trade your home screen for the feature. Point it at what the tap was bound to
  before and Resume becomes purely additive. It is also where *most* presses land: nothing slept
  in, nothing ticked, offer already spent.
- **Withdrawn when you go elsewhere.** Any window from a package that isn't LightOS's clears it,
  so the offer only stands while you're still sitting on the lock screen or the dashboard, which
  is where a wake leaves you.

**This is the only place the feature could live.** An app cannot bring itself back when the
screen comes on: a backgrounded app is cached, a cached app is frozen, and context-registered
broadcasts to a frozen app are queued until it is unfrozen — so `ACTION_SCREEN_ON` is delivered
only after something else has already brought the app forward. An `AccessibilityService` is
bound by the system and so is never cached, never frozen, already watching the foreground
package, and already holding the home key. BrightRemote v1.14 shipped the version that can't
work; v1.15 removed it.

LightOS's lock screen appears *as* the screen goes off, which would otherwise be recorded as
where you were. A LightOS window that arrived within two seconds of the broadcast is treated as
the lock screen arriving, and the app underneath it is remembered instead.

## Gotchas, in the order they'll bite

- **The accessibility setting is a list, not a flag.** `settings put secure
  enabled_accessibility_services` overwrites whatever was there — enabling BrightControl
  naively turns off LightVoice's push-to-talk unless both components are colon-joined.
- **Consuming a key is the dangerous half.** The service consumes only what it actually
  acted on, so a bug here can't trap you — turns and clicks are inert keys in almost
  every app, and the camera and home buttons are the ones that could otherwise have done
  something.
- **The click is a modifier and a button at once.** A held `WHEEL_CLICK` produces no key
  repeat, so the press is remembered on DOWN and the torch only fires on UP if no notch
  arrived in between.
- **The camera button sends two scancodes**, and the order flips between presses —
  `FOCUS` sometimes first, `CAMERA` other times. Only `CAMERA` triggers a binding;
  `FOCUS` is swallowed alongside it so the app never sees half a press.
- **The readout overlay raises its own window-state events.** Trusting those would
  rewrite "the app in front" to BrightControl mid-turn. Events from this package are
  ignored; the settings activity reports itself through a volatile flag instead, since
  service and activity share a process.
- **Brightness has no fixed scale.** 255 is common; 1023, 2047 and 4095 all ship. It's
  derived from the platform's own two mirrors of the value:
  `screen_brightness` divided by `screen_brightness_float`.

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
release, not a cosmetic action** — verify before pushing, not after.

The keystore is committed under `keystore/`, so every build carries the same signing
certificate and upgrades install over the top; CI pins the certificate's SHA-256 in
`signing-fingerprint.txt` and fails on drift. `versionCode` is the workflow run number;
`versionName` in the committed `build.gradle.kts` (currently `2.4.0`) is only the
`major.minor` base — CI stamps `major.minor.RUN` at build time and tags it `vX.Y.Z`.

## Version history

Real tags, oldest to newest:

| Version | What changed |
| --- | --- |
| v3.9 | **The color diagnostic was reporting on a phone that no longer existed.** Color → what happened read the log once, when the screen was first composed, so coming back from the app you had just tested showed "Nothing applied yet" over six applied rules — and Send log wrote its title off that same stale snapshot while the body read the log fresh. Both are re-read on resume, and the title at send. Also: a read-back that finds the *next* app's rule in place is `superseded`, not `LOST` — two apps swapping inside a second used to file two successful writes as a fault |
| v2.15 | **Apps that own the whole wheel.** The scroll-aware rule passes an app's turns but keeps the click, and the click's default is the torch — so an app whose wheel *press* is a control of its own never saw it, with nothing it could do from its side. BrightRecorder, a tape recorder where pressing the wheel plays and stops, is now hands-off outright: every key reaches it untouched and the turn mode does not apply. Consulted before the scroll-aware list, since `com.gios.brightrecorder` sits inside `com.gios.`. The trade is that the torch and camera key do nothing of ours while it is in front; nothing changes for the rest of the family |
| v2.14 | **The camera button works from the lock face.** It always started the camera — but at layer 31 the face is above even an app that has just come to the front, so the shutter fired, the photos were taken, and the viewfinder was never visible. Any binding that brings something forward (camera, an app, LightOS, Resume) now takes the face down with it, and it stays down until the next sleep. The torch and the volume keys deliberately do not, since they change nothing about what is on screen |
| v2.13 | **The face holds black for half a second, then fades up.** Unlocking with your thumb already on the button opens the phone in a couple of hundred milliseconds, and painting a lock screen only to take it away again inside that window is a flicker that reads as a fault. So the panel lights black and stays black; if the phone is still locked when the wait is out, the face fades in over 320 ms. A thumb that landed first takes the window down before the fade starts and nothing is ever seen |
| v2.12 | **BrightChat's photo grid, not the system picker.** The picker reads MediaStore and nothing on LightOS keeps MediaStore current, so a photo taken minutes ago simply is not offered — which made v2.11's SAF picker the same dead end behind a different door. The background editor now walks DCIM and Pictures itself, newest first, three across, one tap to choose. A directory listing cannot go stale. Needs `READ_MEDIA_IMAGES`, which it asks for itself |
| v2.11 | **BrightChat's background editor, on the lock screen.** Pick a photo, choose how it meets the panel, then stack filters on it — ordered Bayer dither to halftone at a chosen cell size, black & white, opacity, corner blur, corner fade — reorderable and repeatable, with a live preview at the panel's own aspect and drag-to-frame the crop. The same pipeline as the chat wallpapers, unchanged. Also: **the flash of LightOS before your app is gone** — the face is now held up *over* the handover and comes down when the target reports itself in front, because taking it down first uncovers whatever the system put there and LightOS holds the HOME role. Plus a **12-hour clock**, and the two prompt lines are now a toggle, off by default |
| v2.10 | **Two defects in v2.9's unlock watch, one of which ran all night.** The 300 ms poll was tied to the *face being up* rather than to the screen being on, so it ticked three times a second for every hour the phone spent asleep, in a process the system is not allowed to freeze — the exact shape that has taken this phone down before. It now starts on screen-on and stops on screen-off, which is the few seconds it was ever meant to cover. And the keyguard locked-state listener was registered and never removed, so every service rebind left another one calling into an unbound instance. Also: the lock picture is decoded once and kept rather than re-decoded on every sleep |
| v2.9 | **The face comes down on unlock, and unlocking opens something.** Both were the same bug: neither `ACTION_USER_PRESENT` nor the keyguard's locked-state listener was arriving, so nothing noticed the phone had opened and the resume never ran either. There is now a third signal that cannot be missed — a 300 ms poll of `isDeviceLocked` while the face is up, which is the state rather than a notification about it — and `hide()` no longer drops its handle on the window before the removal has succeeded. **Resume apps and Otherwise open moved into the LOCK SCREEN section**, where they were previously unreachable unless the *home button* happened to be bound to Resume: point Otherwise open at Luma and that is where an unlock lands. Plus: signal bars replace the carrier name, a smaller top bar and prompt, and the date written as "Sunday, August 16" |
| v2.8 | The lock face now uses **LightOS's own type scale and grid**, ported from `light-sdk` — Akkurat resolved by weight off the system font list, named sizes (`title` for the clock, `button` with 15% tracking for the prompt, `copy` over `detail` for notifications) scaled by screen height the way the SDK does it, spacing in 27-wide grid units, and `contentSecondary` as its actual `#BBBBBB`. No hardcoded sp or dp anywhere on the screen |
| v2.7 | **The thumb works.** The lock face stopped being an activity. `showWhenLocked` marks the keyguard *occluded*, and AOSP arms its fingerprint listener while occluded only for under-display sensors — the LPIII's is in the power button, so v2.5 and v2.6 switched the sensor off by existing. The face is now a window the accessibility service owns at `TYPE_ACCESSIBILITY_OVERLAY`, **layer 31, above the keyguard's 17**, so the keyguard is never occluded: it is showing, visible and listening exactly as it always is, and the power button unlocks the phone untouched. Tap the face to put it away and reach the keypad. Falls out for free: no flash and no 900 ms delay to tune, no overlay appop, and nothing that can hold focus or trap you |
| v2.6 | **The lock face, actually working.** Three v2.5 bugs, two of them one bug. A correct PIN left the face up over an unlocked phone — the bouncer *stops* the occluding activity, so the activity had unregistered its `ACTION_USER_PRESENT` receiver by the time the broadcast arrived; noticing the unlock moved to the service, which is bound and never stopped. The stock screen flashed before ours on every wake because LightOS's lock screen is an activity that comes over *as* the screen goes off, so ours was starting underneath it — the face is now raised 900 ms later, on top of a lock screen that has finished coming up, and re-asserted on screen-on. And **the thumb never worked and could not have**: the LPIII's sensor is in the power button, and AOSP only keeps the keyguard listening to it while occluded for under-display sensors. Swipe up or tap to raise the bouncer, where it works — or set **Unlock → ON WAKE** to have that happen automatically |
| v2.5 | **A Light lock face.** An opt-in screen drawn over the stock lock screen — top bar, clock, your own picture, and the notifications waiting — and unlocking it lands wherever the home button's Resume would have: the app you fell asleep in, or Luma. It does not replace the keyguard and cannot: the real one is still underneath, still what your thumb opens, and it is what takes your code. Off by default, disarms itself if it ever fails to start |
| v2.4 | **Visiting LightOS.** Holding home to open LightOS now marks a *visit*: while it lasts, home belongs to LightOS — its menu opens on a press again, which v2.1's "the tap takes the key anywhere" had broken. **Double-press home to end the visit** and fire the tap binding (LightOS sees both presses, so its menu flickers once on the way out). A visit ends by leaving LightOS, by the double press, or by the screen going off — a wake is a landing, not a visit, so a single press still escapes the idle face |
| v2.3 | **A text message no longer turns the filter off for half a minute.** `USAGE_NOTIFICATION` and `USAGE_NOTIFICATION_EVENT` counted as "ringing", and ringing carries a 30-second grace window — so every notification ping was thirty seconds of dead keys: home passing through to LightOS, wheel dead, all refused upstream of every log line. The ring guard now covers what it was built for — alarms, ringing calls, calls in progress. And the whole-filter refusal logs itself (`filter down — ringing` / `dormant` / `switched off`), so it can never go silent again |
| v2.2 | **Launching a launcher works.** `getLaunchIntentForPackage` answers null for a launcher that publishes no `CATEGORY_LAUNCHER` entry, and that null read as "cannot be opened" — whose fallback is home. Home bound to Luma landed on LightOS, deterministically. A HOME intent scoped to the package now resolves a launcher's own front door first. Also: every home refusal now logs under its own name (`takeover off` / `screen off` / `keyguard locked` / `no overlay appop`) instead of one opaque `not consumable`; the clock refusal logs instead of being silent; and LightOS is never read as a clock, whatever intents it declares |
| v2.1 | **Home works on LightOS's screens again.** The hands-off gate ran before the home button's own door, so on the dashboard or after a wake the tap binding never fired — not even in shadow mode — and a tap pointed at a launcher left you stranded on the idle face. Home is now routed to its door first; hands-off is one of that door's refusals, and a tap that names its own destination takes the key anywhere. A shadow tap also no longer fires behind the keyguard, where the launch was dropped silently but still stamped the throttle and made the next real press read as a repeat. And **`SWIPE` now respects the mode switch**: double-tapping the wheel to BRIGHTNESS changes what a turn does in swipe-scrolled apps too, instead of flipping the readout while every notch kept swiping |
| v1.5.0 | `Otherwise open`: Resume's second press is configurable, so it stops costing you Luma |
| v1.4.0 | `Back to where you were`: home returns you to the app the screen slept in, once |
| v1.0.1 | Initial release — the wheel and camera button, working phone-wide |
| v1.0.2 / v1.0.3 | Same commit, re-tagged (`.gitignore` restored after being lost in a sync) |
| v1.0.4 | `SWIPE`: one continuous finger-drag per app, instead of discrete flicks |
| v1.0.5 | LightPhono recognised as a `com.gios.*` app rather than falling under Light's defaults |
| v1.1.6 | Camera button now goes to whatever camera app is in front, not just Light's |
| v1.0.7 | Buttons and bindings now work on the lock screen |
| v1.0.8 | Lock-screen detection reworked around `KeyguardManager`, since the lock screen is a view, not a separate window |
| v1.0.9 | Double-tap the wheel to switch it between brightness and scrolling, instead of holding it |
| v1.0.10 | LightOS's own screens now take button bindings but leave turns untouched |
| v1.0.11 | Home button added: tap and hold, bindable, falls back to Light's home |
| v1.0.12 – v1.0.14 | Home tap follows the default launcher; the button is left alone until it's bound; brightness mode outranks an app's own scrolling |
| v1.0.15 / v1.0.16 | Home tap goes home and the hold stays LightOS's — nothing consumed unless the hold is bound |
| v1.0.18 | Failing safe: a throw never takes a key away, and three faults in a minute put the filter to sleep |
| v1.0.19 | A clock in front keeps every key it can see |
| v1.0.29 | **A master switch**, first on the screen, after which the app is indistinguishable from uninstalled. Plus the guards a bad morning bought: hands off for 30 s after anything rings, widened to every ring-ish usage and the ringer/call modes; standing down when the same binding fires four times over; one activity start a second; and LightOS reached by home intent rather than by component when it *is* the default launcher. (The morning in question turned out not to be this app — a different one was crash-looping a foreground service and had flooded the task stack with several hundred permission dialogs — but every guard here is right regardless) |
| v1.0.27 | The release decides: the hold no longer fires on a timer mid-press, so nothing comes to the front while the button is still down. The same binding twice inside 350 ms counts once. New on-screen **key log** — the last dozen decisions, for when a filter needs to explain itself and there's no adb in your pocket |
| v1.0.25 | A press the service took is owned to the end: only a fresh DOWN consults the front-app rules, so a binding can't hand the rest of its own press to the thing it just launched. Holding home brought the dashboard over and then went on into the menu |
| v1.0.23 | Home goes to the default launcher by intent again. `GLOBAL_ACTION_HOME` injects a `KEYCODE_HOME` rather than starting home, which LightOS read as "back to the idle face" — a flashed dashboard and a bounce to the lock screen. Synthetic keys are now refused before recognition |
| v1.3.33 | **LightOS brightness, switchable off.** A turn on the lock screen or the dashboard is swallowed instead of reaching LightOS, so its brightness ramp never runs and the level stays where it was put. Deliberately only an off position: nothing takes over those turns, because two owners writing one system brightness value is what made LightOS unstable the first time this service went near them |
| v1.0.21 | **Hold home opens LightOS's dashboard by name.** The takeover refuses the key when the screen is off, the phone is locked, LightOS is in front, or the hold would need an activity start it hasn't been granted — and disarms itself permanently after two dispatches that report failure |

Note: **v1.1.6 is a real tag**, not a typo introduced here — the `major.minor` base in
`build.gradle.kts` was briefly `1.1` for that one release and reverted to `1.0` for the
next, which is why the sequence goes `...v1.0.5, v1.1.6, v1.0.7...` rather than climbing
in order. Worth a look before the next minor-version bump.

## Licence

MIT.
