## BrightControl v3.86 — every button does everything, and a camera keeps its own shutter

**A double tap is a binding now, on all five buttons.** There were two of them before and neither
was yours: the wheel's, which switched what a turn meant, and home's, which opened the app
switcher. Both were on-off switches on two different screens, written as two different pieces of
code, and the camera button and the volume keys had no double tap at all. All of it is one path
now. Every button has a Tap, a Hold and a Double tap, each one a full action picked from the same
list — and the wheel and home still ship with exactly what they always did, so nothing you have
set up changes on its own.

The one thing that cannot be made identical is what the tap does while it waits to find out
whether a second press is coming, and both answers are honest:

- **Waits** holds the tap back a moment, so a double tap never also fires the tap on the way past.
  It costs about a third of a second on every single press. This is what the wheel always did, and
  it is why switching the turn mode stopped also turning the flashlight on.
- **Fires both** sends the tap the instant you let go, and a second press adds the double on top
  of it. This is what home always did, and it is why home has never felt slow. What it costs is a
  glimpse of the home screen on the way to the switcher.

Home keeps *fires both*, everything else keeps *waits*, and the row appears under any button with
a double tap bound.

**Thirteen new things a button can do**, all of them available on every button and both edge
swipes, because a phone that can bind something to one gesture and not to another is doing it for
no reason anybody chose:

- **System settings** — the settings LightOS ships no way into.
- **Notification shade**, **quick settings**, **screenshot**, **lock the phone**, **power menu**.
- **Volume up** and **volume down**, moving whatever stream the volume keys would have moved, so
  they follow a call or a track without being told which.
- **Brighter** and **dimmer**, one notch, down the same path a wheel turn takes.
- **Colour or mono**, which flips the app in front and remembers it. Written as that app's colour
  rule rather than as a write to the screen, because a second writer is the one failure this app's
  colour engine is built to be immune to.
- **Switch what a turn does**, between brightness and scrolling — the wheel's old double tap,
  now bindable anywhere.
- **Lock face**, put up over whatever is on screen.
- **Hotspot**, up or down, with the network name already saved.

**The camera button no longer eats the shutter of the camera it opened.** Reported by a Zero user,
and it was never really about Zero: bind the camera button to open any camera app, and the button
was then swallowed *everywhere*, including inside the app the binding exists to reach. The app
opened and could not take the photograph.

There was already a rule for this — an app that declares itself a camera keeps the camera button —
and Zero cannot pass it. It declares only a launcher entry, while handling `KEYCODE_CAMERA` and
`KEYCODE_FOCUS` perfectly well itself. Nothing about it is wrong; there is simply no way to tell it
is a camera by asking the package manager.

So the rule is a different question now, one that needs no list and no declaration: **a button
whose bindings only say "open this app" is not touched while that app is already in front.**
Launching an app from inside itself does nothing, so the key was being spent on nothing, and the
app in front is the better owner of it. Every acting binding on the button has to point there — a
hold bound to something else is a real second gesture and keeps the button claimed — and the home
button is deliberately outside this, because a home key that stops working inside one app is not a
trade worth making. The declared-camera test also got wider, and now counts an app that registers
for video capture or image capture rather than stills alone.

**Under the hood.** The wheel's pending-tap timer and home's double-press window were separate
mechanisms with separate bugs available to them; they are one mechanism keyed by button. The two
retired switches — "double tap to switch" on the wheel screen, "double press" under home — carry
forward into bindings, including if you had turned either of them off. The switcher's scroll speed
and its Luma row now appear whenever *anything* is bound to open the switcher, rather than only
when home's double press was on, and the Resume app list appears whenever anything is bound to
Resume rather than only home's tap.
