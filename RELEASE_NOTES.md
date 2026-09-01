## BrightControl v4.6 — directions and the calendar, on the lock face

Two new lines on the Light face, each absent until it has something to say, and a fix for the one
way a well-formed reminder could still be kept off it.

### The turn you are on

While BrightWay is navigating, the face carries the current instruction with the numbers under it
— `450 FT · 12 MIN · 3/8`: distance to the turn, minutes left in the whole trip, and which step of
how many. Metres arrive metric off the route and are said in feet and miles, because street signs
here are. The row is greyscale like everything else on this face; where a subway line matters,
BrightWay's instruction already names it.

No polling. BrightWay announces every update on
`content://com.gios.brightway.nav/current` and the face observes that URI for exactly as long as
the window is up — the same lifecycle as the now-playing row. While the panel is dark the pings
are ignored and the face re-asks the moment it lights, so an hour underground costs nothing and
the first glance is never stale. An empty answer is the trip ending, and the row goes with it.

The row is deliberately not touchable and not dismissable — a turn you are mid-way through is not
a notification to wave off. Swipe up for the keypad and hold-to-enter work over it unchanged.

### NEXT UP

One quiet line under the date: `NEXT UP · 9:30 DENTIST`. BrightNotebook offers its next calendar
entry — event, reminder or ticket, over the coming 48 hours — at
`content://com.gios.lightnotebook.nextup/next`, and the face asks on every show and every wake,
with an observer on top because its change notice is best-effort. A timed entry shows its time,
tomorrow's says `TOMORROW` first, an all-day one names the day and skips the midnight. Nothing in
the 48 hours, no Notebook installed, or a Notebook from before the provider shipped: the line is
simply not there. No version is checked anywhere.

### Reminders that never made the face

Reported from a real phone: BrightNotebook reminders buzzed and did not show on the face. The
whole pipeline was audited — the Notebook's channel is IMPORTANCE_HIGH, its title and text are
filled, every flag on it is clearable, and the face's reading of styled notifications has been
robust since v3.87. The one gate left that could drop it was the importance test itself: the
ranked figure arrives *as adjusted* by the platform, and LightOS has no notification-settings
screen, so an adjustment down is invisible and permanent. A reminder, an alarm or a calendar
event now passes the face's filter on its merits once every persistence check has already passed.
A demoted chat can wait in the shade; a demoted reminder for the 9:00 is worthless at 9:05.
BrightWay's ongoing low-importance navigation notification stays ignored — ongoing is ruled out
before the exception is ever consulted.

While in there, the last blank-line case went too: a notification whose only words are in
`EXTRA_SUB_TEXT` now shows them, dead last in the order, instead of an app name over nothing.

### Notes

Swiping a notification off the face, and everything about how that respects un-clearable rows,
shipped long ago and is unchanged here. New this release is only what is listed above.
