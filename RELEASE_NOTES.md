## BrightControl v3.58 — the right edge opens the switcher, and the home flicker is found

### Swipe in from the right for the app switcher

The left edge went back in v3.56. The right edge now opens the recent-apps list — the same window
the home button puts up on a double press, from the same one place in the code, because a gesture
that built its own list would be a second answer to "which apps" and a second way to fail.

Its own toggle, off until you switch it on, alongside the back gesture in a section now called
**Edge gestures**. Both edges share the width, the trigger distance, the indicator and the per-app
exclusion list: nobody wants their left edge to be a different size from their right, and an app that
draws its own controls at the screen edge usually does it at both.

The indicator says APPS rather than BACK, and its glyph is two overlapping cards rather than a
chevron. A chevron would promise a direction, and the right edge does not go anywhere in
particular — it produces a list.

One implementation note worth keeping: the strip is what opens the window, and the window going up
takes the strip back down, so the refresh is *posted* rather than called. A synchronous one reached
`removeView` on the very view whose touch listener was still dispatching the stroke that asked for
it.

### The home-button flicker: it was never about timing

> "if 'tap' is set to 'home' and 'hold' is set to 'lightOS', this causes a flicker between home
> screen and toolbox if you press home button too quick after unlock… but it does retain the app
> switcher function"
>
> "if both 'tap' and 'hold' for home button are set to 'lightos' it disables app switcher, but fixes
> the flicker"

Both halves of that are one cause each, and the "too quick after unlock" detail is what gives the
first one away.

**Shadow mode was adding a second destination.** When the home key cannot be swallowed — an app that
is hands-off, and LightOS's own screens are hands-off — BrightControl runs in *shadow*: it consumes
nothing, LightOS receives the entire press, and the tap binding fires on top. That was written when
the tap could only be home, on the reasoning that firing home twice over is invisible.

It is not invisible on LightOS. **LightOS does not read a home press as "start the home
activity."** It reads it as its own navigation — back to the idle face, or on into the toolbox — so a
shadowed press produced LightOS's answer *and* ours, which is a `CATEGORY_HOME` intent to whichever
launcher is default. Two destinations for one press, racing. And the reason it only happens right
after an unlock is that this is exactly the window where the front app is still LightOS: LightOS
holds the HOME role and comes forward the instant the keyguard goes. A second later the front app has
settled, nothing is refused, the key is consumed properly, and there is one destination again. The
one-second lock-face delay had nothing to do with it.

So a plain **Home** tap is no longer fired in shadow while LightOS is in front. LightOS has the press
and home is what it does with it. Every other action still fires — a tap pointed at an app or at
Resume is a destination LightOS was never going to reach — and the double press still opens the
switcher, because that is counted before any of this.

**And the switcher was not broken by setting tap to LightOS either.** Pointing the tap at LightOS
makes the key consumable, so `LightOsHome` succeeds, and succeeding starts a *visit* — the state
where home belongs to LightOS so you can walk through its menu, ended by pressing home twice. The
visit then claimed the double press for its own exit and never asked the switcher. Two gestures
spelled the same way, and the more specific one lost.

Now the double press during a visit opens the list, and ends the visit either way: a window at layer
31 is in front, so there is nothing left to visit. The tap is the fallback for a list that could not
be shown, which is what it was before.

Either config works now. `tap = home, hold = lightOS, double = switcher` gets all three with no
flicker; so does `tap = lightOS`.

### Also

- Four more JVM tests on the gesture, all for the right edge: it arms on a leftward drag, it is not
  armed by a rightward one (without the sign both strips would arm on a stroke heading off the screen
  they live on), it cancels a scroll the same way, and it counts the same distance whichever way the
  thumb went. Thirteen in total.
- `BackSwipe` is `EdgeSwipe`, one class with a side. The stored settings keys are unchanged, so
  nothing you had configured moves.
