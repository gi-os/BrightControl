## LightControl v2.0 — Tap the volume strip to pick what the keys move

**Two changes to the volume HUD, and the second one is the first time this app moves a volume
itself.**

It no longer appears over LightOS. Light's dashboard and lock screen already have a volume control
of their own, and a second one drawn over the top of it is this app's oldest mistake in a new place:
on Light's own screens, anything added is something duplicated. Everywhere else is unchanged.

And the strip is now tappable. Android hands the volume keys one stream at a time — whatever is
playing, and media when nothing is — so the ringer and the alarm levels cannot be reached from the
hardware at all, and LightOS has no screen for them either. Tap the strip and it cycles: media, ring,
alarm, and the call stream when there is a call. The chosen one says **PIN**, and for the next few
seconds the keys move that stream instead of the default one.

Reaching those streams means taking the press, so this is the one place the app adjusts a volume
rather than reporting one — which is a rule worth bending only inside a fence:

- It needs a tap first. Until you tap, no volume key is consumed, exactly as before.
- It applies to one named stream, and it expires with the strip. Every press that uses it extends it.
- It cannot survive a ring. The takeover lives in a path the service never reaches while anything is
  ringing or a call is up, so the keys that dismiss an alarm are never in question.
- Walking the ringer down into silence needs Do Not Disturb access, which a sideloaded app doesn't
  have. The strip says NEEDS DND ACCESS instead of quietly doing nothing.
- **Settings → VOLUME → Tap to pick a stream** turns it off, and with it off nothing here consumes a
  key at all.

The strip had to become touchable for this, which is a real cost stated plainly: a tap landing on a
thin bar at the very top of the screen, during the second or so it is visible, goes to the strip
instead of the app. Everything outside it passes through untouched, and the window stays unfocusable
— so it can take a stray tap, but never a key.

## LightControl v1.9 — The wheel says what it did, and the volume strip gets out of the way

**Two small things, one of them a diagnosis tool.**

The volume HUD is half the height it was. The first version used a LightOS bar's worth of padding,
which is the right amount for something you tap and far too much for something that flashes over the
top of whatever you are looking at. The label drops one step down the type scale, the notches are
shorter and their gutters narrower, and the strip now reads as a line of type rather than a panel.

The key log has learned about **turns**. Until now it only recorded buttons, which meant the half of
this app that fails *quietly* left no evidence at all: a wheel that does nothing in one app looks
exactly the same whether the key never reached the service, the app resolved to pass the turn
through, or the brightness write was refused for a missing grant. Those are three different fixes.
Now each turn writes one line — the app in front, what was decided, and the level it landed on —
deduped so a twenty-notch gesture costs one line instead of filling the log.

The useful part is what the log says when it says nothing. **No line for a turn means the service was
never handed the key**, and no setting in this app can be the cause. A line saying PASS THROUGH means
the app is set to scroll itself; one saying "blocked — no WRITE_SETTINGS" means the appop is gone.
Settings → KEY LOG.

## LightControl v1.8 — A volume level you can see

**LightOS has no volume UI. The keys work; nothing tells you what they did.**

Press volume up on this phone and the level changes in silence — no bar, no number, nothing. With
music playing you can hear roughly where you are, so you find the level by overshooting and coming
back down. On the ringer there is no feedback at all: a silent phone and a phone at one notch look
identical until something arrives and either rings or doesn't.

So the bar LightOS left out now comes from here. Change the volume and a black strip appears at the
top of the screen with the stream, the percentage, and a row of notches — one notch per press, so it
says how many more presses are left rather than only roughly how loud. It sits at the top rather than
the bottom, where the brightness readout lives, because volume is what you glance up at with a thumb
already on the key. Vibrate and silent are named instead of numbered, since on the ringer those are
what the level *means*.

It watches the whole system, not just the keys. Android broadcasts every volume change, so a media
app's own slider, a headset's buttons and a Bluetooth speaker turning itself down all show up the
same way. A volume key is also read back directly a moment later, in case that broadcast ever stops
coming.

