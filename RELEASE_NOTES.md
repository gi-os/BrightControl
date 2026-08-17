## BrightControl v2.7 — The thumb works

**The lock face stopped being an activity, and that is the entire release.**

### What was wrong

`showWhenLocked` does not mean "draw over the lock screen". It means **occlude the keyguard** —
and an occluded keyguard stops listening to the fingerprint sensor unless the sensor is under the
display. The LPIII's is in the power button. So v2.5 and v2.6 switched the sensor off by existing,
and no flag, permission or API was going to change that.

v2.6's answer was to make you raise the bouncer first. That was working around the wrong thing.

### What it is now

A window, added by the accessibility service that has been in this app since v1.0.1. From AOSP's
own `getWindowLayerFromTypeLw`:

| window | layer |
| --- | --- |
| `TYPE_APPLICATION_OVERLAY` — the brightness readout, the volume HUD | 11 |
| `TYPE_NOTIFICATION_SHADE` — **the keyguard** | 17 |
| `TYPE_KEYGUARD_DIALOG` — the bouncer | 19 |
| **`TYPE_ACCESSIBILITY_OVERLAY`** — this | **31** |

Which also explains something that had never been explained: the brightness readout has never
appeared over the lock screen, because at layer 11 it is underneath it.

Because this is a window and not an activity, **the keyguard is never occluded**. As far as
SystemUI is concerned the lock screen is showing and visible exactly as it always is, its
fingerprint listener is armed exactly as it always is, and the press on the power button unlocks
the phone exactly as it always did. We are painting over the top and nothing else.

The face says **PRESS THE POWER BUTTON**, and this time that is true.

### Three more problems that went away on their own

- **The flash is gone, and so is the 900 ms delay** v2.6 needed to tune. A window at layer 31 is
  above LightOS's lock screen whenever it arrives, so there is nothing to race.
- **No overlay appop.** No activity means no background activity start, so `SYSTEM_ALERT_WINDOW`
  is not part of this feature at all any more.
- **Nothing to get stuck in.** The window holds no key focus, owns no task and has no back stack.
  Every key — power, wheel, camera button — goes exactly where it went before. If the code throws,
  the lock screen is right there underneath, untouched.

### Reaching the keypad

Tap the face. That is all a tap does: hides this window, revealing the real lock screen already
behind it. It never asks the keyguard for anything. Once tapped it stays out of the way until the
next time the phone sleeps.

### What you need

Just a screen lock set — it refuses to draw with no PIN, because "press the power button" over
nothing would be a lie. Notifications still want the listener grant:

```
adb shell cmd notification allow_listener com.gios.lightcontrol/.lock.LockNotifications
```

The `SYSTEM_ALERT_WINDOW` line is no longer needed for the lock face. Keep it — the brightness
readout and volume HUD still use it.
