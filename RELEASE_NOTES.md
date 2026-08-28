## BrightControl v3.95 — a button that shows the strip, and says why when it cannot

**If the volume strip is not appearing on your phone, this release answers it in one tap instead of
in another release.**

Four releases have now been spent guessing at that from the outside. The strip declines to draw for
about a dozen reasons — the setting is off, the overlay grant is missing, the key service is not
actually bound, the screen was off, the app in front has its own volume UI, the app in front is on
the list that takes the volume keys, the stream reports no scale, the same level arrived twice, the
panel was open, a finger is on a bar, the window would not attach — and every one of them is a bare
`return` on a path with nothing on screen. From the phone they are identical: you press a key and
nothing happens. Two of those guesses took the HUD off the screen entirely.

**Volume → If the strip is not appearing** now has a button. It asks the service for a strip down
exactly the path a volume key takes, with every gate left in place. Either one appears over the
settings screen — in which case the strip works and the *trigger* is at fault — or the line under
the button names the reason.

The same screen answers the two questions underneath that one:

- **Is the key service actually bound?** Switched on in Android's settings is not the same as
  running, and nothing here works until it is. The readout is a live callback the service installs,
  so it cannot report a service that is not there.
- **What is bound to the volume keys?** A volume key that stopped working is this app's fault more
  often than not, and there was nowhere on the phone that said what was holding it. Every binding on
  both keys, with one button to hand them all back.

Plus counts of presses seen, broadcasts received and strips actually drawn, which is how you tell a
phone whose volume broadcast never arrives — where the read-back after a key is the whole feature.

### The Volume screen is settings again

It had accumulated every diagnostic added over the last four releases, in a flat list, and read like
a bug report. It is back to what it is for: the strip, the ringer, calls. Everything diagnostic moved
behind the one row that names the problem you would be looking for.

### Also

The flag that suspends the strip while a finger is on a slider is now a timestamp checked against
the window, not a boolean. A touch that never delivers its release — the window taken away
mid-gesture — would otherwise have left it set for ever, which is a HUD that stops appearing with
nothing to say why. That is the third time that exact shape of bug has been possible in this file and
the third one closed off.
