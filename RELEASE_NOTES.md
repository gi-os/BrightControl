## BrightControl v2.5 — A Light lock face, and unlocking straight into an app

**The screen you see while the phone waits for your thumb is now yours, and letting go of it
opens the app you were in rather than the dashboard.**

Settings → **LOCK SCREEN → Light lock face**. Off by default.

What it shows: a top bar with the carrier or Wi-Fi, the next alarm and the battery; the time and
the date; the notifications currently waiting, four at a time with a count for the rest; a picture
of your own if you choose one. Then one line at the bottom saying **WAITING FOR FINGERPRINT**, and
under it, *or tap to enter your code*.

### It is not a lock screen, and this matters

Nothing sideloaded can replace the keyguard — that needs a system signature or root, neither of
which is on offer. What this is instead is a window marked `showWhenLocked`, which the framework
allows to *occlude* the real lock screen. The real one is still there. It still holds the device,
it still has your fingerprints enrolled, and it is still the only thing that opens the phone.

Two things follow, and they are worth knowing before you turn it on:

- **Your thumb is doing exactly what it did before.** The keyguard keeps listening to the sensor
  while it is occluded — the same mechanism that lets you unlock out of the camera shortcut — so
  the press dismisses the real screen underneath this one. There is no fingerprint code in this
  app and there must not be: a prompt of our own would say yes and leave the phone locked.
- **The code entry is still AOSP's, and still looks like it.** A keypad drawn here could not
  unlock anything. Tapping the face asks for the real bouncer, which cannot be restyled. So you
  will see the stock screen when the thumb doesn't take — after a reboot, after four hours, on a
  wet morning — and that is the one place this face doesn't reach.

Nothing about the phone's security changes. If you can unlock it today, you can unlock it with
this on; if you couldn't, this doesn't help.

### Unlocking opens the app

An unlock now goes wherever **Resume** would have sent the home button — the same list, the same
fallback, the same spend-on-use. Sleep in Roll and the thumb opens Roll. Sleep anywhere else and
you get whatever you set *Otherwise open* to, which for most people is Luma.

Deliberately the same rule rather than a second one. The list of apps and the fallback already
exist under **Home button opens → Resume**, and an unlock that went somewhere else would be a
second thing to learn and a second thing to get wrong. The face says which app it is about to
open, in words, before you touch it — read from the same snapshot the service acts on, so the
label cannot promise one thing and the unlock deliver another.

### Two grants, and what happens without them

- **Overlay appop**, which the app already needed: `adb shell appops set com.gios.lightcontrol
  SYSTEM_ALERT_WINDOW allow`. Without it the face is never started at all — a background activity
  start on Android 14 is refused *silently*, so this is checked rather than attempted.
- **Notification listener**, only if you want the notifications:
  `adb shell cmd notification allow_listener com.gios.lightcontrol/.lock.LockNotifications`.
  Nothing is stored and nothing leaves the phone; the list is rebuilt from the live shade and held
  in memory. Without the grant the row says NO GRANT and the rest of the face works.

### It turns itself off rather than misbehave

The same rule the home button has had since v1.0.21, for the same reason: the screen this draws
over is the one screen on the phone that has to work. It disarms, with the reason on the settings
screen, if there is no screen lock set (nothing to occlude, and the fingerprint line would be a
lie), if the overlay appop is missing, or if it fails to start three times running. Re-arming is
one tap.

And if it simply crashes or is killed, what is behind it is the stock lock screen, working. That
was the design constraint, not a consolation.

### Notes on the picture

Chosen with the system picker, held as a persistable grant rather than copied — this app should
not be sitting on a photograph you think you deleted. It is desaturated and dimmed 55% before it
is drawn, because the panel is greyscale and matte: converting deliberately means choosing which
greys, rather than letting the display choose, and it keeps a bright photo from swallowing the
clock.
