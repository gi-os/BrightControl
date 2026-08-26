## BrightControl v3.80 — a deadline that killed a working bond

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