What it does not do is touch the keys. The volume pair is the one this app has always passed straight
through — taking them would mean re-implementing volume to add a picture of it — so the HUD only ever
reports. Nothing is consumed, nothing is adjusted, and it runs even in the moments the service
otherwise keeps its hands off every key: an alarm ringing, a call, a clock in front. The overlay is
untouchable and unfocusable, so it can never take a tap or swallow the next press. It needs the same
overlay grant the brightness readout does, and with the grant missing it simply doesn't appear.

**Settings → VOLUME → Show the level** turns it off. The master switch turns it off too, because "this
app does nothing" has to mean nothing.

## LightControl v1.7 — The home button goes where you pointed it

**Two presses of home, two destinations, and neither of them is home unless you said so.**

*Back to where you were* shipped as a pair of presses: the first brings back the app the screen
slept in, the second moves on to whatever you set under **Otherwise open**. Set that to an app and
the second press still went home. Two separate things were doing it, and both were old defences
firing at the wrong target.

The first is the rate limit on opening things. This service starts activities from the background,
and it has always allowed one a second — a real guard, because the activity it most often starts is
LightOS's launcher, which runs as a system process, and restarting a launcher on a loop while it is
showing an alarm is not something a thumb can ask for. But it counted starts, not destinations. Two
presses inside a second are exactly what the resume pair *is*, so the second one was refused — and a
refused launch was being read as "that app cannot be opened", which falls back to home on purpose so
that an app uninstalled since you bound it can never leave you stranded. A queue is not a failure.
The limit is now per destination: the same target keeps its full second, a different one waits a
quarter of it, and a start that was merely early no longer gets home substituted for it.

The second only happened on Light's own screens, which is where a press after a wake lands. The home
button has a rule that it will not swallow the key while LightOS is in front, because home already
goes there and taking the key could only lose. What it does instead is *shadow* the press: consume
nothing, let LightOS have the whole thing, and fire your tap binding on top. That is invisible when
the tap is home — the same destination twice — and it is a coin flip when the tap is an app, because
LightOS reads a home press as "back to the idle face" and your app was racing a launcher. So a tap
that names a destination of its own now takes the key wherever you are standing, Light's screens
included. Everything that made the key safe is untouched: the takeover switch, the screen-off and
locked-phone refusals, the missing-overlay-grant refusal, alarms and clocks owning every key they can
see. Leave the tap on Home and nothing about this release changes anything.

## LightControl v1.6 — Shake the phone to report a bug

**LightControl can now file its own bug reports, and you can say what went wrong in your own words.**

Until now only Roll, Notebook and Phono could do this. Every other app on the phone failed
silently: you would notice something wrong on the subway, have nowhere to put it, and have
forgotten it by the time you were near a computer. This is the same feature, ported.

Shake the phone twice — there and back, twice — and a sheet comes up. Pick what happened from
five chips, and add a note if you have something to add. The note is optional but it is the part
that carries anything: "Something looks wrong" is a shrug, and what you type becomes the title of
the issue. Under it the report carries the screen you were on, the app and firmware versions,
free space, heap, and the stack trace if the app died the last time you had it open.

Three things raise the sheet. A shake, because you noticed something. A crash last run, asked
once on the next launch, because that is the only moment the stack trace is still worth anything.
And a failure the app noticed by itself — those are the reports that otherwise never get filed,
because a screen that quietly came back empty looks ordinary.

Reports queue on disk before anything is sent, always. A phone that reports a freeze is by
definition a phone that was just misbehaving, and a report that exists only in flight is the one
report guaranteed to be lost. If there is no network, or this build has no reporting key, it
waits on the phone until a build that does installs over it.

The gesture is tuned to be hard to trigger by accident: it counts reversals rather than force,
because setting the phone down hard clears any threshold a shake clears, but only a shake
*reverses*. Walking never fires it. That arithmetic now has unit tests in every app that has the
feature.

The accelerometer only runs while you are looking at the app.
