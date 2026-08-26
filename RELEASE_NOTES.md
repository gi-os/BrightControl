## BrightControl v3.48 — GRANT ALL says what it is doing, and stops when there is nothing to talk to

**Nine grants, and a dead socket costs each one a twelve-second reconnect plus three probes before
it fails.** Run as one batch with the result printed at the end, that is over two minutes of
greyed-out buttons above an empty log. It reads exactly like a button that did nothing — and it was
reported as one, which is fair, because from the outside there is no difference.

Each line is printed the moment it is known now:

```
1/9  Brightness (WRITE_SETTINGS) — OK
2/9  Overlay (SYSTEM_ALERT_WINDOW) — OK
3/9  Color (WRITE_SECURE_SETTINGS) — FAILED
stopped — the connection is down, so the rest would fail too
```

**And it gives up the first time a grant fails with the connection actually down.** One dead socket
cannot usefully be reconnected nine times: if the reconnect inside the first failure could not get
one, the eight after it will not either. What was not attempted is counted rather than left to be
inferred from a short list.

**"GRANTING…" could also be a phase nobody could leave.** The automatic pairing runs the same nine
grants, and both `PAIRING…` and `GRANTING…` disable the PAIR button — deliberately, since two
pairing attempts at once are worse than one. But there was no way *out* of those phases if the work
behind them died with the process, and a phase nobody can leave is a button nobody can press. The
grants there publish one at a time too, so the screen fills in as they land, and a phase that
outlived its work is released when the screen is opened.

If the connection drops part-way through that batch it now says so and points at GRANT ALL, instead
of sitting on GRANTING… until something else happened to notice.
