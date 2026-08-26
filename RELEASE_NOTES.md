## BrightControl v3.65 — a box for every app's notifications

LightOS shows a new notification as a line in its own list and nothing else, so something arriving
while you are in an app is something you find out about later. **Banners** are the box over the
top: the app, who it is from, and two lines of it. Tap it to open the app, swipe up to send it
away, or leave it and it goes on its own.

It is BrightChat's heads-up box, which is the right shape for this phone — solid black, one
hairline outline, square corners, no icon, no timestamp, no buttons and no animation — pointed at
every app rather than at one. **It never buzzes.** The app that sent the notification already did,
and two buzzes for one message is worse than none.

**Off until you switch it on**, under the new Notifications section. Everything else in this app
changes what a *key* does; a window that appears over what you were reading is a larger promise
than a remapped button, and it should be one you made.

**No new grant.** It reads the shade through the notification listener the lock face already needs,
and draws through the accessibility window the lock face already uses. `SYSTEM_ALERT_WINDOW` is not
involved.

**It can wake the screen — with a wake lock, never an activity.** BrightChat wakes the panel with a
`showWhenLocked` activity and this app must not: that flag marks the keyguard *occluded*, and an
occluded keyguard stops arming this phone's power-button fingerprint reader. That is the v2.5 and
v2.6 regression where the thumb stopped unlocking the phone. So the drawing and the waking are two
separate things here — the window sits above the keyguard without touching it, and the panel comes
on by itself.

**It waits two seconds.** BrightChat learned this the expensive way: a text read on a Mac lit the
phone next to it anyway, because the message arrives before the news that you have already seen it.
An app that decides an alert is stale cancels its notification, and a cancelled notification is a
key that has gone — so waiting and re-checking reproduces that fix for every app at once, with
nothing asked of any of them.

**Nothing permanent gets a banner.** A recording, a download, a navigation. Something that has been
in the shade for an hour is not news, whatever the lock face is set to show.

**One list, in one place.** *Apps never shown* and *Permanent notifications* moved out of Lock
screen into Notifications. It is the same setting it always was and the lock face still reads it —
but a rule two features share should not live inside one of them, where the next change to the lock
face quietly breaks the other.

**BrightChat and BrightSports stand down.** Both draw a box of their own, and this one is drawn off
the very notification they post — so with everything switched on a message was one buzz and two
boxes. With banners on they are told to stop drawing theirs. Their buzz and their shade
notification are untouched, and turning banners off gives them straight back. Needs their next
release to take effect.


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
