## BrightControl v3.96 — the volume keys get their press back

**This is why the volume was not changing, and why the strip kept showing a level that had not
moved.**

Timing a hold means swallowing the press: the DOWN has to be kept until the release, or there is no
way to tell a tap from a hold, and a key already handed to the system cannot be taken back. That is
a fair trade on the home button or the wheel click, whose taps this service then owns and reproduces.

The button handler consumed whenever **any** of a button's three gestures was bound. So binding
anything to one volume key's hold or double tap made every press on that key disappear — whatever
its tap was set to, and whether or not you ever held it. The volume stopped changing, and the strip,
working perfectly, reported the level that had not moved on every single press.

Four releases were spent looking at the strip. The strip was telling the truth.

### The hold and double tap are gone from both volume keys

Not defaulted to nothing — **gone**. Holding a volume key is how you change the volume quickly, so
there was never anything on the other side of that trade worth having, and a gesture that has to be
timed cannot coexist with a key whose ordinary job is a repeating function this app cannot reproduce.

Buttons now shows one row for each volume key, and says why the other two are missing. A hold or
double tap stored by an older build is refused when it is read back, not merely hidden, so an
upgrade cannot leave one still acting.

The rule is one function with tests on it, because it is exactly the kind of thing that regresses
silently somewhere other than where it breaks.

### If yours is still not changing

**Volume → If the strip is not appearing** names the cause directly. The *Volume keys* row reads
`PASSED ON` when nothing is holding them, or lists what is bound with a button to hand them back.
After this release only a binding on the *tap* can hold a volume key, and that one is an explicit
choice to spend the press.
