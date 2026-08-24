## BrightControl v3.9 — the log the screen was not reading

**Color → what happened said "Nothing applied yet" while six rules were sitting in the log**, and
Send log filed an issue whose title said nothing had ever been applied above a body listing all
six. Two separate defects in the diagnostic, and between them they made the one screen built to
settle this bug testify against itself.

### The screen was showing a snapshot from before you left it

The log and the live filter pair are both state that lives outside the composition — the log is
written by the key service as you switch apps, the pair by whoever wrote the daltonizer last.
Both were read once, when the screen was first composed, and never again. That is correct right
up until you leave the screen, and leaving the screen is the entire way this feature is
exercised: you open a Color app, come back, and the composition survived the trip, so what you
are looking at is the phone as it was before any of it happened.

Both are re-read on every resume now. The title is also written from a fresh read at the moment
Send log is tapped, because the evidence block always read the log straight out of preferences
while the title was written off the snapshot — so the two halves of the report described
different phones.

### Two of the six lines said LOST and nothing had been lost

`LOST` meant no more than "the pair does not match 900 ms later", and two apps swapping inside a
second produce that out of two writes that both worked: app A's rule is written, app B comes
forward and states its own, and A's read-back lands after B's write and reports A's values as
overwritten. Since the issue title is counted off these outcomes, an ordinary walk through three
apps filed itself as a fault.

A mismatch that is exactly what the front app is asking for now is `superseded` — that write was
correct and is simply no longer the question. Anything else is still `LOST`, which is the case
worth having: values nobody in this app ever asked for, which is what LightOS writing after us
looks like.

**Still open:** whether a Color rule that reads back `ok` actually puts color on the panel. Every
line in that report held, and the phone was still grey — so the write lands, stays, and is
ignored by whatever paints. That is a different bug and it needs the device.

Fixes [light-reports#37] — per-app color: nothing was ever applied.
