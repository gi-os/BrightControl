## BrightControl v3.61 — STOP reaches the slow part, and a silent reader says what it saw

**STOP was checked everywhere except where the time goes.** `runVia` and the command runner both
honoured it; `ensureAlive` did not — and with nothing to connect to, that is where a batch lives:
twelve seconds of mDNS discovery per step, nine steps, nearly two minutes of a button that has
already been pressed. The check now sits at the top of it, between each probe, and immediately in
front of the lookup itself. Discovery cannot be interrupted once it has started, so the worst a stop
costs is the one lookup already running — and the button says `STOPPING…` while that finishes,
because a button that has heard you and is waiting is a different thing from a button that ignored
you.

**And the reader going quiet is no longer indistinguishable from working.** v3.59 stopped it
reporting the Wireless-debugging list as an unreadable dialog, which was right — that screen has
never carried a code. But it took the signal with it: a ninety-second window ending in silence could
mean the dialog never opened, or opened in a window nobody looked at, or that nobody ever got there.

So the window now remembers the heading of every screen it read, and says so when it expires:

```
Could not find the pairing dialog in 90 seconds.

windows seen while waiting:
Wireless debugging
Developer options
Pair device with pairing code
```

Three lines that separate three completely different problems. And if the list comes back empty —
*"no Settings window was read at all"* — the answer is not the reader at all, it is that the pairing
helper is not running, which is a switch in Accessibility rather than a bug in here.

**Where this stands, plainly:** there is still no pairing on disk, so every grant and every relayed
request will keep reporting a connection that is gone, correctly. The one action that can change that
is PAIR AUTOMATICALLY. If it fails again, this release makes the failure say which of the three
things it was.
