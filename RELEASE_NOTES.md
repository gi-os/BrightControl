## BrightControl v3.94 — the bar is a slider, and the keys are handed back

### Drag the bar

The strip was a readout for eleven releases. "It reports; it never adjusts" was the right rule while
the only way to touch it was a key — a key filter must never take a working control away to add one
— but that rule was about *keys*. A finger on a bar this app drew takes nothing from anybody.

So the bar is a slider. Drag it and the volume goes there, in the strip and in every row of the
panel behind it. The bar is drawn thin and the thing you touch is a finger's worth of height around
it, because a control you have to aim at is not one you can use with the phone half out of a pocket.

Tap the *name* rather than the bar to open the panel — every volume this phone has, each one
draggable.

### The panel opens for everybody now

It used to be gated behind **Tap to pick a stream**, which is a setting about letting a volume key be
*consumed*. Sliders consume nothing, so gating the only route to the ringer and alarm levels behind
it left them unreachable by default for no reason at all.

That setting still exists and is still off: tapping a *name* in the panel hands the hardware keys to
that stream for a few seconds, and that is the one thing here that takes a key.

### Vibrate and silent are one tap apart

Under the ring slider there is now a **RINGER** row: normal, vibrate, silent, cycling on tap.

Three states of one switch, and the bottom of a slider is only the first of them. Dragging the ring
volume to zero gets you vibrate on this phone and there is nothing further to drag, so getting from
vibrate to silent had no gesture at all. If the change is refused the row says so — muting a phone
needs Do Not Disturb access, which the ADB screen grants.

### The volume keys are handed back

**This is why the volume keys could stop working.** Tapping a name pins a stream so the keys move it,
and that pin consumed the press. If the stream could not actually be moved — crossing the ringer into
silence needs Do Not Disturb access, and some streams the platform simply refuses — the old code
consumed the press *anyway*, and refreshed the pin while doing it. So once a pin landed on a stream
this app could not move, every further press was swallowed and extended the pin that was swallowing
it. The keys were dead for as long as you kept pressing them.

A pinned stream that cannot be moved now ends the pin and returns the key to the system, which is the
rule this app has had since the beginning and had quietly broken in one place. The move is verified
by reading the level back rather than by the absence of an exception, and being already at the end of
the scale counts as moved, because there was nowhere to go.

**Volume** also lists every binding on both volume keys and offers to hand them back in one tap. A
volume key that stopped working is this app's fault more often than not, and there was nowhere on the
phone that said what was holding it.
