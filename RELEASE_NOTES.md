## BrightControl v4.11 — the switcher gets its screen back

One fix, and it is the app switcher: on the phone it had quietly become two apps and a Home row.
It is a screenful again — the current app, three recents and the pinned Home on the LPIII — and
this time there is a test that fails the build if it ever thins out again.

### What regressed, and when

No single release broke it, which is exactly why nothing caught it. The switcher budgets its rows
from the panel's height minus everything else it draws, and between v3.97 and v4.3 the
"everything else" grew three times: v3.97 pinned Home to the bottom and added the HOME heading
over it, v4.1 reshuffled, and v4.3 widened the SYSTEM SWITCHER button's tap target with a full
line of air below it. Every subtraction was correct and defensible on its own. Together, on a
1080×1240 panel, they left room for 2.9 rows — below the three-row floor, so the floor clamp
silently *became* the capacity, and the pinned Home row and the current-app head (v4.3) were then
paid out of it. Two apps remained. No arithmetic was wrong; the sum was, and nothing was watching
the sum.

It surfaced today because v4.7 fixed the wheel hold on LightOS's own screens
(light-reports#136, dead since Aug 27) — the gesture came back, and what it opened onto was the
list as it had quietly become.

### The fix

The list is the tenant; the furniture pays rent. Air only — nothing is removed, no type size
changes, the rows look exactly as they did:

- rows carry half a grid unit above and below instead of three quarters (their height was always
  the icon's, the air just framed it),
- the way-out button keeps a real tap target — one unit each side, still two and a half times the
  bare text strip that used to miss — instead of two,
- the HOME heading's gap tightens to a unit total,
- the hint drops to one unit of air,
- and the column no longer keeps three grid units of empty margin under the hint, where there is
  nothing but the edge of the glass.

That takes the furniture from 798px of a 1240px panel to 538px, and the budget answers five rows
with Home pinned: the app you are in, three recents, Home. Bigger system text sheds rows
gracefully and never goes below the floor.

### The arithmetic can no longer rot in silence

The row budget moved out of the view into `SwitcherFit`, a pure object with no Context in it, and
the views take their padding from the same named constants the budget subtracts — the layout and
the prediction cannot drift apart one number at a time anymore. `SwitcherFitTest` holds the
answer against the LPIII's real panel: five rows pinned, five unpinned, everything it promises
proven to fit inside the glass, and a floor that stays a floor. The next padding that costs a row
fails CI with a message instead of thinning a screen nobody is measuring.

## BrightControl v4.10 — the weather, in one dim line

One addition, and it is a sentence: the lock face now shows today's weather, under the status
bar where the alarm and the battery already live.

### 72° · CLEAR · H 81 L 64

Current temperature, the sky in a word, today's high and low — and `· RAIN 60%` appended only
when the chance of rain reaches 40%, because a line that mentions every 10% drizzle is a line
you stop reading. Fahrenheit on the face; the conversion is a pure object with its own tests,
rounding half-up.

### No new permission, no location, no radio

The numbers come from LightFog's new weather provider (`com.gios.lightfog.weather`, from
LightFog v0.13). LightFog is the one app on this phone allowed to know where it is, and what it
serves here is weather only — no coordinate crosses the boundary, and the coordinate behind the
numbers was rounded to about a kilometre before it ever left the phone. This app asks a
ContentProvider and draws a TextView; that is the whole of its involvement.

### Absent, never broken — and never a poll

No LightFog, an older LightFog, an empty cache, any failure at all: the line simply is not
there. A reading older than three hours is treated the same way, because stale weather
presented as current is worse than none. The query runs when the face shows and when the screen
wakes, on its own thread, exactly like the directions row and the calendar line — and never on
a schedule while the phone is dark. The line is not touchable and not dismissable; it is a
status glyph in the shape of a sentence.
