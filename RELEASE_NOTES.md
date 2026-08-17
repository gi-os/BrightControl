## BrightControl v2.9 — It comes down, and it opens something

### The face staying up and nothing opening were one bug

Both are downstream of the same failure: nothing noticed the phone had been unlocked. The face is
taken down and the resume is fired from the same moment, so when that moment never arrives you get
exactly the two symptoms — a lock screen over an open phone, and no app.

Two signals were already meant to catch it. `ACTION_USER_PRESENT`, and the keyguard's own
`KeyguardLockedStateListener`. Between v2.5 and v2.8 the face has now twice been left on screen
because the signal in play did not arrive, so there is a third one that cannot be missed: while
the face is up, the service asks `KeyguardManager.isDeviceLocked` three times a second. That is
not a notification that can go astray — it is the state itself. One binder call per tick, only
while the face is showing, stopping the instant anything takes it down.

And `hide()` had a second bug waiting: it dropped its reference to the window *before* asking the
window manager to remove it. A removal that was refused would have left a full-screen window on
the phone with nothing holding a handle to it — a lock screen you cannot get rid of without a
reboot. It now drops the handle only once removal has succeeded, and retries with
`removeViewImmediate`, which cannot be deferred.

### Unlocking into Luma

**Resume apps** and **Otherwise open** now live in the LOCK SCREEN section. They were previously
shown only when the *home button* was bound to Resume — so it was possible to turn the lock face
on and have no way anywhere in the app to say what it should open, which is why unlocking landed
on LightOS.

- **Unlocks into** — the apps an unlock is allowed to bring back. Sleep in one, unlock, it returns.
- **Otherwise open** — where an unlock goes when there is nothing to bring back, which is most of
  the time. **Point this at Luma.**

Unlocking also writes what it decided into the key log — `unlock → roll`, `unlock → not on the
list · fallback`, `unlock → nothing slept · fallback` — because "it didn't open anything" has four
possible causes and from the phone they look identical.

### The face itself

- **Signal bars instead of the carrier name.** "T-MOBILE" is nine characters that never change and
  do not tell you whether a message will arrive. Cellular strength needs a grant
  (`adb shell pm grant com.gios.lightcontrol android.permission.READ_PHONE_STATE`); without it the
  bars are drawn as empty outlines rather than at zero, because "not known" and "no signal" must
  not look the same. Wi-Fi needs nothing.
- **Smaller top bar** — `superfine` rather than `fine`.
- **Smaller prompt.** PRESS THE POWER BUTTON is a caption saying the sensor is live, not a control
  to press; at `button` size it shouted over the clock. It is `detail`, and dimmed.
- **The date reads as words**: "Sunday, August 16" instead of SUNDAY 16 AUGUST. Tracked all-caps is
  right for a status bar and wrong for the one thing on the screen that is read as a sentence.
