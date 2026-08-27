## BrightControl v3.92 — a stored rule could eat a click for ever

**The wheel click of a wheel-owning app now has the same first claim the camera key has always
had.** Roll and BrightRecorder implement the wheel's press as a control of their own, and the
built-in table has said so for a while — but an explicit per-app rule stored in this app's settings
beats the built-in table, and rightly. The trap: a rule of "scroll through" stored *before* those
apps used their click — stored, back then, as the only way to make their wheel turns work at all —
kept winning on every build after the fix existed. The turns arrived, the click was spent on this
service's own binding, and the app told you to click a wheel whose click never came. Roll's dial
lock was exactly that: "click to unlock", against a torch coming on in your pocket.

The claim is answered from the built-in list alone, after the stored rule has had its say about
everything else — turns, colour, the camera key all still obey it. And the claim is logged
("WheelClick · lightcamera owns the wheel"), because this key's disappearance has been
undiagnosable from the phone once already.

If your wheel click stopped working in Roll and you have a per-app rule set for it here, this is
why, and you no longer need to clear the rule — but Settings -> per-app rules -> Roll -> Default is
still the tidier state.

## BrightControl v3.90 — three fixes to v3.89
## BrightControl v3.91 — the volume strip is back

**v3.90 turned the HUD off entirely. This puts it back.**

v3.90 added a guard so that a volume key the app in front swallows — BrightLibrary turning a page —
no longer flashes a strip reporting a level that had not moved. To know whether the level moved, it
needed a reading from before the press, and it took that reading from a runnable posted at zero
delay, to keep three system calls off the thread the input dispatcher waits on.

A posted runnable does not run until the current message finishes, and by then the press has landed:
**volume keys are handled upstream of accessibility filtering.** So the "before" reading was the
level *after* the change. Every press compared equal to itself, every press was judged to have moved
nothing, and every press was suppressed. Not just in BrightLibrary — everywhere.

The reading is taken synchronously in the key callback now, where the level is still the old one.
That is not a guess: it is why the read-back after a press is delayed 90ms in the first place, and
this file has said so since the HUD was written. The cost is three system calls once per burst of
presses, which is less than the two the HUD made per press before the guard existed at all.

Everything about the guard now fails towards showing the strip: a reading that could not be taken,
one taken half way, or one older than the burst it belongs to all mean the strip appears. **An
unanswered volume key is a worse failure than a strip shown once too often**, and v3.90 is what
happens when that is the wrong way round.

### The Volume screen now says where the strip's news comes from

The HUD has two sources: the system's volume broadcast, and reading the level back after a key. The
broadcast is meant to be the main one and the read-back the fallback — but whether this build sends
that broadcast at all is not knowable from outside, and it decides what a bug in either path costs.
On a phone where the broadcast never arrives, the fallback *is* the feature, and a guard added to it
takes the whole HUD off the screen.

So **Volume** now carries a line reading either `BOTH` or `KEYS ONLY`, with the counts behind it.
It is a diagnostic, not a setting, and it exists because that number is the one fact that would have
made v3.90 obvious in a second rather than after a release.
