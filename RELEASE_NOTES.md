## BrightControl v3.48 — swipe a row right to clear it, and no more half-notifications

**Swipe right on a notification and it is gone.** The row follows your thumb, fades as it goes, and
past five grid units letting go dismisses it. It is a real `cancelNotification`, not a private list
of things this screen has decided to stop showing: the notification leaves the shade and Glance at
the same moment it leaves the face. A face that only hid things would disagree with the rest of the
phone and hand the same message back at the next unlock.

The row disappears the instant you lift, before the platform has confirmed anything. `cancelNotification`
is a request that travels to another process and comes back as a rebuild, and for those few frames
the row used to spring back under the finger, which reads as the gesture having failed. What is
shown is corrected the moment the real answer arrives, and a notification the app marked
un-clearable simply reappears — which is the truth, and the only honest way to say it.

**The player's card swipes away too, and the music keeps playing.** A card is not a transport
control. Swiping it off is "not now", so nothing is paused and nothing is stopped; the row comes
back when the session has something new to say — a different track, or play pressed again in the
app. Opening Spotify and starting something is what asking for the card back looks like from here.

**The shade is clamped to the room it actually has.** This is the "two and a half notifications"
bug. The list drew four rows into whatever space was left under the clock and drew them whether
they fit or not, so the fourth ran off the bottom of the panel — and nothing on this face scrolls,
because the window holds no key focus and every drag on it already means something else. It now
measures against the space the layout gives it, draws only whole rows, and puts what is missing on
the `+N MORE` line instead of implying it with a row cut in half. Six rows are built rather than
four, so a screen with room for six shows six; clearing the top ones brings the rest up.

**And the face repaints when the shade changes.** It repainted on the minute tick and nothing else,
so a message arriving at 10:00:05 appeared at 10:01 — and a row you had just swiped away sat there
until the same tick.

### The gestures, in one place

Three, and each one has to be impossible to perform by accident, because this window covers the
whole panel and a phone in a pocket presses the whole panel:

| gesture | what it does |
| --- | --- |
| swipe **up** | put the face away, keypad underneath |
| swipe **right** on a row | clear that notification, or put the player's card away |
| **press and hold**, once unlocked | go in |

They are read in one class now. The media buttons and the track title are clickable children, and a
clickable child takes the whole gesture from the first touch — so before this, a swipe that started
on the player did nothing at all, and a swipe up from the track title never reached the keypad. The
parent takes a press over the moment it turns into a drag, which is what it always should have
been: a drag was never a tap.

The axis is decided once, at the first movement past the touch slop, and never revisited. A gesture
that changes its mind halfway is a gesture that clears a notification on the way to the keypad.
