<img src="docs/icon.png" alt="" width="72" align="left" />

# BrightControl

The Light Phone III hardware, working everywhere. Plus the settings LightOS never shipped a
screen for.

BrightControl started as the wheel and the camera button in sideloaded apps. It is now the
layer the phone is missing. It adds per-app color, a lock face, a volume readout, a
captive-portal browser, and a hotspot that raises itself. Under all of it, the phone grants
itself the permissions those need. No computer in the room.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightControl.png" alt="Scan to open BrightControl in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed. This opens BrightControl there, where you
can install or update it directly. If you do not have BrightMarket yet, get it and browse every
Bright app at **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

**Current version: v3.77.** See [Version history](#version-history).

## What it does

| | |
|---|---|
| **Controls** | The wheel, the camera button, the home button and the volume keys. Each one has a tap and a hold, bindable to any installed app |
| **Edge gestures** | Swipe in from either edge, short or long. Four bindings, chosen like a button's. The left edge goes back and is on out of the box, because this phone has no back button; the right edge is off until you switch it on |
| **Color** | Per-app color, on a phone with one global monochrome switch |
| **Lock screen** | A Light-style lock face with notifications, now playing, signal, battery and a photo background |
| **Volume** | The on-screen volume level LightOS ships without |
| **ADB and grants** | The phone grants itself every permission it needs, over its own wireless debugging |
| **Wi-Fi login** | A captive-portal sign-in page. **In development. It may not work** |
| **Hotspot** | Raises the hotspot when a paired iPad is near. **In development. It may not work** |
| **Diagnostics** | Shake to file a report, the last crash, and a log of what the key filter did |

Out of the box:

| Gesture | Does |
|---|---|
| Turn the wheel | Brightness. Or a per-notch scroll, or passed through, per app. On LightOS screens it stays with LightOS unless you switch that off |
| Double tap the wheel | Switches turning between brightness and scrolling, and says which |
| Tap the wheel | Flashlight |
| Hold the wheel | Nothing. Bind it to any app |
| Tap the camera button | The Light camera |
| Hold the camera button | Nothing. Bind it to any app |
| Tap the home button | Home, whichever launcher is default |
| Press the home button twice | The app switcher: the apps you have been in, newest first |
| Hold the home button | The LightOS dashboard, by name. Rebind it to anything |
| Drag in from the left edge | Back. A long drag opens the app switcher |
| Drag in from the right edge | Nothing, until you switch it on. Then the app switcher, or back on a long drag |
| Wake the phone | The stock lock screen. Or a Light face over it, once you turn it on |
| Volume keys, tap or hold | Passed through, but bindable. The level appears on screen |

## What this is and why

Every wheel notch, camera-button press and home-button press on the LPIII arrives as an
ordinary `KeyEvent`. It goes to whichever app has focus. Light patched
`/system/usr/keylayout/Generic.kl`, the keylayout every input device on the phone loads. So the
brightness ramp and flashlight in the Light tools are only app-layer code inside those tools:

```
key 19    WHEEL_CCW      # wheel up       (Pixart pat9126ja, was R)
key 20    WHEEL_CW       # wheel down     (Pixart pat9126ja, was T)
key 66    WHEEL_CLICK    # wheel press    (gpio-keys, was F8)
key 80    FOCUS          # camera stage 1 (gpio-keys, was NUMPAD_2)
key 27    CAMERA         # camera stage 2 (gpio-keys, was RIGHT_BRACKET)
```

Nothing intercepts these keys in `PhoneWindowManager`. In a sideloaded app the keys arrive and
nothing listens. BrightControl is that missing layer for everything else.

It is an `AccessibilityService` with `flagRequestFilterKeyEvents`. That flag is the only way an
app can see a key without window focus, because `INJECT_EVENTS` is signature-only.
[LightVoice](https://github.com/gi-os/LightVoice) uses the same mechanism for push-to-talk. The
service declares one event type, `typeWindowStateChanged`, to track the foreground package. It
also declares `canRetrieveWindowContent="false"`. Screen content stays unreachable by
declaration, not by promise.

The wheel is not a rotary encoder. It is an optical sensor, a `Pixart pat9126ja`. It fires one
discrete DOWN and UP pair per notch, about 35 to 60 ms apart. So `AXIS_SCROLL` and
`onRotaryScrollEvent` never see anything, and this has to run on raw key events.

`WHEEL_CCW`, `WHEEL_CW` and `WHEEL_CLICK` are not AOSP keycodes. The app resolves them by label
at runtime through `KeyEvent.keyCodeFromString`, then falls back to the raw Linux scancode. It
gates that on the input device name, so the `r` key of a paired Bluetooth keyboard cannot dim
the screen.

Every button carries a tap and a hold. You bind them separately, and either one can open any
installed app. The app leaves the Light tools alone by default, and you can override any app on
its own. Time tells a tap and a hold apart, not key repeat, because a held key on this phone
never repeats.

**The release decides. Nothing fires mid-press.** That order matters more than it sounds. A
hold that fires while the button is still down brings an app to the front during the press.
The rest of the press then belongs to a foreground that changed underneath it. One press makes
one decision, dispatched once, after the key is already accounted for. This costs the feel of a
hold going off in your hand. It buys a button that does one thing.

## Quick start

Install the app, open it, and follow the intro screen. **You do not need a computer.**

That sentence is the whole of v3.x. LightOS grants nothing this app needs through any on-screen
setting. The accessibility service, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`,
`WRITE_SECURE_SETTINGS` and the notification listener all used to mean plugging into a
computer. A reinstall then dropped the appops and the secure-settings grants, so it meant
plugging in again. That was the most common way this app arrived on a phone looking broken.

**ADB and grants** removes the computer. The Android wireless-debugging daemon listens on a TCP
port on the device. The app connects to it over loopback with a client certificate. It then
runs `pm grant` and `appops set` against itself.

Pairing happens once, from a floating panel that sits on top of the Settings pairing dialog.
The dialog keeps its session alive only while it stays on screen. An overlay is therefore the
only way to read the six digits and use them without killing the thing that issued them. After
that it is discovery, not pairing. The daemon re-advertises `_adb-tls-connect._tcp` on a fresh
port after every boot, and the app finds it again by itself.

You can still do this from a computer. **Setup and guide** lists every grant with its exact
command. The ADB screen then reads each grant back off the phone rather than trusting what the
command printed. The adb `shell:` service merges stdout and stderr and carries no exit status,
so "printed nothing" does not mean "worked".

```bash
# the one grant that cannot be self-applied, because everything else runs through it
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1
```

That setting is a list, not a flag. Writing it replaces whatever was there. If you also run the
LightVoice push-to-talk service, colon-join the two components instead.

## Controls

### Defaults, and why

| Apps | Turning the wheel | Everything else |
|---|---|---|
| `com.lightos*`, `com.thelightphone.*`, `com.lightphone.*`, the launcher, SystemUI, Camera2 | untouched | untouched |
| `com.gios.brightrecorder` | untouched | untouched |
| `com.gios.*`, `com.lightfastread`, `com.lightrss.reader` | goes to the app, which scrolls per notch | ours |
| everything else | brightness, or `SWIPE` per app | ours |

The wheel mode is global and outranks the scroll-aware row of that table. In brightness mode a
turn changes brightness in every app, including the apps that carry `hw/`. That is the reason
the mode lives here and not in the apps. They never need to know the mode exists, and switching
it does not mean shipping sixteen releases. In scroll mode those apps get their turns back and
scroll per notch, which still beats any synthetic finger.

The app leaves the Light tools alone because the wheel already works there. Anything it
intercepted would be a feature removed. Apps that carry a `hw/` scroll module get their turns
passed through, because per-notch scrolling inside the app beats anything reachable from
outside it.

BrightRecorder gets the stronger treatment because its wheel press is a control too. It is a
tape recorder, and pressing the wheel plays and stops it. Passing turns through while keeping
our buttons is not enough there. The click default is the torch, so the press would light the
flashlight and get consumed before the app ever saw it. The app could do nothing about that
from its side.

So BrightRecorder is hands-off outright. The trade is that the torch and the camera key do
nothing of ours while it sits in front. The list is `ownsWheelPrefixes`, and the code consults
it before the scroll-aware one, because `com.gios.brightrecorder` sits inside `com.gios.` and
that rule would otherwise claim it.

Per-app overrides cycle on tap through `AUTO`, `BRIGHT`, `SWIPE`, `APP` and `OFF`. Rows left on
`AUTO` show what it resolved to, so the table above stays visible in the UI rather than
becoming folklore.

### The app switcher

**Press home twice, quickly, and the apps you have been in are listed newest first.** The wheel
moves the selection, a wheel click opens it, home closes it, and it closes itself after six idle
seconds. LightOS ships no recents screen, so this is the only way back to what you were doing
two minutes ago that is not finding the app again.

**The first press is never held back.** Reading a double press the usual way means sitting on
the first one until its partner could have arrived, which is a third of a second added to every
press of the key this phone is used with most. Home fires the moment you let go, exactly as it
always did, and the second press draws the list over whatever the first one landed on.

The list is a **window at layer 31**, the same as the lock face, not an activity. An activity
would push itself onto the recents order it exists to display, so the switcher would always be
the most recent thing you used. `performGlobalAction(GLOBAL_ACTION_RECENTS)` is not used: this
phone has no recents screen for it to open, so it returns true and shows nothing.

The order comes from the window-state events the service already receives — `getRecentTasks` is
privileged and `UsageStatsManager` needs a grant LightOS has no screen for. So the list starts
empty at boot, is as long as the phone has been awake, is never written to disk, and leaves out
LightOS's own shell, which one press of home already reaches.

**Each row carries the app's own icon, inline, ahead of its name.** A switcher is passed through
rather than looked at, and the icon is the part of an app you already know by shape. It is drawn
exactly one line tall, tied to the type beside it, so a row with an icon is the same height as one
without and the same number of apps still fit — an icon a few pixels taller pushes the app furthest
back below a fold this list deliberately cannot be scrolled past. The icon dims with the name it
belongs to, because brightness is the only thing this screen has to say *here*.

**Luma is listed as Home, with a drawn house instead of its own icon.** Every other row is somewhere
you were; the launcher is how you leave wherever you were, and a name and an app icon like the rest
of the list makes the one row that is not an app look like one. The house is a thin outline among
solid squares, which is the distinction doing the work. Matched by package (`app.luma`) rather than
by whichever app holds the HOME role — on this phone that is always LightOS, which has to keep the
role or it crash-loops, so the role says nothing about the launcher a person actually uses. Off
under **Buttons → Home button → Luma is Home**, where the row only appears if Luma is installed. The
switcher only: the bindings picker still names it Luma, because a picker that renames its own
options is a picker you cannot search.

Switch it off under **Buttons → Home button → Double press**. A right-edge swipe opens the same
window, if you would rather not spend a button on it. See **Edge gestures**.

**The double press works while visiting LightOS too, and for a while it did not.** Pointing the home
*tap* at LightOS makes the key consumable, so the binding succeeds, and succeeding starts a visit:
the state where home belongs to LightOS so you can walk through its menu, ended by pressing home
twice. The visit then claimed the double press for its own exit and never asked the switcher, so
setting the tap to LightOS looked like it switched the switcher off. Two gestures spelled the same
way, and the more specific one lost. The double press now opens the list, and ends the visit either
way, because a window at layer 31 is in front and there is nothing left to visit.

**A way out to the system's own screens, at the bottom of the list.** SYSTEM SWITCHER asks the
platform for its recents with `performGlobalAction(GLOBAL_ACTION_RECENTS)` — which is not what the
home button does, because that call reports the action was *injected* rather than that anything
appeared, and on this phone nothing does. It is a button rather than the gesture because "this
firmware has no recents screen" is a conclusion from one phone, and a button can be held to its
answer: the switcher goes down, the system is given 800 ms, and if no package came to the front the
list comes back saying nothing came up. A dead button that admits it costs a tap. A missing one
costs the feature.

**Hold a row — with a thumb or by holding the wheel click — and Settings' own App info page for
that app opens.** Force stop, Uninstall, storage, permissions. That hold used to run `am force-stop`
over this app's own adb shell and fall back to `killBackgroundProcesses` where there was no shell,
which meant one gesture with two outcomes and a message saying which one you got. AOSP's Force stop
button needs no shell, no pairing and no permission, and is the real thing every time. A gesture
that always does what it says beats one that sometimes does more, so the adb path and the
`KILL_BACKGROUND_PROCESSES` permission behind it are both gone.

### The home button

**Tap goes home. Hold opens the LightOS dashboard by name**, `com.lightos/.MainActivity`,
rather than `CATEGORY_HOME`. A sideloaded LPIII loses the Light home screen: install a launcher
that can see your APKs, make it the default, and the dashboard becomes unreachable. You can
rebind both gestures to any installed app.

This is the one button where the mechanism is worth understanding, because it is the one where
the app can make the phone worse. Timing a hold means swallowing the DOWN and starting a clock.
The app cannot hand a consumed key back once it knows the press was a tap. So from the moment
you bind the hold, **BrightControl is what makes the home button work.**

The app refuses that promise in five situations. Each one falls through to the same place:

| Situation | Why |
|---|---|
| The takeover is off | By hand, or because it disarmed itself |
| Screen off, or the phone locked | That press is a wake or an unlock, not yours. Android drops a background activity start behind a keyguard anyway |
| LightOS in front | Its dashboard and lock screen are one activity. Home already goes there, so swallowing the key can only lose. This holds even with **LightOS screens** on |
| A clock in front, or something ringing | The same rule every key follows. See [Failing safe](#failing-safe) |
| The hold needs to start an activity and the overlay appop is missing | Android 14 drops the start in silence. The press would get consumed and nothing would happen |

Two rules keep one press to one action. The same bug taught both of them. Holding home brought
the LightOS dashboard over and then carried on into its menu.

**The release decides. Nothing fires mid-press.** The hold used to fire on a timer while the
button was still down. That put an activity in front of the user in the middle of their own
press.

**A press already taken is a press owned to the end.** Every check above asks about the app in
front, and the app in front changes because of what the service just did. The hold fires, the
dashboard comes over, and the release arrives to different answers than the press did.
Re-deciding at that point is how a consumed DOWN grew an unconsumed UP. LightOS got a lone home
release, read it as a home press of its own, and walked on into the menu.

So only a fresh DOWN consults the rules. Everything after it belongs to the press. Hold once
and the dashboard comes over. Only the next hold reaches the LightOS menu, because that is a
fresh press with LightOS now in front.

The fallback is "shadow" mode, and it consumes nothing. LightOS sees the entire press. Long
presses behave exactly as they do with this app uninstalled, and the tap binding fires on top
afterwards if the press was short. Shadow mode cannot offer a hold. By the time you know a
press was long, the system already delivered it.

**With one exception, and it is the flicker.** "Firing home twice over is invisible" is true of
every window except the one shadow mode most often runs over. LightOS does not read a home press
as "start the home activity". It reads it as its own navigation, to the idle face or on into the
toolbox, so a shadowed press produced LightOS's answer *and* a `CATEGORY_HOME` intent to whichever
launcher is default. Two destinations for one press, racing, and the phone flickered between a home
screen and the toolbox.

It only happened right after an unlock, which is the tell rather than the cause: that is exactly the
window where the front app is still LightOS, because LightOS holds the HOME role and comes forward
the instant the keyguard goes. A second later the front app has settled, nothing is refused, the key
is consumed properly, and there is one destination again.

So a plain **Home** tap is not fired in shadow while LightOS is in front. LightOS has the press and
home is what it does with it. Every other action still fires, because a tap pointed at an app or at
Resume is a destination LightOS was never going to reach. A tap pointed at LightOS is not shadowed at
all, because it picks a destination.

**Home is an activity start, not a synthetic key press.** This cost a release to learn.
`performGlobalAction(GLOBAL_ACTION_HOME)` looks like the tidy accessibility-native answer. It
needs no permission and starts nothing in the background. But it does not start the home
activity at all. AOSP implements it as `sendDownAndUpKeyEvents(KEYCODE_HOME)`, an injected key.

An injected home key hands the press to whatever already has focus. LightOS reads a home press
as "back to the idle face". So on LightOS screens, v1.0.21 flashed the dashboard and bounced to
the lock screen, and never reached the default launcher. A `CATEGORY_HOME` intent asks nobody's
opinion. The global action stays only as the fallback for a phone with no resolvable home
activity or no overlay appop.

The other half of that lesson: **the app refuses synthetic keys at the door.** All five LPIII
controls are physical. Each has a named input device and a nonzero Linux scancode. An injected
key reports `VIRTUAL_KEYBOARD` as its device and scancode 0. `LightKeys` checks that first. A
home binding that can see its own injected home press feeds itself.
`FLAG_FROM_SYSTEM` is not the test, since real hardware keys carry it too.

**The takeover also disarms itself.** Two home dispatches in a row that report failure switch
it off permanently. The app hands the key back to the system and says so on the front screen,
with a `RETRY` next to it. Know how much that can catch, though. Android drops a blocked
background start silently, and the global action returns true for "injected" rather than for
"went home". So this is the last guard, not the first. The refusals above are what keep the key
safe.

### Back to where you were

This is a home-button action, and it stays off until you bind it. Set **Home button opens** to
*Back to where you were*, then tick the apps that qualify in **Resume apps**. Sleep the phone
inside one of them and the next home press brings it back. The press after that goes home, and
so does every press once you open something else yourself.

### Edge gestures

LightOS has no navigation bar. Its settings hold a gesture-navigation switch, and that switch reaches
Light's own tools. An app you sideloaded gets nothing from it. An app that pushes a screen and draws
no arrow of its own is therefore a dead end until you press home, and the recents list is reachable
only by a double press of a physical button.

Two strips, 14dp wide, one down each edge of the screen. Each edge carries **two bindings**: one for
a short drag inwards and one for a long one. All four are ordinary actions, picked from the same
screen the wheel click and the camera button use, so an edge can open an app, go home, reach Light's
dashboard or do nothing at all.

Out of the box the two edges mirror each other, which needs no opinion about which edge is which:

| Edge | Short drag | Long drag |
|---|---|---|
| Left | Back | The app switcher |
| Right | The app switcher | Back |

**Crossing a threshold arms the gesture. The lift commits it.** A drag that comes back under a
threshold drops to the stage below, so you can always change your mind about a stroke you have
started. That property is what makes a second stage work at all: passing the short threshold on the
way to the long one is unavoidable, so a gesture that fired on crossing would perform the short
binding every time and then perform the long one as well.

`Go back` and `App switcher` are actions now rather than behaviour these strips own privately, so the
buttons can be bound to them too. Back is the only action on this phone with no hardware to reach it.

A small box follows your thumb while the drag runs. It is an outline while the stroke is short, and it
turns white and names whichever binding a release would perform. The box is not decoration. An armed
state that nothing on screen reports is a gesture you cannot aim.

The box measures the whole gesture, so it keeps growing after the short binding arms rather than
sitting full for the second half of the stroke. There used to be a tick across it marking where the
long binding took over; it is gone, because a short grey line inside an outlined box at this size
reads as a smudge rather than as a scale mark. The word and the glyph still change at the long
threshold, which is the announcement people were reading anyway. An edge whose long binding is set to
nothing has one gesture, and its box measures the short one.

**The strips stop short of the top of the screen.** Nearly every screen on this phone puts a back
arrow in the top-left corner — the corner the left strip runs through — so reaching for the arrow put
a thumb on the strip, and a thumb that slid inwards on its way down was a swipe rather than a tap.
Both went back, which is why it read as harmless; the same slip on a screen with no arrow, or on the
right edge, performed a gesture nobody asked for. The top 92 dp of both edges now belong to the app.
**Leave the top alone** sets it, and 0 is the old behaviour.

The glyph is a chevron for back, two overlapping cards for the switcher, and a plain filled square
for everything else. Three, and no more: the two gestures anybody will actually bind to an edge have
a shape people already know, and an icon that guessed wrong would be worse than a neutral one on a
screen read at arm's length mid-drag.

**These are the only features here that take a touch, and that must be described exactly.** No API
lets an app watch a touch without receiving it. Gesture detection through the accessibility API
requires touch exploration, which changes how you drive the whole phone. `dispatchGesture` sends
touches and never receives them. What remains is an overlay window, and an overlay window that
receives a touch has taken it. A touch cannot be given back after the stroke starts.

So a strip receives every stroke that starts on it, including the ones that turn out to be a scroll.
`FLAG_NOT_TOUCH_MODAL` keeps every touch outside the strip going where it always went, and the window
is never focusable, so this can never cost a key. Because the cost is real:

- **The left edge is on out of the box. The right edge is not.** The left one repairs an absence:
  there is no back button on this phone at all, and an app that pushes a screen and draws no arrow of
  its own is a dead end. The right one adds convenience to something that already works, since a
  double press of home opens the same window. An absence is worth filling; convenience is worth
  opting into. Either edge is off in one tap on its own screen, and both are gone with the
  EVERYTHING OFF switch.
- The **width is a setting**: 10, 14, 20 or 28dp. That number is the whole cost. It is one number for
  both edges, because nobody wants their left edge to be a different size from their right.
- **A long swipe costs nothing more than a short one.** The strip is the same width either way. Only
  how far the finger travels afterwards differs, and by then the touch is already ours.
- **Any app can be left out.** One list for both edges: an app that puts its own controls at the
  screen edge usually does it at both.
- **Light's own software never gets a strip.** It has these gestures already, on the same edges.
- **The lock face and the app switcher never get one either.** Both are full-screen windows above a
  strip, and both use the edges for their own swipes. That the switcher is one of them is also what
  stops the right edge re-opening a list that is already up.

A stroke that moves further up or down than 34dp, and further that way than it moves across, is read
as a scroll and cancelled for the rest of the stroke. Nothing later in that stroke can revive it. A
long flick that drifts sideways at the end is exactly the stroke this rule exists for.

Distance along the screen is measured in the stroke's own direction, so one class serves both edges.
A left-edge stroke that travels left is exactly as meaningless as a right-edge stroke that travels
right. Without that sign, both strips would arm on a stroke heading off the screen they live on.

Two guards on the long threshold, and neither is a setting anybody should be able to produce. A
threshold past four fifths of the screen is pulled back, because one you cannot reach is a gesture
nobody can complete. One at or below the short threshold is pushed above it, because that makes the
short binding unreachable: every stroke that armed it would already have armed the long one.

`GLOBAL_ACTION_BACK` is a request rather than a result. What an app does with a back belongs to the
app. Many apps on their first screen accept the action and do nothing with it, and from outside the
app that looks identical to the gesture working.

The right-edge strip is what opens the switcher, and the switcher going up is what takes the strip
down. That refresh is **posted** rather than called: a synchronous one reaches `removeView` on the
very view whose touch listener is still delivering the stroke that asked for the window.

### Scrolling apps that never heard of the wheel

Nothing lets a normal app inject a scroll, because `INJECT_EVENTS` is signature-only. Two
routes exist from out here and only one is acceptable.

`dispatchGesture` draws a synthetic finger-drag and needs only `canPerformGestures`, which does
not imply reading the screen. `SWIPE` uses this. The other route is `ACTION_SCROLL_FORWARD` on
an accessibility node. That is precise, but it moves a whole screenful per notch and requires
`canRetrieveWindowContent`. The service could then read everything on screen, which is not
worth a scroll.

`SWIPE` is off by default and you set it per app. It is one finger that never lifts, rather
than a series of flicks. A `StrokeDescription` marked `willContinue` leaves the finger down,
and `continueStroke` moves it as the wheel turns. The finger travels 64dp a notch, coalesced
while a stroke is in flight, and capped at 0.6 screen so it does not read as a fling.

A continuation has to start exactly where the last one ended. The finger stays inside the
middle 18 to 82 percent of the screen, away from the edges where a drag becomes the system back
or home gesture. At the end of that band it relifts and starts a fresh stroke from the middle.
That relift is the one visible seam and nothing can remove it, because a real thumb has the
same limit. A real finger touching the screen mid-scroll cancels the synthetic one rather than
fighting it.

A synthetic finger is never as good as an app that scrolls itself. Apps that want per-notch
scrolling implement it with the `hw/` module instead. That is four files and no permissions,
and it scrolls a `WebView` properly, because it lives inside the app that owns it.

### Camera-in-front

Anything registered for `STILL_IMAGE_CAMERA` gets both camera-button stages untouched, memoized
per package. So the shutter of a third-party camera is never dead, and "open the camera" never
fires again from inside a camera that is already open. An explicit per-app rule of `OFF` still
wins over this.

The volume keys are bindable but pass through by default. They are the one pair that already
works, so consuming them out of the box would take a function away to add one.

## The screen

### Color, per app

LightOS forces the whole system to monochrome through the accessibility **daltonizer**. That is
two secure settings, `ENABLED` at 0 or 1 and `MODE`, where 0 means monochromacy. BrightControl
drives those two off whichever app sits in front. A package with a `Color` rule turns the
daltonizer off while it is on screen. `Mono` turns it on. `Default` restores the baseline the
phone had before any rule fired.

**The code writes state, never a transition.** `applyFor` says what the daltonizer should be
right now for the given package, and makes it so, idempotently. That is the whole design. A
transition-based version says "turn color on when entering, restore when leaving". Its restore
can fire while holding nothing, and nothing can undo that. A state-based one corrects itself on
the next event, whatever happened in between.

Rules cycle `AUTO`, `COLOR`, `MONO` and `PASS`. **`PASS` writes nothing at all.** It exists for
apps that hold `WRITE_SECURE_SETTINGS` and set their own color themselves.
[Roll](https://github.com/gi-os/Roll) and [BrightChat](https://github.com/gi-os/BrightChat) both
do. `AUTO` was never "no opinion". It resolves through a built-in table, and for those two apps
the honest answer is to leave them to it.

This needs `WRITE_SECURE_SETTINGS`, which the ADB screen can grant. **Color, then what
happened** logs every write and its read-back. A rule that something else overwrote therefore
names the app that did it.

### The lock screen

The LightOS lock screen is not a keyguard window. It is a view inside the single LightOS
activity. Locked or on the dashboard, the focused window is
`com.lightos/com.lightos.MainActivity` either way, and no observable event marks the difference.
`ControlService` calls `KeyguardManager.isKeyguardLocked()` per key event instead.

The optional Light face is **a window owned by the accessibility service, not an activity**, and
that distinction is the entire feature. Versions 2.5 and 2.6 drew it as an activity marked
`showWhenLocked`, and the fingerprint reader stopped working.

That flag marks the keyguard occluded. While occluded, AOSP arms its fingerprint listener only
for under-display sensors, a dreaming device, or a bouncer already up. The LPIII reader sits in
the power button, so the face switched the sensor off by existing. A window at
`TYPE_ACCESSIBILITY_OVERLAY`, **layer 31, above the keyguard layer of 17**, occludes nothing.
The keyguard shows, stays visible and keeps listening exactly as always, and the power button
unlocks the phone untouched.

The face draws the clock and date, notifications, what is playing, four signal bars and a
battery outline. Both
of those last two are glyphs rather than text. You read "T-MOBILE" and "85%". You glance at bars
and a fill. Type comes from the LightOS scale ported from `light-sdk`: named sizes scaled by
screen height, spacing in 27-wide grid units, and no hardcoded sp or dp anywhere on the screen.

**Swipe a row left to clear it.** A notification goes with a real `cancelNotification`, so it
is gone from the shade and from Glance too rather than only from here — a face that kept its own
private list of things you had waved away would disagree with the rest of the phone and hand the
same message back at the next unlock. The player's card is the exception: swiping it away puts the
card away and does not touch the music, and it comes back when the session has something new to
say — a different track, or play pressed again in the app.

**Permanent notifications are dropped, and one flag was missing.** The filter tested
`FLAG_ONGOING_EVENT` and `FLAG_FOREGROUND_SERVICE`. There is a third flag. `FLAG_NO_CLEAR` says
nothing about progress. It says the notification cannot be dismissed, and it is the flag LightOS puts
on its own permanent notice. That notice set neither of the two flags being tested, so it passed
every check, drew on the face at full importance, and then refused to go: the platform declines
`cancelNotification` on an un-clearable notification by not removing it, and the rebuild brought the
row back. On the phone that reads as a swipe that does nothing.

`FLAG_NO_CLEAR` now counts as permanent, together with `CATEGORY_SERVICE`. **Permanent
notifications** is a switch on the Lock screen page, off by default, for the case where the permanent
notification is the point: a recording, a download, a route. And a swipe always removes the row now.
Where the real cancel is refused, the row is held out of the list until the next unlock and then
forgotten. Nothing is stored, because a face with its own permanent record of what you waved away is
a face that disagrees with the shade.

**Apps never shown** hides a source by name. The list holds every app that has posted anything,
taken from the raw shade before any of the face's own rules have filtered it, so an app whose
notification is already dropped is still there to be hidden for good. Hiding a source changes the
lock face and nothing else. Nothing is cancelled, nothing about the notification is stored, and the
shade, Glance and the app itself are untouched.

**And the shade is clamped to the room it has.** The list used to draw four rows into whatever
space was left under the clock and draw them whether they fit or not, so a busy morning showed two
notifications and the top half of a third, with nothing to scroll — this window holds no focus, and
every drag on it already means something else. It now measures against the space it is actually
given, draws only whole rows, and says how many are missing on the `+N MORE` line. Clearing the top
ones brings the rest up.

Three gestures on the face, and each one has to be impossible to do by accident, because this
window covers the whole panel and a pocket presses the whole panel: **up** for the keypad,
**left** on a row to clear it, **press and hold** to go in once the phone is unlocked. The axis is
locked at the first movement past the touch slop and never revisited, so a lazy diagonal cannot
take the face away while you are wiping a row.

**A ringing call gets a card on the face.** This is a fix, not an addition. The face is a window
at layer 31, so it paints over the dialer's incoming-call screen the same way it once painted over
the camera. A call that arrived while the phone was locked rang behind a clock. The card shows who
is calling and carries ANSWER and DECLINE.

Answering presses the dialer's own notification buttons, which needs no grant beyond the
notification listener the face already uses. A dialer whose buttons cannot be identified by their
labels falls back to `TelecomManager`, which needs `ANSWER_PHONE_CALLS`. The card draws either way.

**Who is calling comes from telephony, because this phone's dialer posts no notification.**
LightOS's dialer is a system app showing its own incoming-call activity, so there is nothing in the
shade for a notification listener to read — which is why the card said "Incoming call" for every
call it ever drew. The number arrives on the `PHONE_STATE` broadcast and `PhoneLookup` turns it into
the contact name, which needs `READ_CALL_LOG` and `READ_CONTACTS`; both are in the ADB screen's
one-tap run, and without them the card falls back to the old wording rather than breaking. The
number is drawn under the name.

Where a dialer *does* post a notification, that is still read first, and read properly:
**out of the `Person`, not the title.** A `CallStyle`
notification leaves the title empty and lets SystemUI build it at draw time, which is a step a
listener never sees, so the card read the one field the dialer had not filled in. It now takes the
first real answer out of the call `Person`, the people list, the title, the big title, the
conversation title, the ticker and the number on the `Person` URI, in that order. Placeholders lose
to anything real behind them; a genuinely anonymous call still reads as the dialer described it.
Whether a notification is a call at all is four tests, not one: the category, the `CallStyle`
template, the `android.callType` extra, or the default dialer carrying a button that answers.

**ANSWER takes the face down on the press, and raises the dialer's own screen as it goes.** Waiting
for the audio mode to move and come back round through the poll is a second of clock and a dead
button after a thumb has landed. And uncovering the screen is not the same as raising it — the
dialer's full-screen intent fired while a window at layer 31 was over the top, so the activity
underneath may have been stopped or replaced by the keyguard, and the call would connect with
nothing on screen to mute or hang it up.

The full-screen intent first, then the content intent, then `TelecomManager.showInCallScreen` —
which is asked and **not believed**, because it returns nothing and reports nothing and is only a
request passed to the dialer. When nothing verifiable fired, the face goes and fetches the dialer
itself: resuming its task puts its call screen in front, since during a call that task's top
activity is the call. Once per call, latched. The diagnostics log names the route that ran, and
prints what the phone said about the call when none did.

Once the call is answered, **the face stands down for the length of the call**. LightOS shows mute,
speaker, the keypad and hang up on its own in-call screen, and all of it sits under layer 31. The
face comes back by itself when the call ends. Switch the card off and the face stands down for the
whole call instead. It is never allowed to sit on top of a ringing phone.

**The controls follow what is playing.** The row reads three answers off the media session and
draws a different set for each: music gets previous, play/pause and next; a podcast gets back 15,
play/pause and forward 15; a live stream gets play/pause and stop. It is read off what the session
declares it can do — a queue, a position, a step, a stop, a length — and never off the package name,
because one app is a music player and a podcast player in the same process and next year's player
has a name this app has not heard of. Fast-forward with no queue is spoken word. No length with no
queue is a stream. Seekable and over twenty minutes is spoken word. Anything else gets skip
buttons, which are wrong in the fewest ways when the answer is unknown.

The fifteen seconds are a real `seekTo` from an extrapolated position, not the platform's
`fastForward`, whose step is whatever each player decided. Stop takes the row down with it, which is
what stop on a station means.

The background is a photo with a filter stack you assemble yourself. It offers ordered Bayer
dither to halftone at a chosen cell size, black and white, opacity, corner blur and corner fade.
You can reorder and repeat them, with a live preview at the panel aspect ratio.

It walks DCIM and Pictures directly rather than using the system picker, because **nothing on
LightOS keeps MediaStore current**. No media provider does the scanning a normal Android build
does, so no picker offers a photo taken minutes ago. A directory listing cannot go stale.

**On the lock screen** is off by default and takes the buttons only. Turns still go to LightOS
untouched, since LightOS already puts brightness there on both screens. The first version took
turns too and made LightOS unstable.

**LightOS brightness** is on by default and is the one lever over those turns. It has only an
off position. On, LightOS gets the notch and dims the screen. Off, the app swallows the notch
and nothing acts on it, not even the BrightControl brightness. That is why it can ship. What
made LightOS unstable was two owners writing the same system brightness value a notch apart,
and dropping a key has no second writer in it.

### Volume

LightOS ships no volume UI at all. The keys work and media responds, but nothing on screen says
so. The only way to find a level is to press until it is too loud and come back down. On the
ring and alarm streams there is no feedback whatsoever. A silent phone and a phone at one notch
look identical until something arrives.

So there is a HUD at the top of the screen, and it is **only a HUD. It reports. It never
adjusts.** Nothing here consumes a key, which is what makes it safe to put on the volume keys.
It listens to `android.media.VOLUME_CHANGED_ACTION`, so it also catches the slider of a media
app, a headset button, and a Bluetooth device that turns itself down on connect.

The HUD stays off LightOS screens, which have volume controls of their own. **A call is the
exception.** The dialer is in front for the whole call and has no volume control at all, so during
a call the strip is the only feedback the phone gives.

**A call on the speaker starts at maximum.** LightOS has no volume UI, so the call stream sits
wherever it was last left and nothing on screen reports it. The level is set once, on the move to
the speaker route, so turning it down mid-call keeps it down. Android holds one index per output
device, so this moves the speaker and never the earpiece.

Maximum is the ceiling for any app, and it is worth knowing why. Call audio goes from the modem to
the codec to the speaker. It never passes through the app mixer, and its gain comes from one number
inside the audio HAL, which is the `STREAM_VOICE_CALL` index. `LoudnessEnhancer`, an effect on
session 0, or a dialer of our own would each control the route and the screen. None of them adds a
decibel.

## System

### Wi-Fi login. In development

> **This is unfinished and may not work.** It needs a system WebView to draw the login page in,
> and nobody has confirmed that LightOS ships one. On a phone without one the page will not
> render, and this screen can do nothing about it. Treat it as an experiment, not a feature.

A hotel or cafe network answers every request with its own login page until you submit it. On a
phone with no browser there is nothing to submit it with, so the network connects and then never
validates.

`PortalActivity` is that missing piece. It is a WebView **pinned to the captive network** with
`bindProcessToNetwork`, because an unvalidated Wi-Fi network is exactly what Android routes
around by default. It opens from the settings screen or from the system
`ACTION_CAPTIVE_PORTAL_SIGN_IN` flow, and closes itself once real traffic flows.

It does not join networks. Picking a network and typing its password is still LightOS Settings.
The state row reads the same capability bits the platform sets, `NET_CAPABILITY_CAPTIVE_PORTAL`
when a portal announced itself and `VALIDATED` once traffic flows. So it agrees with what the
system concluded rather than running a probe of its own.

### Hotspot. In development

> **This is unfinished and may not work.** It depends on Bluetooth pairing having exchanged an
> identity key, on the shell surviving a reboot, and on the iPad choosing to join. Any of those
> can leave the hotspot never coming up, or coming up and getting ignored.

This came from [BrightHotspot](https://github.com/gi-os/BrightHotspot), a good app with one
fatal setup step. Raising an access point is `signature|privileged` and needs a shell UID.
BrightHotspot borrowed one from Shizuku, and the Shizuku route in is the wireless-debugging
pairing flow that Android tears down on every reboot. A setup step you repeat forever is not a
setup step. It is a fault. This app has held a shell the whole time, by a route that reconnects
itself over mDNS with no re-pairing, so the same feature has nothing to redo.

It watches for a paired device advertising over BLE. When that device is near and the phone is
not on a network you marked as trusted, it guesses that the device wants a connection and raises
the hotspot. The device answers the guess by joining or not. A join confirms it. Three minutes
of silence refutes it and earns a backoff, so a cafe with good Wi-Fi does not make the phone
flap. All of that lives in `hotspot/TriggerEngine.kt`, which has no Android in it and a test
beside it.

You set three things up once. **The order matters. Step 2 is the one everybody skips, and it is
the one that makes the rest automatic.**

1. **Pair the two, from the phone.** iOS lists only the accessories it knows how to be. An
   Android phone never appears in the iPad Bluetooth list, however long you stare at it. Open
   Settings, then Bluetooth on the iPad, and leave it on that screen. That is what makes the
   iPad advertise. Then scan from the LightOS Bluetooth settings and accept the code on both
   sides. This is not a formality. An iPad advertises under an address that rotates every few
   minutes so nothing can follow it. Only the identity key the two exchange during pairing
   turns that back into "this is my iPad". **Hotspot, then CAN IT HEAR THE
   IPAD?** tells you whether it worked.
2. **Join the hotspot by hand, once.** Turn the phone hotspot on from LightOS. Join it from the
   iPad the ordinary way. Check that *Auto-Join* is on for it. iOS joins a network without
   asking only when it already knows that network. This one manual join buys every automatic one
   afterwards. Skip it and the hotspot will come up faithfully every time while the iPad sits
   there ignoring it.
3. **Tell BrightControl.** Set the network name and password to match exactly what the iPad
   joined in step 2. Pick the device to watch for. Add your home Wi-Fi under *leave it alone
   here*.

When it does not work, start here:

- **The hotspot comes up and the iPad ignores it.** You skipped step 2, or the name and password
  do not match, or Auto-Join is off.
- **The hotspot never comes up.** Try *START HOTSPOT NOW*, which reports what the shell said. No
  adb connection is the usual answer.
- **The iPad joins but has no internet.** The phone has nothing to share. Either it is not on
  cellular, or the plan does not allow tethering.
- **"Hearing other devices, but none of them are yours."** The pairing did not exchange the
  identity key. Presence triggering cannot work as built.

### ADB and grants

See [Quick start](#quick-start). Two details make this safe rather than merely clever.

**The pairing reader cannot see anything else.** Reading the six digits off the Settings dialog
runs in a separate accessibility service, declared with `packageNames="com.android.settings"`.
It is structurally incapable of seeing the screen of another app. `ControlService` keeps its
`canRetrieveWindowContent="false"` promise untouched.

**The app never executes anything that arrives from another app.** BrightMarket can send a list
of grants that an app README asks for. A user then does not have to find a computer for the app
of another developer. The app parses that string. It does not run it. It matches each line against
the small set of things an app is allowed to need, then rebuilds the command from the parsed
pieces. It refuses a request naming a package other than the sender, and it refuses loudly,
because that is the shape an attack takes. The user sees the exact commands, and nothing runs
until they agree.

### Diagnostics

Shake the phone and a report sheet comes up. It files a GitHub issue against the private tracker
with the build, the firmware, free space, heap, and the last crash. The gesture counts reversals
rather than force. That is what separates a deliberate rattle from a phone set down hard or
carried. The app registers the accelerometer on resume and drops it on pause, so it is not a
battery question.

A sideloaded app on a phone with no developer tools is otherwise a black box. It either works or
it "just closes", and the stack trace sits in a logcat nobody has a cable for. So the crash
handler writes the stack to disk on the way out, and the next launch offers to send it. It still
calls the default handler afterwards. This records the crash. It does not swallow it.

## Privacy

`ControlService` declares one event type and `canRetrieveWindowContent="false"`. It can observe
exactly two things: key codes, and the package name of the app that came to the front. That name
rides along with the event and needs no node access. Screen content stays unreachable by
declaration rather than by promise. One service does read a screen, the ADB pairing reader, and
the manifest restricts it to `com.android.settings`.

## Failing safe

**One switch turns everything off.** It sits first on the home screen, and `onKeyEvent` checks
it before anything else. After it, the app is indistinguishable from an uninstalled one. It
exists because the only other way to stop an accessibility service is a computer, which is not
what you have at 7am with an alarm going off.

A key filter is the one kind of app that can make a phone worse by breaking. Swallow a press and
then throw, and the key is simply gone. So:

**A clock keeps every key, always.** Anything registered for `SHOW_ALARMS` or `SET_ALARM` passes
through entirely. The ringing check below catches only the moment audio plays. A silenced alarm,
a pre-alarm screen and a snooze countdown are all a clock in front with something urgent to
dismiss and no sound to detect.

**Nothing gets intercepted while something rings, or for thirty seconds afterwards.** Any active
playback with a ring-like usage passes every key through, as does the ringer or call audio mode.
The grace window exists because the moment an alarm goes silent looks identical to silence,
while the screen with the stop button on it is still up.

**Four presses of the same binding and the service stands down.** Someone pressing the same
button over and over is someone whose phone is not doing what they asked.

**One activity start a second, at most.** The activity this most often starts is a launcher, and
launchers here run as uid 1000.

**Every fault answers "pass the key through".** `onKeyEvent` runs inside a catch that returns
false, because passing a key on is always safe and consuming one is not.

**Three faults in a minute and the service goes quiet** until you open the app again. A dormant
filter is indistinguishable from an uninstalled one, which is the right thing to degrade into.
The front screen shows the last fault, because a button that silently stopped working is worse
than a button that says why.

## Layout

```
Bindings.kt                buttons, gestures, actions, and the out-of-the-box defaults
Prefs.kt                   settings, plus the table that decides untouched apps
MainActivity.kt            the settings hub and its section screens; parentOf encodes Back

keys/LightKeys.kt          the keycodes, resolved by label then by scancode
keys/ControlService.kt     the filter service: gesture split, consume rules, foreground app
keys/Brightness.kt         system brightness with a derived scale
keys/ColorMode.kt          per-app color, written as state and never as a transition
keys/WheelSwipe.kt         the synthetic finger: one continued stroke, tracked and relifted
keys/BackGesture.kt        one edge stroke and its two thresholds, with no Android types in it
keys/EdgeSwipe.kt          an edge strip and its indicator, as two windows; one class per side
keys/Readout.kt            the brightness level, as one reused overlay window
keys/VolumeHud.kt          the volume level, reported and never adjusted
keys/CallAudio.kt          the call speaker, put to maximum once per speaker route
keys/Grants.kt             what is granted, and the volatile own-window flag

lock/LockOverlay.kt        the Light face as a service-owned window at layer 31
lock/LockNotifications.kt  the shade, filtered; NoteFilter names the three permanence flags
lock/LockMedia.kt          what is playing, read off the platform media session
lock/LockCall.kt           whether the phone is ringing, and the two ways to answer it
lock/MediaGlyph.kt         the transport marks, drawn rather than shipped as drawables
lock/LockBackground.kt     the photo and its filter stack
lock/LockGallery.kt        DCIM walked directly, because MediaStore is never current here
lock/LightType.kt          the light-sdk type scale and grid, for plain Views

adb/AdbManager.kt          the phone talking ADB to itself over loopback
adb/AdbPairOverlay.kt      pairing without leaving the Settings dialog
adb/GrantCheck.kt          whether a grant landed, asked of the phone not of the command
adb/GrantRequest.kt        another app's grant list, parsed and rebuilt, never executed

hotspot/TriggerEngine.kt   the raise and lower decision, with no Android in it
hotspot/SoftAp.kt          the access point, over the shell this app already holds
portal/PortalActivity.kt   the captive-portal WebView, bound to the captive network
report/                    shake to report, the crash log, and the queue
```

## Gotchas, in the order they will bite

- **The accessibility setting is a list, not a flag.** `settings put secure
  enabled_accessibility_services` overwrites whatever was there. Enabling BrightControl naively
  turns off the LightVoice push-to-talk unless you colon-join both components.
- **Consuming a key is the dangerous half.** The service consumes only what it acted on, so a
  bug here cannot trap you.
- **The click is a modifier and a button at once.** A held `WHEEL_CLICK` produces no key repeat.
  The app remembers the press on DOWN and fires the torch on UP only if no notch arrived in
  between.
- **The camera button sends two scancodes**, and the order flips between presses. `FOCUS`
  sometimes comes first and `CAMERA` other times. Only `CAMERA` triggers a binding. The app
  swallows `FOCUS` alongside it so no app ever sees half a press.
- **The readout overlay raises its own window-state events.** Trusting those would rewrite "the
  app in front" to BrightControl mid-turn. The app ignores events from its own package. The
  settings activity reports itself through a volatile flag instead.
- **Brightness has no fixed scale.** 255 is common, but 1023, 2047 and 4095 all ship. The app
  derives it from `screen_brightness` divided by `screen_brightness_float`.
- **The daltonizer off state is not `mode = 0`.** `enabled = 0` with `mode = 0` still reads as
  monochrome. Off is mode `-1`.
- **The adb shell service carries no exit status.** It merges stdout and stderr and returns no
  code, so "printed nothing" covers both success and a command that never ran. The app reads
  every grant back off the phone instead.
- **A BLE scan with no permission does not throw.** It returns nothing, forever, which looks
  exactly like an iPad that is not there.

## Not doing

- **Node-based scrolling.** `ACTION_SCROLL_FORWARD` is precise but needs
  `canRetrieveWindowContent`. Reading the whole screen to scroll a list is not a trade worth
  making.
- **Remapping the power button.** Long-press is the hardware power menu, below the framework.
- **A launcher tile per app.** The point is that the phone behaves consistently, not that there
  is more to configure.

## Building

```bash
./gradlew :app:assembleDebug
```

## Contributing

Solo repo, no PR workflow. Commits go straight to `main`. Every push to `main` triggers CI, which
builds, signs and publishes a GitHub Release. **A push is a release, not a cosmetic
action.** Verify before you push, not after. `paths-ignore` excludes documentation-only pushes.

The release keystore is a repository secret, not a file in the tree. CI decodes `KEYSTORE_B64`
to `keystore/lightcontrol.jks` — a gitignored path — and opens it with `KEYSTORE_PASSWORD`. A
build without those secrets still produces an installable APK; it is just signed with the local
debug key and will not install over a release. CI pins the certificate SHA-256 in
`signing-fingerprint.txt` and fails on drift. `versionCode` is the workflow run number.
`versionName` in the committed `build.gradle.kts` is only the `major.minor` base. CI stamps
`major.minor.RUN` at build time and tags it `vX.Y.Z`.

## Version history

Real tags, newest first. `RELEASE_NOTES.md` holds the full entry for the current release.

| Version | What changed |
| --- | --- |
| v3.82 | **The edge strips leave the top of the screen alone, and the tick is gone.** Nearly every screen puts a back arrow in the top-left corner, which is the corner the left strip ran through — reaching for the arrow with a thumb that slid inwards was a swipe rather than a tap, and on the right edge or a screen with no arrow the same slip fired a gesture nobody asked for. The top 92 dp of both edges now belong to the app, adjustable under **Leave the top alone**, with 0 the old behaviour. It had to be a hole in the window rather than a rule in the touch listener: an overlay that receives a touch has taken it, so a strip that merely ignored the corner would have left the arrow unreachable instead of reachable. The indicator's tick is removed as well — a short grey line inside an outlined box read as a smudge, and the word and glyph already change at the long threshold |
| v3.78 | **Luma is Home in the switcher, not an app.** The launcher is listed as Home behind a drawn house instead of its own name and icon — every other row is somewhere you were, and the one row that is how you *leave* was dressed as one more app. A thin outline among solid squares is the whole distinction. Matched by package rather than by the HOME role, which on this phone is always LightOS. Off under Buttons → Home button, and the setting stays off the screen entirely when Luma is not installed |
| v3.77 | **The switcher stays after a wheel hold, and ADB commands are readable.** The switcher opened by a wheel hold no longer closes on release — the release key event was reaching `onSwitcherKey` after the hold-threshold change in v3.68 opened it mid-press. Tapping to show a grant's adb command now gives it six lines instead of two, so nothing is cut off with \"...\" |
| v3.76 | **The pairing reader looks on a timer, not only when told.** It read on accessibility events, so a dialog that arrived while the app was settling was sometimes never seen at all |
| v3.75 | **A pairing is not condemned on one probe.** Reports #116 and #119 both said a shell stream was refused after a good pairing; the check was wrong, not the key |
| v3.74 | **BrightNotebook was drawing a second box.** Banners knew about two apps with a heads-up box of their own and there were three, so from v3.65 a reminder coming due drew BrightNotebook's box and then this app's over the top of it |
| v3.73 | **The switcher shows each app's icon.** Inline, ahead of the name, on the row itself rather than in views of its own. Sized to exactly one line so the row height does not move — anything taller changes how many apps fit and pushes the one furthest back below a fold this list cannot be scrolled past. The icon dims with its row, because the selection on that screen is one thing being brighter than the rest |
| v3.63 | **The left edge goes back out of the box.** It shipped off, on the reasoning that this is the one feature here that takes a touch rather than a key. That was right about the cost and wrong about the conclusion: a phone with no back button is broken in a way that a phone with a 14 dp strip down one edge is not, and a default nobody discovers is a feature nobody has. The right edge stays off, because the recents list is already a double press of home — an absence is worth filling, convenience is worth opting into. Turning it off writes a real false, so no existing choice is overridden |
| v3.62 | **STOP gives the buttons back immediately.** Stopping a run in flight no longer waits to be noticed: the buttons come back the moment you press it |
| v3.61 | **Every edge has two swipes, and both are bound like a button.** A short drag inwards does one thing and a long one does another, and all four are ordinary actions picked from the same screen the buttons use. `Go back` and `App switcher` are actions now rather than behaviour the strips owned privately, so the camera button can go back too. The indicator grew a tick for where the long binding takes over — without one the only way to find the second stage is to drag until the word changes, which is a gesture you learn by overshooting the one you wanted |
| v3.60 | **A run can be stopped, and STOP stands the retry down.** The ADB screens grew a way out of a command in flight, and stopping one no longer leaves the retry armed to start it again |
| v3.58 | **The right edge opens the switcher, and the home flicker is found.** A second strip, on the other edge, for the recents list. The flicker between a home screen and the toolbox was shadow mode adding a second destination: LightOS does not read a home press as "start the home activity", so a shadowed press produced LightOS's answer and a `CATEGORY_HOME` intent, racing. It only showed right after an unlock because that is the window where LightOS is still the front app. A plain Home tap is no longer fired in shadow while LightOS is in front. And pointing the tap at LightOS no longer costs the app switcher: succeeding starts a *visit*, and the visit was claiming the double press for its own exit before the switcher was asked |
| v3.56 | **A back gesture, and a lock face that stops repeating itself.** A strip on the left edge goes back, with a small box at the thumb that says when a release would commit it. Off by default, because it is the one feature here that receives a touch, and a received touch cannot be handed back. On the lock face, `FLAG_NO_CLEAR` now counts as permanent, which is what LightOS puts on its own always-running notice: that notice passed every check the filter had and could not be swiped off, because the platform refuses to cancel an un-clearable notification by not removing it. Permanent notifications are a switch now, and any app can be hidden from the face by name |
| v3.55 | **A running command says what it is doing.** The request screen collected the output of a command until the stream closed, so the most talkative helper there is, the one that answers a Bluetooth pairing request, said nothing for forty-five seconds and then everything at once. Whole lines arrive as they happen, the last forty are kept, and a request with several commands says which one it is on |
| v3.49 | **The dismiss swipe goes left.** Same gesture, other direction: a row is pushed off the left edge to clear it, which is the way every other shade on every other phone does it and the way a right thumb travels most easily |
| v3.48 | **Swipe left to clear a row, and no more half-notifications.** A notification swiped off the face is really cancelled, so it goes from the shade too; the player's card can be swiped away without touching the music and returns on the next track or the next play. The list now measures the space it is given and draws only whole rows — it was drawing four regardless and clipping the last one against a window nothing can scroll. The face also repaints when the shade changes instead of on the next minute tick |
| v3.42 | **Hold a row for App info, by thumb or by wheel.** The hold used to force stop the app over adb and background it where there was no shell — one gesture, two outcomes, and a message to say which one happened. Settings' App info page carries AOSP's own Force stop, which needs no shell and no permission, so the hold opens that instead and `ForceStop` and `KILL_BACKGROUND_PROCESSES` are gone. The bottom button is tap-only again: a gesture about an app belongs on the app's row, not on the control furthest from it |
| v3.41 | **A way out to the system's own screens, under the switcher.** SYSTEM SWITCHER asks the platform for its recents and then checks whether anything actually came forward, because `performGlobalAction` reports injection rather than appearance — nothing came up means the list returns with a line saying so. Holding it opens App info for the selected app, where AOSP's own Force stop is, which is what a hold on a row can only approximate without a paired adb shell |
| v3.40 | **The caller's name comes from telephony, and the call screen is fetched rather than hoped for.** v3.38 fixed how a call notification is read; on this phone there is no call notification — LightOS's dialer is a system app that shows its own activity and posts nothing. The card now reads the number off the `PHONE_STATE` broadcast and the name out of contacts, behind two new grants in the one-tap ADB run. And `showInCallScreen`, which returns nothing and reports nothing, is no longer treated as a hand-off: when no intent could be sent, the face resumes the dialer's own task, which during a call is the call screen. A log line now says what the phone actually told us, per ring |
| v3.39 | **A request may ask to repair a broken system app.** One word, one fixed table of two system packages, nothing typed by the sender reaching the shell |
| v3.38 | **The call card says who is calling, and the call screen comes up when you answer.** The card read `EXTRA_TITLE`, which a CallStyle notification leaves empty — the caller is a `Person` the platform renders at draw time — so every call was "Incoming call". It now reads the person, the people list, four text fields and the number, in that order, and treats a notification as a call on four tests rather than one. ANSWER takes the face down on the press instead of a poll tick later, and asks the dialer for its own screen on the way out rather than merely uncovering whatever was behind |
| v3.37 | **The lock face knows what it is playing.** One control set for three kinds of thing was wrong twice: previous and next on an hour-long podcast mean lose the hour, and on a live stream they are two dead buttons. Podcasts get back 15 and forward 15, as a real `seekTo` from an extrapolated position rather than the platform's whatever-the-player-decided `fastForward`. Streams get a stop. The kind comes off what the session declares — a queue, a position, a step, a length — so it works for any player and needs no list of package names |
| v3.17 | **What is playing, on the lock face.** Cover, track and skip controls under the notifications. A player cannot draw this itself. An app window sits at layer 11 and the face sits at 31, so BrightMusic's own controls were painted underneath it. The row reads the platform media session, so it works for any player and needs no new grant |
| v3.14 | **Wi-Fi login and Hotspot are labelled unfinished, in the app and here.** Both shipped looking like finished features and neither one is. The portal needs a system WebView this phone may not have. The hotspot depends on a BLE identity key, a shell that survives a reboot, and an iPad that chooses to join. A feature that might not work is fine. One that does not say so is not. Also a README rewritten around what the app now is, six subsystems rather than the wheel and two buttons, with a Quick start that no longer opens by telling you to find a computer |
| v3.13 | **The apps that ship on PASS could not be tapped off it.** Roll and BrightChat both hold the daltonizer grant and set their own color, so both ship as PASS. Both rows sat unchanged however often anyone tapped them. The step after PASS is AUTO, AUTO stores nothing, and storing nothing resolves back through the preset table to PASS. Two correct rules cancelled out, on exactly the two apps the feature is for. The step now follows what it resolves to |
| v3.12 | **The ADB connection reconnects itself.** The daemon TLS listener does not survive leaving the Wireless-debugging screen and comes back on a new port. The connection made during setup was therefore dead by the time anyone walked back to the button that needed it: six grants, six `Stream closed`. Pairing needs a human. The port is discovery. Every batch now reconnects in front of itself |
| v3.11 | **The color log names the app in full.** The app cut package ids to their last segment, so the line that mattered read as a bare word like `edgegestures`, which you cannot look up or grant a rule to |
| v3.10 | **PASS, presets, and grants that say whether they worked.** A fourth color rule that writes nothing at all, for apps that hold `WRITE_SECURE_SETTINGS` and set their own color. Every ADB grant now reads back off the phone rather than getting judged by what the command printed |
| v3.9 | **The color diagnostic reported on a phone that no longer existed.** The screen read the log once at composition, so coming back from the app you had just tested showed "Nothing applied yet" over six applied rules. Both the screen and the send title re-read on resume |
| v2.15 | **Apps that own the whole wheel.** BrightRecorder is hands-off outright. Its wheel press is play and stop, and the click default is the torch, so the press got eaten before the app saw it |
| v2.14 | **The camera button works from the lock face.** At layer 31 the face sits above even an app that just came to the front, so the shutter fired and the viewfinder never appeared. Any binding that brings something forward now takes the face down with it |
| v2.13 | **The face holds black for half a second, then fades up.** Painting a lock screen only to remove it inside the unlock window is a flicker that reads as a fault |
| v2.12 | **The BrightChat photo grid, not the system picker.** Nothing on LightOS keeps MediaStore current, so no picker offers a photo taken minutes ago. The editor now walks DCIM and Pictures itself |
| v2.11 | **The background editor, on the lock screen.** Dither, black and white, opacity, corner blur and corner fade, reorderable and repeatable, with a live preview at the panel aspect ratio |
| v2.10 | **Two defects in the v2.9 unlock watch, one of which ran all night.** The 300 ms poll followed the face being up rather than the screen being on. It ticked three times a second for every hour the phone spent asleep |
| v2.9 | **The face comes down on unlock, and unlocking opens something.** Neither `ACTION_USER_PRESENT` nor the keyguard listener arrived, so nothing noticed that the phone had opened |
| v2.8 | The lock face uses **the LightOS type scale and grid**, ported from `light-sdk`. No hardcoded sp or dp anywhere on the screen |
| v2.7 | **The thumb works.** The lock face stopped being an activity. `showWhenLocked` marks the keyguard occluded, which switches off a power-button fingerprint reader. It is now a service-owned window at layer 31, above the keyguard layer of 17 |
| v2.6 | **The lock face, actually working.** Three bugs, two of them one bug. The bouncer stops the occluding activity, so the unlock broadcast arrived after the receiver was gone |

## Licence

MIT. The icons and design tokens come from
[`lightphone/light-sdk`](https://github.com/lightphone/light-sdk), MIT, and the copyright belongs
to The Light Phone. That covers the 27x31 grid, the type scale and the haptics. See
`LICENSE-light-sdk`.

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
