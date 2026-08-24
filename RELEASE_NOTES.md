## BrightControl v3.13 — the two apps you would try first were the two you could not change

**"Unable to toggle filter options for BrightChat or Roll."** Tapping either row in Color →
Per-app rules did nothing at all. Not a wrong value, not a value that failed to stick — the row
redrew exactly as it was, every time, while every other app on the list cycled normally.

Both of them ship as PASS. That is deliberate: Roll and BrightChat hold `WRITE_SECURE_SETTINGS`
and drive the daltonizer themselves, so a rule set here would be a second writer fighting the
first, and PASS is BrightControl declining to join the argument.

### Two correct rules that cancel each other out

The list stores nothing when a tap lands back on the app's preset, so that changing the preset
table later still reaches an app someone once tapped through. And AUTO — a stored nothing —
resolves *through* that same preset table rather than flatly meaning mono.

For an app whose preset is PASS, those two meet head on. The step after PASS is AUTO. AUTO clears
the override. Clearing the override resolves back through the table to PASS. The preference was
written, the state map was updated, the row recomposed — and landed on the value it started from.
From the phone that is a dead row, and the only two apps it could happen to are the two the
feature exists for.

### The step is now chosen by where it lands

`Policy.nextColorRule` works out what to store from the resolved state and the preset together,
and skips any candidate whose outcome is the state the row is already showing. Roll and BrightChat
now cycle PASS → COLOR → MONO → PASS: three states rather than four, because for them AUTO and
PASS are the same phone. Apps with a Color preset and apps with no preset are untouched — for
those, AUTO and the preset look different, so nothing gets skipped.

It lives in `Policy` with the rest of the resolution rules rather than in the screen, so the
property that matters — every tap changes what the row resolves to, for every preset — is a unit
test instead of something to be checked by hand on a 3.92" panel.

Fixes [light-reports#40] — unable to toggle filter options for BrightChat or Roll.
