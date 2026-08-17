## BrightControl v2.6 — The lock face, actually working

**Three things were wrong with v2.5. Two of them were the same bug. The third was a wrong
assumption about the hardware, and it is the one worth reading.**

### Your thumb never worked, and it could not have

v2.5 said WAITING FOR FINGERPRINT and then waited forever. The reasoning behind that line was
that the keyguard keeps listening to the sensor while something is drawn over it — which is how
you unlock out of the camera-from-lock shortcut on other phones.

That is true only for phones with an **under-display** sensor. The LPIII's is in the power button.
AOSP arms the keyguard's fingerprint listener while it is occluded in exactly three cases: an
under-display sensor, a dreaming device, or a bouncer that is already showing. A side sensor with
a quiet face over the lock screen is none of them, so the press went nowhere. There is no flag, no
permission and no API that changes this — it is the framework, not a setting.

So every route out of this screen now goes through the bouncer, because raising the bouncer is
what turns the sensor back on:

- **Swipe up, or tap.** The code screen comes up and **your thumb works there** — it is still the
  quick way through, it just needs one gesture first.
- **Settings → LOCK SCREEN → Unlock → ON WAKE**, if you would rather not have that gesture. The
  bouncer is raised the instant the phone wakes, so the thumb works immediately — at the cost of
  the stock screen being what you look at, with this one behind it.

One extra gesture against one stock-looking screen. There is no third option, and pretending
otherwise is what v2.5 did.

The line on the face now says **SWIPE UP TO UNLOCK** / *then your thumb, or your code*, which is
what actually happens.

### A correct PIN left the face on screen

The activity was watching for `ACTION_USER_PRESENT` itself. But raising the bouncer over an
occluding activity **stops** that activity — so `onStop` unregistered the receiver, and the
broadcast for the unlock arrived while nothing was listening. The face stayed up over an open
phone, which is the worst thing on the list.

Noticing the unlock has moved to the accessibility service, which the system binds and never
stops and never freezes. It takes the face down and then launches. Three more nets underneath it:
a `KeyguardLockedStateListener`, a receiver on the activity registered for its whole life rather
than just the visible part, and a re-check every time the face returns to the front.

### The stock screen flashed before ours

Because LightOS's lock screen is an activity, and it comes over **as** the screen goes off — the
same fact this app already relies on to work out what to resume. Ours was being started in the
same instant, which means started underneath it. On waking you saw LightOS first and ours a moment
later, arriving over the top.

The face is now raised **900 ms after** the screen goes off, by which time LightOS's lock screen
has finished coming up and there is nothing left to race, and it is re-asserted on screen-on in
case anything came over it while the phone was down. The screen is off for all of that, so the
delay costs nothing.

### Unchanged

It still is not a lock screen and still cannot be. The real keyguard is underneath, still holds
the device, still takes your code. The failure mode of everything here is still "you see the stock
lock screen, working".
