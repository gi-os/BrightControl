## LightControl v1.7 — The shake asks instead of interrupting

**Shaking the phone no longer throws a sheet over what you were doing. It puts a small
"SEND ERROR?" chip in the bottom corner, and only tapping that opens the report.**

The first version got the shape of the question wrong. A shake is a gesture the phone can
misread — and the cost of misreading it was paid every single time, because a full-screen sheet
landed on top of whatever you were reading to ask about a problem that may not have existed. On a
3.92" panel that is a bad trade against a report that might not be real.

So the offer is small, it sits out of the way, and **silence is an answer**. Ignore the chip for
four seconds and it fades. Nothing is lost by ignoring it: an unsent crash log stays on disk and
is offered again on the next launch, and a failure the app noticed itself will not ask again for
an hour. Only the tap costs anything, and only the tap opens the sheet.

A crash offer stands for eight seconds rather than four. It is the one offer that cannot be
reconstructed from nothing if you miss it.

The chip is drawn in its own window rather than placed in the layout, so it lands in the same
corner in every app regardless of how that app is built, and it cannot swallow a tap meant for
what is underneath it.

Nothing else about reporting changed — same note field, same queue-to-disk-first behaviour, same
gesture tuning.
