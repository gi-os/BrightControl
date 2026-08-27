## BrightControl v3.90 — three fixes to v3.89

### The volume strip is on again by default

v3.89 switched both volume settings off together, on one argument: a window drawn over other
people's apps is something to ask for rather than something to discover. That argument belongs to
only one of them.

The strip **reports**. It takes nothing, consumes no key, and on a phone whose alternative is no
volume UI at all, off by default means a press changes the level and nothing anywhere says so. That
is not a safer default, it is a broken one — and it reads as the feature having stopped working,
which is exactly how it was reported.

So **Show the level** is on again. **Tap to pick a stream** stays off, because that one is the half
the argument does apply to: it is the only setting in this app that lets a volume key be *consumed*.
Turn it on in **Volume** to reach the ringer and alarm levels, which Android and LightOS between
them otherwise make unreachable from the hardware.

If you switched the strip off by hand during v3.89, it stays off. The default only decides for a
setting nobody has touched.

### A page turn no longer flashes the volume strip

**BrightLibrary turns pages with the volume keys, and consuming a key means the system never sees
it** — no volume slider, and no change in volume either. But this app's key filter runs *ahead* of
the app in front, so every page turn was noted as a volume press, and the strip appeared over the
page it had just turned, reporting a level that had not moved.

There is no API that answers "did the app in front swallow that", and a list of apps that do would
be a list to maintain. Neither is needed: the strip exists to report a **change**, so a level that
did not change is not news. That rule has always governed the broadcast path — a repeated value is
dropped there — and now governs the path that reads the level back after a key.

Pressing up at maximum still shows the full bar. A key that answers nothing reads as a key that does
nothing, and the end of the scale is a real answer.

### The Wi-Fi ringer list repaints when you tap it

Tapping a network in **Ringer by Wi-Fi** did nothing visible. The rule was being saved correctly and
the screen simply never showed it, which is the worse of the two bugs it looked like.

The rows read each network's rule straight out of storage while drawing, and a stored value changing
is invisible to the UI — so the repaint had to be provoked by writing something the screen was
watching. The line that did it added the network to the list of networks seen, which for a network
already in that list produces a list equal to the one already there, and a value equal to the one
already there is not a change. Nothing repainted. The rules are held by the screen now.

### Also

The flag that suspends the strip while the selector is open is derived from whether the selector's
window is actually on screen, rather than kept as a boolean beside it. A flag stuck on would have
been a HUD that stopped appearing for good with nothing on the phone to say why — the failure this
release is mostly about, and worth making unreachable rather than merely unlikely.
