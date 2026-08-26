## BrightControl v3.81 — a deadline that killed a working bond, and a connect with no deadline at all

```
setPairingConfirmation true
RESULT gave up in state BONDING (request was answered)
Killed
```

That `Killed` is this app. The pairing confirmation had gone through, the ring and the phone were
exchanging keys, and the 45-second command deadline closed the socket underneath them.

A deadline exists to stop a command that is **stuck**, and a bond in progress is the opposite of
stuck. The slow-command allowance is two and a half minutes now, and the helper it runs is given 55
seconds of its own with more granted while the state says BONDING — so the timeout can only fire on
something that has actually stopped moving.

Everything else about the deadline stays: 20 seconds for ordinary commands, one budget across
attempt and retry, and STOP still ends a command instantly by closing the socket. This changes only
the one command that is *supposed* to take its time.

**And the opposite mistake, from v3.78: a connect with no deadline at all.** The port-off-the-screen
fallback calls `connectPort`, which opens a socket — and a TCP connect to a port nothing is listening
on does not fail quickly, it retries SYNs for minutes. That is the run that sat at `RUNNING 224s`
with nothing to show for it.

It gets six seconds now, on a thread the app abandons if it overruns — the same discipline every
command has had since v3.49. A port that is listening answers on loopback in milliseconds; anything
slower than six seconds is not a slow success, it is a stale port.

Also fixed: a test of mine was asserting the old 24-second budget in the built command, which is why
v3.80 never shipped. It asserts the shape and the pinned parts now — the number has already moved
once and will again.
