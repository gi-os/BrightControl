# LightControl

The Light Phone III's brightness wheel and camera button, working inside apps Light didn't
write.

| Gesture | Out of the box |
|---|---|
| Hold the wheel in and turn | Brightness, with the level shown at the bottom of the screen |
| Tap the wheel | Flashlight |
| Hold the wheel | nothing — bind it to any app |
| Tap the camera button | The Light camera |
| Hold the camera button | nothing — bind it to any app |
| Volume keys, tap or hold | passed through, but bindable |
| Turn the wheel | Brightness — or a swipe, or passed through, per app |

Every button has a **tap** and a **hold**, bound separately, and either can open any installed
app. Light's own tools are left strictly alone, and any app can be overridden individually.

Tap and hold are told apart by time, not by key repeat: a held key on this phone repeats
never, so DOWN schedules the hold and UP either cancels it and runs the tap, or finds the hold
already fired. The wheel click has a third possibility — a notch arriving mid-press turns the
whole thing into a brightness gesture, which cancels the pending hold *and* suppresses the
tap, because ending a brightness adjustment with the flashlight coming on is a nasty surprise
in the dark.

## Why any of this is necessary

Light patched `/system/usr/keylayout/Generic.kl`, the layout every input device on the phone
loads, so the wheel and camera button arrive as ordinary key events:

```
key 19    WHEEL_CCW      # wheel up       (Pixart pat9126ja, was R)
key 20    WHEEL_CW       # wheel down     (Pixart pat9126ja, was T)
key 66    WHEEL_CLICK    # wheel press    (gpio-keys, was F8)
key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
```

Nothing intercepts them in `PhoneWindowManager`. They go to the focused window, and the
brightness ramp and flashlight live in the app layer of Light's own tools — so in any
sideloaded app the keys arrive and nothing listens. This is that missing layer, for
everything else.

The wheel is not a rotary encoder either. It's an optical sensor firing one discrete DOWN+UP
pair per notch, ~35–60 ms apart, so `AXIS_SCROLL` and `onRotaryScrollEvent` never see a
thing.

`WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` aren't AOSP keycodes — Light added them — so the
integers are Light's to change. They're resolved by label at runtime through
`KeyEvent.keyCodeFromString`, which reads the same native table the keylayout parser does,
and fall back to the raw Linux scancode, which is hardware and can't move. The fallback is
gated on the input device name so a paired Bluetooth keyboard's `r` can't dim your screen.

## Scrolling apps that never heard of the wheel

Nothing lets a normal app inject a scroll — `INJECT_EVENTS` is signature-only. Two routes
exist from out here, and only one of them is acceptable:

- **`dispatchGesture`** draws a synthetic finger-drag. It needs `canPerformGestures`, which
  notably does *not* imply reading the screen. This is what `SWIPE` uses.
- **`ACTION_SCROLL_FORWARD`** on an accessibility node is precise but moves a whole screenful
  per notch, and requires `canRetrieveWindowContent` — the service would be able to read
  everything on screen in order to scroll a list. Not worth it.

With root the better answer would be a keylayout that also emits `PAGE_UP`/`PAGE_DOWN`, and
apps would scroll for free — but this is a user build.

`SWIPE` is off by default and set per app. It is **one finger that never lifts**, not a series
of flicks: a `StrokeDescription` marked `willContinue` leaves the finger down, and
`continueStroke` moves it as the wheel turns. The first version dispatched a separate flick per
burst and felt like it — every stroke was a fresh touch-down-drag-lift, so the app saw a queue
of small flings whose momentum fought the next one.

Two things make it fiddly, and both are visible in `keys/WheelSwipe.kt`:

- **A continuation must start exactly where the last one ended**, so the finger's position is
  tracked rather than recomputed.
- **A finger cannot leave the screen.** It stays inside the middle 18–82% — away from the edges
  where a drag becomes the system back or home gesture — and when it reaches the end of that
  band it lifts, with the next notch starting a fresh stroke from the middle. That relift is
  the one visible seam, and it is unavoidable: a real thumb has the same limit, which is why
  people swipe repeatedly instead of dragging one screen-length inch.

If a real finger touches the screen mid-scroll the gesture is cancelled, and the synthetic one
gives up rather than fighting it — that is how you avoid a scroll that won't stop.

It is still a synthetic finger, and it will never be as good as an app scrolling itself. Apps
that want per-notch scrolling implement it with the `hw/` module — four files, no permissions,
and it scrolls a WebView properly because it lives inside the app that owns it.

## The lock screen, and why it comes with the dashboard

LightOS's lock screen is not a keyguard. `KeyguardManager.isKeyguardLocked` is false while it's
up, because it is a view inside LightOS's own single activity — locked or sitting on the home
dashboard, the focused window is `com.lightos/com.lightos.MainActivity` in the same task either
way. There is no class name, no separate window and no system flag that separates them, and the
only thing that would is reading the screen, which this app does not do.

