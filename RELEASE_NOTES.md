## BrightControl v4.21 — NEXT UP looks 18 hours ahead

**Less on the lock face.** The NEXT UP line under the date used to show whatever BrightNotebook had in the next 48 hours. That is the provider's window, and it is right for a calendar; on a lock face it meant Thursday's meeting sitting under Tuesday's clock. The line now draws only what starts within 18 hours — the rest of today and first thing tomorrow. At 10 pm it reaches a 9 am start; at noon it reaches nothing past bedtime.

### How

The cutoff lives on the face, not in the Notebook. `NextUpText.within()` is checked on every repaint, and the face already repaints on the minute tick, so an entry parked just past the edge appears the minute it comes inside — no second query, no provider change, and the Notebook keeps its 48-hour contract for anything else reading it.

Nothing else changed.
