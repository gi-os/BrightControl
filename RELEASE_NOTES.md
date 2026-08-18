## BrightControl v2.15 — Apps that own the whole wheel

**A new built-in rule for apps whose wheel *press* is a control of their own, not just their
turns. BrightRecorder is the first, and it is why this exists.**

The scroll-aware rule passes an app's bare turns through and keeps the click for this service. That
is right for nearly everything — the click is the torch, phone-wide, and an app that scrolls with
the wheel still wants a flashlight. It is wrong for an app where pressing the wheel *is* a control.

BrightRecorder is a tape recorder: turning the wheel scrubs the tape and pressing it plays and
stops. Under the scroll-aware rule its press never arrived — the torch came on instead and the key
was consumed, with nothing the app could do from its side, because this service decides first.

So there is now a short list of applicationIds that own the whole wheel, turns and press alike, and
they resolve to the same hands-off treatment Light's own tools get: every key reaches the app
untouched, and the global turn mode does not apply either. It is consulted before the scroll-aware
list, because `com.gios.brightrecorder` sits inside `com.gios.` and would otherwise be claimed by
the weaker rule.

The cost, and it is deliberate: **while such an app is in front, the wheel click and the camera
button do nothing of ours** — no torch, no camera. That is the correct trade when the app in front
is a tape recorder and the wheel is its transport, and it is the same bargain already struck for
LightOS's own screens. Nothing changes for any other app: the rest of the `com.gios.` family still
gets turns-through-with-our-buttons exactly as before.

Doing this by hand was already possible — the per-app rule **Off** has always meant this — so this
release is about it being right without anyone having to find the setting.

### Under the hood

The built-in table is now a pure function of the applicationId, split out from the stored-preference
lookup so it can be tested on the JVM. Five new tests, because three prefix lists overlap here and
the order they are consulted in *is* the behaviour: that a whole-wheel app is left alone entirely,
that the rest of the `com.gios.` family still only gets its turns, that Light's own software stays
hands-off, and that the phono fork keeps its bindings despite its Light-looking id.
