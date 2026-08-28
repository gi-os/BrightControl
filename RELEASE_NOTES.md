## BrightControl v3.98 — the strip stays out of apps you name, and the edges buzz

**Four separate reports about four different apps turned out to be one missing list.**

The volume strip refuses to draw over Light's own screens, because those have a volume control of
their own and a second one on top is the same number twice. That refusal is a table of package
prefixes, and a table can only ever know about the software it was written for. It does not know
that a sideloaded audiobook player draws its own slider, that BrightLibrary is doing something else
with the keys entirely, or that one person simply does not want an overlay across the dialer while
a call is running.

### Volume → Apps the strip stays down for

A list you keep, empty out of the box. An app on it gets no strip at all — not from a key, not from
the app's own slider, not from a headset button.

That last part is the difference from the list next to it. *Apps with their own volume keys* is
about a press: it says this app's volume keys turn pages, so a press in it was never a volume
change. It deliberately leaves the broadcast path alone, because an app that reads a key without
consuming it really did move the volume. This new list is the blunt one, and it gates both paths.
It is also checked **before** the built-in table, which is what makes the dialer reachable: the
table hands the dialer a strip on purpose, since it is the one Light screen with no volume UI of
its own and it is in front for the whole call, and until now there was no way to say no to that.

Light's own screens show ALWAYS OFF in the list rather than a switch that would do nothing —
except that tapping one still works, for exactly the dialer case above. The two rules that decide
where the strip may appear now sit together in one function with tests on it, instead of a hundred
lines apart in the watcher with one of them written inline as a prefix test.

Fixes [light-reports#74], [light-reports#117], [light-reports#135] and [light-reports#156] — the
strip drawn over BrightLibrary, over the LightOS phone app, and over an audiobook player's own
volume UI.

### The edge gestures are felt, not just seen

A tick as the drag passes each threshold, and again when the gesture fires. LightOS buzzes its own
back gesture, and a gesture on the same phone that does not buzz reads as a gesture that did not
work — which is what was reported, twice.

It goes on the *crossing*, so a threshold is felt once rather than humming for the length of the
drag, and it is felt in both directions: pulling back under a threshold changes what a release will
do, and that is worth knowing without looking. A stroke given away as a scroll stays silent, and so
does a gesture the app in front refuses, because a buzz there would be the phone claiming to have
done something it did not do. **Edge gestures → Buzz as it arms** turns it off; the phone's own
haptics switch still sits outside it.

Fixes [light-reports#124] and [light-reports#133].

### Settings rows are no longer cut off mid-sentence

The explanation under a setting was limited to two lines with an ellipsis, and call sites had been
raising that limit one at a time — three here, four there, each a guess at how tall a particular
string renders on a 360dp screen. Every reworded sentence quietly re-broke whichever rows the guess
no longer fitted. Reported as text "not wrapping correctly to fit the screen", which is what a
clipped wrap looks like from the outside: the words are there, the screen has room, and the app
cuts them off anyway.

The limit is gone. A row is as tall as its text, and a longer explanation costs scrolling rather
than the end of a sentence.

Fixes [light-reports#130].

### The note field no longer hides under the keyboard

Typing a long note in the report sheet pushed the line you were writing behind the keyboard. The
sheet scrolls, and Compose already brings a focused field into view inside a scroll — but it was
bringing it into a region the keyboard was covering, because the sheet had no idea the bottom of
the screen had moved. It does now.

Fixes [light-reports#134]. The same fix went into `light-common`, which is where the other apps
get this sheet from.
