## BrightControl v3.93 — the strip is back, and the page-turn fix is a list

**v3.90 and v3.91 both left the volume strip not appearing at all. This is the fix, and it abandons
the approach rather than adjusting it again.**

v3.90 set out to stop BrightLibrary's page turns flashing a volume readout. BrightLibrary turns
pages with the volume keys and consumes them, so the system never sees the press and the level does
not move — but this app's key filter sees the press anyway, because it runs ahead of the app in
front. The idea was to compare the level before and after and show nothing when nothing moved.

The "before" reading has to be taken before the system applies the press. v3.90 posted it to a
handler, where it ran after. v3.91 took it synchronously inside the key callback, which is where
this file has claimed for years that the level is still the old one — and it was still after,
because **volume keys are handled upstream of accessibility filtering on this phone**. By the time
the filter is asked about the key, the volume has already changed.

There is no moment in this process where the old value can be read. The approach cannot work here,
however it is timed, and both attempts had the same failure: every press compared equal to itself,
every press was judged to have moved nothing, and the strip stopped appearing everywhere.

**So the rule is a list now.** *Volume → Apps whose volume keys are their own.* An app on it gets no
strip from its volume keys; BrightLibrary is on it out of the box. It is the same answer this
codebase gives everywhere else it needs to know something about an app that nothing will tell it,
and unlike the inference it cannot go wrong anywhere except on the apps in it. Nothing else changes:
this app has never taken a volume key from anybody, and an app on the list keeps every key it had.

### The Volume screen now says why the strip did not appear

The strip declines to draw for nine different reasons — the setting is off, the overlay grant is
missing, the screen was off, the app in front has its own volume UI, the stream reports no scale,
the same level arrived twice, the selector was open, the window would not attach, the app takes the
keys itself — and every one of them is a bare `return` on a path with nothing on screen. From the
phone they are indistinguishable: you press a key and nothing happens.

Three releases were spent guessing which one it was. **Volume** now carries the answer directly:
the last outcome in words, and counts of presses seen, broadcasts received and strips actually
drawn. If it ever goes quiet again, that row says why in one line instead of a release.
