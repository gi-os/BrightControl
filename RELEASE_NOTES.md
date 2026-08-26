## BrightControl v3.64 — a pairing the phone has stopped trusting, and STOP that works more than once

**STOP worked once and then said STOPPING… forever.** The flag is cleared when a *request run*
starts, and the ADB screen's buttons — GRANT ALL, NFC, Shizuku, the command box — are not request
runs. So the flag survived into the next press, and the second GRANT ALL came up with its STOP button
already reading STOPPING… and already disabled. Every button on that screen clears the last stop
now, which is the only rule that keeps this honest.

**And report #73 named a state that has been hiding behind "Stream closed" all evening.** Look at
the difference:

| report | detail |
|---|---|
| #70–#72 | the connection is gone and could not be picked back up |
| **#73** | **Stream closed.** |

`Stream closed` as the *final* answer means the reconnect in front of it **succeeded** — the daemon
accepted the connection — and then refused to open a shell stream, twice. A connection that is
accepted and then useless is not a connection problem at all. It is **a key the phone no longer
trusts**: the certificate and private key live in this app's own files and survive everything,
including the phone's side of the pairing being cleared by a reboot or by the wireless-debugging
toggle going off and on.

Two things follow.

**It says so now.** A stream failure on a connection that is up no longer reports "the connection is
gone" — it says the phone accepted the connection and then refused to run anything, which means the
pairing is no longer trusted. Sending somebody to fix the connection was sending them to fix the
wrong thing.

**And there is a button for it.** The ADB screen shows whether a pairing is held at all, and
**FORGET THE PAIRING** deletes the key and certificate so the next pairing makes new ones. That
deletion matters: pairing again while those files exist can hand the daemon the same key it has
already rejected, which produces a pairing that looks successful and streams that die exactly as
before — which may well be what has been happening.

**So the order to try is: FORGET THE PAIRING, then PAIR AUTOMATICALLY.** Not one or the other.
