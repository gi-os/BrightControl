## BrightControl v2.16 — The notifications that fit, and a drag for the rest

**The third notification on the lock face was cut in half. It always was, and it was never going to
be fixed by showing fewer of them.**

The column was a plain list at the end of a weighted layout: it was handed whatever vertical space
the clock and the date had left over, and it drew its rows regardless of whether they went past the
end of it. Two notifications fit, the third one lost its bottom, and the fourth was not there at
all — with nothing on screen to say there was more.

Lowering the cap would have traded one wrong answer for another, because **how many fit is not a
number this code can know in advance**. A notification is one line if it has only a title and three
if it has a body, and the space left over depends on the picture, the prompt lines and the screen.
The count has to be measured, not decided.

So it is measured. The column now asks its rows how tall they turned out, keeps the ones that fit
**whole**, and ends exactly where the last of them ends — the bottom edge of the list is the bottom
edge of a notification, always. What does not fit is not thrown away either: **a drag inside the
column moves through the rest**, and letting go settles it on a boundary rather than resting
mid-notification. Up to eight are reachable that way, with `+N MORE` past that, since a lock screen
that has to be scrolled through is a shade.

### Swiping up still reaches the keypad

This is the part that had to be right. The face is dismissed by swiping up, and that gesture is how
the keypad is reached — a scrolling area in the middle of the screen is normally a band where the
swipe silently stops working, because a scroll view claims every touch it is offered whether or not
it has anything to scroll.

The column claims a drag **only when there is something to scroll**. When everything fits, it never
sees the touch at all and the swipe behaves exactly as it did before. When it does scroll, the drag
moves the list and nothing else: it does not raise the bouncer, does not dismiss the keyguard, and
does not unlock the phone. There is no fling, either — momentum is what would leave the list resting
half way through a notification, which is the thing this release exists to stop.

### Under the hood

The column is also **rebuilt only when the notifications change**, rather than on every minute tick.
The old code tore down its views and built them again once a minute, which was a flicker before and
would now also throw away a scroll position while it was being read.

The two sums this depends on — how tall the column may be, and where a released drag settles — are a
pure function apart from the view that uses them, and there are eight new JVM tests over them. Not
ceremony: the failure they cover is a notification with its bottom half missing on a locked phone at
midnight, which is invisible to every other kind of check.
