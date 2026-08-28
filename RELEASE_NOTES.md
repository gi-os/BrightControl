## BrightControl v3.97 — Home is pinned to the switcher, and is not an app in it

**Every other row in the switcher is somewhere you were. Home is where you go to leave wherever you
were.** Ranking that by recency was always the wrong shape, and drawing it with a name and an icon
like the rest of the list made the one row that is not an app look exactly like the apps.

Home now sits at the bottom, under its own **HOME** heading, with the drawn house — always there,
whether or not you have been to it, and taken out of the recents above it. A launcher that appears
twice, once as itself and once as Home, is the list telling you two different things about one press.

### Home is the home button's tap, not a list of launcher packages

v3.78 relabelled Luma by matching `app.luma`, which only ever answered a narrower question: whether
one named app should be renamed where it happened to appear. This reads the **Home button → Tap**
binding instead, because that is what a person means by home on this phone — whatever a single press
actually reaches.

That is also what makes the hiding right rather than a special case. Bind the tap to Luma and Luma
stops appearing as an app, because it stopped being one the moment it became the destination of the
home button. Bind it to something else and Luma is an app again, listed by its own name and icon,
with nothing anywhere to undo.

Asking the system who holds the HOME role would give the wrong answer every time: LightOS holds it
and has to, or it crash-loops, no matter which launcher is being used.

A tap bound to something that is not a home at all — back, the switcher, the torch — leaves the
pinned row on the system's own home. The row's promise is that the list always has a way out of it;
it is not a second copy of whatever the home button happens to be doing.

### What moved in Buttons

**Luma is Home** is gone and **Home is pinned** is in its place, under **Buttons → Home button**. It
no longer waits for Luma to be installed, because there is always a home. The default is on, and
turning it off gives you the old shape back: no pinned row, and your launcher listed like any other
app.

The bindings picker still names packages. A picker that renames its own options is a picker you
cannot search.

### Smaller things in the same change

- **The list is still exactly as tall as the screen allows.** The pinned row and its heading are
  counted into the same arithmetic the header, the hint and SYSTEM SWITCHER already were — a row
  this measurement forgets is always the app furthest back, on a list that refuses to be scrolled
  by finger.
- **"Nothing yet" comes back.** The empty-switcher line was asked of the whole list, which is never
  empty now. It is asked of the recents instead, so a fresh install still says why it has nothing to
  show.
- **Holding the pinned row** opens App info for whatever home resolves to, the same as any other
  row, and does nothing at all when it resolves to no package.
