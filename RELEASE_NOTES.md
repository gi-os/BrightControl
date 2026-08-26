## BrightControl v3.60 — every edge has two swipes, and both are bound like a button

The edges did one fixed thing each: left went back, right opened the switcher. Now each edge has a
**short swipe and a long one**, and all four are ordinary bindings picked from the same screen the
wheel click and the camera button use.

| Edge | Short | Long |
|---|---|---|
| Left | Back | App switcher |
| Right | App switcher | Back |

Those are the defaults, and they need no opinion about which edge is which: the two edges mirror each
other. Change any of the four to anything a button can do — an app, home, LightOS's dashboard, Back
to where you were, the flashlight, the camera, nothing at all.

**Two new actions, and they are available to the buttons too.** `Go back` and `App switcher` used to
be behaviours the edge strips owned privately. They are now `Action`s like any other, which means the
camera button can go back and a hold on the wheel can open the switcher. Back is the only action on
this phone with no hardware to reach it, so having it bindable anywhere is the point.

### The long swipe

Carry the drag past **150 dp** — a third of the panel, adjustable to 110, 200, 260, or off — and the
long binding replaces the short one.

**Crossing a threshold only arms it; the lift is what commits.** That was already true and it is what
makes a second stage work at all: passing the short threshold on the way to the long one is
unavoidable, so a gesture that fired on crossing would perform the short binding every single time
and then perform the long one as well. Drag back under a threshold and it drops to the stage below,
so a stroke can always be changed your mind about.

**The indicator grew a tick.** The box now measures the whole gesture rather than just the short one,
with a mark at the point where the long swipe takes over. Without it a long swipe is a guess: the box
grows, the word changes at some point, and there is nothing to say how much further the second stage
is. The word itself is whichever binding a release would perform — BACK, APPS, HOME, LIGHTOS, or an
app's own name — with a chevron for back, two cards for the switcher, and a plain filled square for
everything else. An icon that guessed wrong would be worse than a neutral one on a screen read at
arm's length mid-drag.

A long threshold past four fifths of the screen is pulled back, because a threshold you cannot reach
is a gesture nobody can complete. One under the short threshold is pushed above it, because two
thresholds in the wrong order make the short binding unreachable — every stroke that armed it would
already have armed the long one. Neither is a setting anybody should be able to produce, and a
settings screen cannot be trusted never to try.

### Also

- **A long swipe costs nothing more than a short one.** The strip is the same width either way. Only
  how far the finger travels afterwards differs, and by then the touch is already ours. The whole
  cost of these gestures is still one number: the strip width.
- One picker for both kinds of binding, addressed by a `BindSlot`. A second picker for the edges
  would have been a second list of actions to keep in step with the first, which is how a phone ends
  up able to bind an app to a button and not to a gesture for no reason anybody chose.
- An edge whose long binding is set to nothing has one gesture, and its indicator measures the short
  one — no tick two thirds of the way across for something that will not happen.
- Eight more JVM tests, twenty-one in all: through the short stage on the way to the long one, back
  out of it again, travel measured against the right threshold, the tick's position, a long stroke's
  vertical drift still reading as horizontal, and both stages on the right edge as well.
- Stored settings keys are unchanged, so nothing you had configured moves.
