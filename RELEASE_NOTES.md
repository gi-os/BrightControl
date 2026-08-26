## BrightControl v3.41 — a door to the system's own switcher, and to App info

**SYSTEM SWITCHER, at the bottom of the list.** `performGlobalAction(GLOBAL_ACTION_RECENTS)` has
always been the wrong thing for the *home button* to do on this phone: it reports that the action
was injected, not that a screen appeared, and on LightOS nothing appears. A gesture that returns
success and does nothing is the worst answer available, which is why the double press draws this
app's own list instead.

It is on the screen now anyway, as a button. "This firmware ships no recents" is a conclusion drawn
from one firmware, and the cost of being wrong about it is a phone quietly hiding a working
switcher. The difference between a button and a gesture here is that a button can be held to its
answer: the overlay goes down first — it is layer 31 and a recents screen is an activity — the
system gets 800 ms, and if no package came to the front, the list comes back with **NOTHING CAME UP
· no system switcher here** on it. A dead button that admits it is dead costs a tap. A missing one
costs the feature.

**Hold it for App info.** The system's own application page for whichever app the selection is on,
which is where AOSP keeps a Force stop button — the real one, the same act the switcher's hold
reaches for over adb. On a phone with no paired shell that hold can only background an app and says
so; this is the two-tap way to do the whole thing, with nothing to pair and nothing to grant.

It is one button with two answers rather than two rows, and the hold is written on it in the
second line, because the hold is the more useful half and a hold nobody knows about is a feature
nobody has. Both re-arm the idle timer instead of closing the list: asking for another screen is not
the same as being done with this one, and the answer has to be able to land back here.

**The row count knows about it.** [capacity] measures how many rows fit from the panel height and
the type scale, and it now subtracts the button as well as the header and the hint. A row this
arithmetic forgets is a row below the fold of a list that cannot be scrolled with a finger, and it
is always the app furthest back — the one the switcher exists for.
