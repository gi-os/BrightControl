## BrightControl v4.22 — the Home button reaches the keypad, notification taps, call speaker/phone

**The swipe and the hold are gone. The Home button does both jobs now.** Reaching the keypad used to
be a swipe up, and going in once the phone had unlocked used to be a press-and-hold anywhere on the
glass — both retired in favor of the Home button, which a pocket cannot press by accident the way it
presses the whole panel. Press it on a face that has not unlocked and it drops to the keypad. Press
it once the phone has unlocked and the face is holding itself open to be read, and it goes in, same
as the hold used to.

**A notification row can be tapped**, once the phone has actually unlocked — same rule as June's
card and the player's title, because a lock screen that opens an app on one tap before that is not a
lock screen. It sends the same intent tapping the row in the shade would.

**The call card gets SPEAKER and PHONE, once a call is active.** The dialer's own in-call screen has
a speaker button and this same jump, but that screen sits underneath the lock face exactly like
everything else the face covers — and the screen cycling mid-call re-raises the face over an
already-live call with nothing on it to reach that screen from. Now it can: SPEAKER toggles the
route, PHONE jumps straight to LightOS's own in-call screen.

The now-dead "Hold to enter" setting is gone from the Lock screen page.

## BrightControl v4.21 — NEXT UP looks 18 hours ahead

**Less on the lock face.** The NEXT UP line under the date used to show whatever BrightNotebook had in the next 48 hours. That is the provider's window, and it is right for a calendar; on a lock face it meant Thursday's meeting sitting under Tuesday's clock. The line now draws only what starts within 18 hours — the rest of today and first thing tomorrow. At 10 pm it reaches a 9 am start; at noon it reaches nothing past bedtime.

### How

The cutoff lives on the face, not in the Notebook. `NextUpText.within()` is checked on every repaint, and the face already repaints on the minute tick, so an entry parked just past the edge appears the minute it comes inside — no second query, no provider change, and the Notebook keeps its 48-hour contract for anything else reading it.

Nothing else changed.