So it is one switch for both: **LightOS screens**, off by default. On, a turn there is brightness
and the buttons take their bindings — the same behaviour as everywhere else, including the torch,
which is the one you want from a locked phone in the dark. Off, LightOS keeps its own handling and
this app stays out of the way.

Off by default because turning it on *replaces* behaviour that already works rather than adding
behaviour that's missing, and that's a trade worth making on purpose. One thing it can't fix
either way: a binding that opens an app is a background activity start, dropped behind a lock
until the target itself declares `showWhenLocked`, which isn't ours to declare.

## Privacy

The service declares one event type and `canRetrieveWindowContent="false"`. What it can
observe is exactly two things: key codes, and the package name of the app that came to the
front, which rides along with the event and needs no node access. Screen content is not
reachable, by declaration rather than by promise.

## Setup

Three grants, none of which LightOS has a Settings screen for. The app's first screen reports
all three and shows the line to paste for whichever is missing.

```bash
adb install -r LightControl-v1.0.x.apk

# 1. the key service. This *replaces* the enabled list, so if you also run LightVoice's
#    push-to-talk, join them with a colon instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# 2. brightness
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow

# 3. the level readout, and letting the camera button start an activity from a service
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Without (2) the wheel can't change brightness at all — a service has no window of its own, so
there's no per-app override to fall back on. Without (3) the readout silently doesn't appear
and the camera button does nothing; the brightness still works.

## Defaults, and why

The defaults matter more than the settings do, because the wheel should behave sensibly
before anyone configures anything.

| Apps | Turning the wheel | Everything else |
|---|---|---|
| `com.lightos*`, `com.thelightphone.*`, `com.lightphone.*`, the launcher, SystemUI, Camera2 | untouched | untouched |
| `com.gios.*`, `com.lightfastread`, `com.lightrss.reader` | goes to the app, which scrolls per notch | ours |
| everything else | brightness (or `SWIPE`, per app) | ours |

Light's tools are hands-off because the wheel already works there — anything intercepted
would be a feature *removed*. Apps carrying `hw/` get their turns passed through because
per-notch scrolling inside the app beats anything reachable from outside it.

Per-app overrides cycle on tap through `AUTO → BRIGHT → SWIPE → APP → OFF`, and rows left on
AUTO show what AUTO resolved to, so the table above is visible in the UI rather than folklore.

**A camera in front keeps the camera button**, both stages, whatever is bound to it. The test
is what the app declares — anything registered for `STILL_IMAGE_CAMERA` — rather than a list of
package names, so it holds for cameras that don't exist yet. Without this the key is swallowed
everywhere and its binding fires even when a camera is already open: a third-party camera's
shutter is dead, which is precisely the thing you installed it for, and "open the camera" from
inside a camera does nothing anyway. An explicit per-app rule of `OFF` still wins.

The volume keys are bindable but pass through by default. They are the one pair that already
works, and consuming them out of the box would be taking a function away to add one.

## Gotchas, in the order they'll bite

**The accessibility setting is a list, not a flag.** `settings put secure
enabled_accessibility_services` overwrites whatever was there. Enabling LightControl this way
will silently turn off LightVoice's push-to-talk unless both components are in one
colon-separated string.

**Consuming a key is the dangerous half.** Every key swallowed is a key some app doesn't get,
so the service consumes only what it actually acted on, and only where the resolved behaviour
asked for it. A bug here can't trap you: turns and clicks are inert keys in almost every app,
and the camera button is the only one that could otherwise have done something.

**The click is a modifier and a button at once.** A held `WHEEL_CLICK` produces no key
repeat, so the press is remembered on DOWN and the torch only fires on UP if no notch arrived
in between. Otherwise every brightness adjustment would end with the flashlight coming on.

**The camera button sends two scancodes**, and the order flips between presses — `FOCUS`
first sometimes, `CAMERA` first other times. Only `CAMERA` triggers anything; `FOCUS` is
swallowed alongside it so the app never sees half a press.

**The readout overlay raises window-state events of its own.** Trusting those would rewrite
"the app in front" to LightControl mid-turn and swap the mapping underneath you. Events from
this package are ignored, and the settings activity reports itself through a volatile flag
instead — same process, no IPC.

**Brightness has no fixed scale.** 255 is common; 1023, 2047 and 4095 all ship. It's derived
instead, from the platform's own two mirrors of the same value: `screen_brightness` divided by
`screen_brightness_float`.

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

## Not doing

- **Node-based scrolling.** See above. Reading the screen to scroll a list isn't a trade worth
  making.
- **Remapping the power button.** Long-press is the hardware power menu, below the framework.
- **A launcher tile per app.** The point is that the phone behaves consistently, not that
  there's more to configure.
