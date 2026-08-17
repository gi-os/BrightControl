## BrightControl v2.13 — Black first, then the face

Pressing the power button with your thumb already on the sensor opens the phone in a couple of
hundred milliseconds. For that whole gesture the right thing to show is what a phone that is *about
to be open* shows: nothing. Painting a lock screen and taking it away again a moment later is a
flicker the eye reads as a fault, even though everything worked.

So the panel now lights **black and stays black for half a second**. If the phone is still locked
when that wait is out, the face fades up over 320 ms.

The wait is how it finds out which unlock this was. A thumb that landed first takes the whole window
down before the fade begins, and nothing was ever drawn. Someone who picked the phone up to *look*
at it waits half a second and then gets the clock — long enough to cover the fast case, short enough
that it never reads as the phone failing to wake.

The window itself stays opaque black throughout; what fades is the picture and the clock over it, so
there is no point at which anything underneath shows through.
