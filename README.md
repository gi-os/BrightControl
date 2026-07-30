# LightControl

The Light Phone III's brightness wheel and camera button, working inside apps Light didn't
write.

| Gesture | What happens |
|---|---|
| Hold the wheel in and turn | Brightness, with the level shown at the bottom of the screen |
| Click the wheel | Flashlight |
| Camera button | Opens the camera |
| Turn the wheel | Brightness in most apps — or passed through to apps that scroll with it |

Light's own tools are left strictly alone, and any app can be overridden individually.

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

## What it doesn't do: scroll other apps

It could, two ways, and both are worse than not doing it:

- **`dispatchGesture`** draws a synthetic swipe. Works anywhere, but a drag gets read as a
  tap or a fling, gestures queue at ~60 ms each so a fast turn lags, and it fights real
  touches.
- **`ACTION_SCROLL_FORWARD`** on an accessibility node is precise, but moves a whole
  screenful per notch, and requires `canRetrieveWindowContent` — the service would be able to
  read everything on screen, to scroll a list.

So turns are passed through instead, and an app that wants per-notch scrolling implements it
itself. That's the `hw/` module in the LightX apps — four files, no permissions, and it
scrolls a WebView properly because it's inside the app that owns it.

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
| everything else | brightness | ours |

Light's tools are hands-off because the wheel already works there — anything intercepted
would be a feature *removed*. Apps carrying `hw/` get their turns passed through because
per-notch scrolling inside the app beats anything reachable from outside it.

Per-app overrides cycle on tap through `AUTO → BRIGHT → APP → OFF`, and rows left on AUTO
show what AUTO resolved to, so the table above is visible in the UI rather than folklore.

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
keys/LightKeys.kt        the five keycodes, resolved by label then by scancode
keys/ControlService.kt   the filter service: gesture split, consume rules, foreground app
keys/Brightness.kt       system brightness with a derived scale
keys/Readout.kt          the level, as one reused overlay window
keys/Grants.kt           what's granted, and the volatile own-window flag
Prefs.kt                 settings, plus the table that decides untouched apps
ui/SettingsScreen.kt     grants first, then the mapping
ui/AppListScreen.kt      every launchable app, tap to cycle its rule
```

## Not doing

- **Scrolling other apps.** See above. The compromise isn't worth the permission.
- **Remapping the volume keys or power.** They already work; there's nothing to fix.
- **A launcher tile per app.** The point is that the phone behaves consistently, not that
  there's more to configure.
