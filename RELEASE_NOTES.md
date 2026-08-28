## BrightControl v4.1 — one screen for the switcher, and no flash of the app you are leaving

### Picking an app no longer shows you the old one first

The switcher took itself down and *then* started the activity. That is the obvious order and it is
the wrong one: a start is not instant, and for the few frames between the window coming off and the
new app drawing, what is on screen is **the app you were trying to leave**. Reported as switching
that goes a little too quick, which is exactly how it looks — a flash of where you were on the way
to where you asked to go.

The list is a full-screen opaque window above everything, so leaving it up costs nothing and hides
the whole handover. The app starts behind it, and the list lifts off a screen that is already
correct. One cut instead of a flicker.

It comes off when the window-state event names the new app, plus a frame to let it draw — removing
it one frame early shows the old app again, which is the bug rather than the fix. And after 700 ms
regardless, because a start can be throttled, refused, or land somewhere that raises no event this
service is allowed to see, and a full-screen black window with nothing to remove it is the single
worst thing this app could ship.

### App switcher is a screen of its own

On the main menu, under Controls. Scroll speed, Show Home, and Home app — everything about the
recent-apps list in the one place.

They used to live under **Buttons → Home button**, from back when a double press of home was the
only way to open that window. It has been an ordinary binding on all five buttons and both edges
since v3.86, so those rows were sitting under one gesture on one particular button: not where
anybody looks for "how fast does this scroll", and gone entirely if you moved the binding elsewhere,
which reads as settings being taken away.

**It says what opens it.** A read-only row names every button gesture and live edge swipe bound to
the switcher, and says so plainly when nothing is. The bindings themselves stay in Buttons and Edge
gestures — a screen that could set both would be a second binding editor, out of step with the first
the moment either changed.

Two lines at the bottom answer the questions this feature actually gets: what the wheel, the click
and the hold do while the list is up, and why the list is drawn here rather than being the
platform's own.

Buttons keeps a door to it under the home button, once something is bound to the switcher.
