## BrightControl v4.3 — the system switcher button gets a real tap target

**A report said the app switcher would not register the tap on the system switcher, and asked for a wider touch area.**

The bottom of the switcher ends in a single line — "SYSTEM SWITCHER" — the way out to the platform's own
recents. It was drawn as a plain line of superfine type with a gap above it and nothing below, so the only
place a tap registered was the thin strip of the text itself. A thumb reaching for the last control on the
screen landed just under it instead, and the tap fell on the list's background — where "anywhere that is not
a row" means close the switcher. The button that was meant to open another screen was quietly dismissing the
one you were on.

The button now carries a full line of air below the text as well as above it, so its tap target is roughly
twice as tall and sits where the thumb is actually going. The row budget accounts for the extra height too,
so widening the target spends one row of capacity instead of pushing the furthest-back app below the fold.

Fixes [light-reports#205] — the system switcher row ignored taps aimed at the bottom edge of the screen.
