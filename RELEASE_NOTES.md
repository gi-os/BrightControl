## BrightControl v2.10 — Two defects in the unlock watch

v2.9 added a poll of `isDeviceLocked` as the signal that could not be missed, and got the
condition wrong in a way that only shows up overnight.

**The poll was tied to the face being up, not to the screen being on.** The face goes up when the
phone sleeps — so the loop ticked three times a second for every hour the phone spent on a bedside
table, in an accessibility service the system is not permitted to freeze. That is the exact shape
of the bug that has taken this phone down before.

It now starts on `ACTION_SCREEN_ON` and stops on `ACTION_SCREEN_OFF`, which is the few seconds
between picking the phone up and opening it — the only window it was ever meant to cover. Nothing
about how reliably the face comes down changes: while the screen is off there is nobody to unlock
it, and `ACTION_USER_PRESENT` and the keyguard listener are both still there underneath.

**The keyguard locked-state listener was registered and never handed back.** A listener outlives
the service that registered it, so every rebind — a settings change, an update, toggling the
service off and on — left another one calling `onUserPresent` on an instance that had already been
unbound. It is now released on unbind.

**The lock picture is decoded once and kept.** It was being re-decoded from the file on every
single sleep: a few megabytes of allocation, dozens of times a day, for an image that had not
changed. Cached against its URI, so choosing a new one still takes effect immediately.

Also hardened, none of it visible: the clock's tick receiver passes `RECEIVER_NOT_EXPORTED`
explicitly, and the notification list and the first paint are wrapped — a throw on the main thread
behind the lock screen is the phone appearing to freeze, which is not a failure mode this feature
is allowed to have.
