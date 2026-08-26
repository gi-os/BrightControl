## BrightControl v3.49 — the dismiss swipe goes left

**Same gesture, other direction.** A notification on the lock face is now pushed off the **left**
edge to clear it, not the right, and so is the player's card. Everything else about it is unchanged:
the row follows your thumb and fades, five grid units is the point of no return, letting go short of
that puts it back, and what lands is a real `cancelNotification` — the notification leaves the shade
and Glance at the same moment it leaves the face.

Left is the direction every other shade on every other phone uses, and on a phone held in a right
hand it is the shorter travel — the thumb is already on that side of the panel.

### The gestures, in one place

| gesture | what it does |
| --- | --- |
| swipe **up** | put the face away, keypad underneath |
| swipe **left** on a row | clear that notification, or put the player's card away |
| **press and hold**, once unlocked | go in |

A drag to the right over a row now does nothing at all, deliberately: the axis is still locked at the
first movement past the touch slop, so a sideways drag is a sideways drag and cannot fall through to
the swipe up and take the face away while you meant to wipe a row.
