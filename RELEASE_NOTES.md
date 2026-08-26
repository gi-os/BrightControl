## BrightControl v3.56 — a back gesture, and a lock face that stops showing you the same notice forever

Two things, both asked for by Ryan Ness in Discord, and both about something this phone does not
have.

### Swipe back

> "apps built into lightos have this already available since there is a gesture navigation option
> in lightos settings but app outside of lightos don't allow for going back since lightos doesnt
> have a back button"

That is the whole problem. LightOS took the navigation bar away and put a gesture-navigation switch
in its own settings, and that switch reaches Light's own tools. Everything sideloaded is left with
no way back at all — an app that pushes a screen and forgot to draw its own arrow is a dead end
until you press home and start again.

**So: a thin strip down the left edge. Drag right, let go, the app goes back.** New section on the
home screen, under Controls, off until you switch it on.

A small box appears at your thumb while the drag is happening: an outline while the stroke is still
short, then white with the word BACK on it once letting go would actually go back. That is not
decoration. Crossing the trigger only **arms** the gesture — the lift is what commits it, and
dragging back under the trigger disarms it — so there has to be something on screen saying which of
those two states you are in.

**What it costs, stated plainly, because it is the only feature in this app that takes a touch.**
There is no way to watch a touch without receiving it. Gesture detection through the accessibility
API needs touch exploration switched on, which changes how the entire phone is driven, and
`dispatchGesture` sends touches rather than receiving them. What is left is an overlay window, and an
overlay window that gets a touch has taken it — a swallowed touch cannot be handed back once the
stroke has begun.

So the honest description is: touches that begin within 14 dp of the left edge go to BrightControl
instead of to the app. Everything outside the strip behaves exactly as it did. And because that is a
real cost rather than a rounding error:

- **It is off until you turn it on**, like the lock face and for the same reason. Everything else
  here changes what a *key* does, and a key this app declines to take is a key the app still gets.
- **The width is a setting** — 10, 14, 20 or 28 dp. The entire cost of the feature is that number.
- **Any app can be left out**, for the apps whose left edge is a control: a pager, a slider, a
  drawer.
- **Light's own software never gets a strip.** It already has a back gesture, and a second one over
  the top would be the weaker of two on the same edge.
- **Nor does the lock face or the app switcher.** Both are full-screen windows above the strip, and
  their own swipes are what the left edge is for while they are up.
- **It can never cost a key.** The strip is never focusable, so the wheel and the buttons are
  untouched by it. That is the rule the rest of this app is built on, and this does not break it.

One thing it cannot do anything about: going back is a *request*. `GLOBAL_ACTION_BACK` is the only
route an accessibility service has, and what an app does with a back is the app's business. On its
first screen many apps accept it and do nothing, which from out here is indistinguishable from
working.

### The lock face and the notification that would not go

> "Brightcontrols lock screen displays lightos notification since its a process that is always
> running in background. Not sure if this can be adjusted?"

It can, and the reason it was happening is a missing flag.

The face has always dropped the always-running kind of notification, and it tested two flags for it:
`FLAG_ONGOING_EVENT` and `FLAG_FOREGROUND_SERVICE`. There is a third. **`FLAG_NO_CLEAR` says nothing
about progress — it says the notification cannot be dismissed** — and it is the one LightOS puts on
its notice about itself. So that notice set neither of the flags being tested, passed every check the
filter had, landed on the face at full importance, and then could not be swiped away: the platform
refuses to cancel an un-clearable notification by simply not removing it, so the rebuild brought the
row straight back. From the phone, that looks exactly like the swipe not working.

Three changes:

- **`FLAG_NO_CLEAR` counts as permanent**, along with `CATEGORY_SERVICE` for an app that describes
  itself that way without setting a flag. Which means LightOS's notice is off the face by default,
  with nothing to configure.
- **Permanent notifications are now a switch** rather than a hard rule — *Lock screen → Permanent
  notifications*. Off is what the code always did. On is for the case where the permanent
  notification is the point: a recording, a download, a navigation.
- **A swipe always removes the row.** Where the real cancel is refused, the row is held out of the
  list for the rest of the locked session and forgotten at the next unlock. Deliberately not stored:
  a face keeping its own permanent record of what you had waved away is a face that disagrees with
  the shade, with Glance and with the app that posted it.

And for anything the flags do not catch: **Lock screen → Apps never shown**. A list of every app that
has actually posted something, taken from the raw shade before any of the face's own rules have
filtered it — so an app whose notification is already being dropped still appears there to be hidden
for good. Hiding a source affects the lock face and nothing else: nothing is cancelled, nothing is
stored about what the notification said, and the shade, Glance and the app itself are untouched.

### Also

- The app switcher now tells the service when it goes up and comes down, so the nine call sites that
  take it down are no longer nine places to remember the back strip in.
- The settings screen can tell the service a setting changed. Only the back strip needs it, and it
  needs it badly: the strip is a window, so switching it on has to put something on screen — and
  window events from this app's own package are dropped before the service sees them, so turning the
  gesture on and then trying it inside these settings did nothing at all.
- Twenty new tests on the JVM: the gesture's whole decision (arm, disarm, cancel a scroll, clamp the
  travel, keep the indicator's anchor where the finger went down), the three permanence flags by
  their literal wire values, and the back strip's table — which is separate from the wheel's on
  purpose, because an app can own the whole wheel and still want a way back. Roll and BrightRecorder
  are hands-off for the wheel and both get the strip.
