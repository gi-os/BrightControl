## BrightControl v3.66 — failures report themselves

*"Have it auto send errors instead of offering to send errors, since that is easy to click off of."*
Right, and the offer was worse than easy to dismiss — it appeared **while you were in the middle of
the thing that had just failed**, and one tap outside it threw away the only account of what went
wrong that anybody would ever have. Several evenings this week were diagnosed by reading a logcat
over your shoulder instead, for exactly that reason.

**So a failure the app noticed itself is now filed as it happens.** No sheet, no dialog, no decision
to make. There was never anything to ask about: a shake report needs *you* — which symptom, in your
words, on which screen — but a detected failure has already written its own account of what it tried
and what came back. A dialog on top of that adds only a way to lose it.

It is not silent. A line appears at the bottom for four seconds:

```
Reported: could not run "appops set … WRITE_SETTINGS allow" on the phone's own shell
```

or *"Saved to send later"* on a build with no token, since the queue goes out on the next launch
either way. It takes nothing away from what you were doing, which is the whole difference from the
sheet it replaces.

**Shaking still opens the sheet**, because that report is worth typing into — the hand-written note
on #61 ("pairing box present but numbers within not detected") is what found the reader bug.

**And it can be switched off.** Diagnostics → *Send failures without asking*. Off restores the old
behaviour exactly, with the offer and the tap that discards it.

Tonight's reports, for the record: #75 and #76 both carry the new message — *"the phone accepted the
connection and then refused to run anything, which means it no longer trusts this app's pairing"* —
so that diagnosis is now the phone's own words rather than a theory. FORGET THE PAIRING, then PAIR
AUTOMATICALLY.
