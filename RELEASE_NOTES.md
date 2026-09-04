## BrightControl v4.18 — the Send log row stops filing the same log twice

The Color screen's "Send log" filed the same log nine times in a single four-second sitting.
All nine arrived titled **per-app color: 1 held, 0 overwritten**, which is the *success* state —
one write landed and stayed — so the color feature itself was never broken. The row that broke
is the one that sends: tapping it flips its label to "Log sent" but left the tap alive, so
every further press composed and queued the same log again, and nine presses meant nine
identical issues.

### What changed

`ColorScreen`'s send row is inert once sent. `onClick` is now `if (sent) null else { … }`, so
after the report is queued the row carries no click at all — the same "state, never a
transition" idea the color feature runs on. Only the Clear row below re-arms it, and clearing
is exactly what you do before filing a fresh log anyway.

Fixes [light-reports#271] — the Send log row filed nine copies of one healthy color log in four
seconds.
