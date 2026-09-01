## BrightControl v4.10 — the weather, in one dim line

One addition, and it is a sentence: the lock face now shows today's weather, under the status
bar where the alarm and the battery already live.

### 72° · CLEAR · H 81 L 64

Current temperature, the sky in a word, today's high and low — and `· RAIN 60%` appended only
when the chance of rain reaches 40%, because a line that mentions every 10% drizzle is a line
you stop reading. Fahrenheit on the face; the conversion is a pure object with its own tests,
rounding half-up.

### No new permission, no location, no radio

The numbers come from LightFog's new weather provider (`com.gios.lightfog.weather`, from
LightFog v0.13). LightFog is the one app on this phone allowed to know where it is, and what it
serves here is weather only — no coordinate crosses the boundary, and the coordinate behind the
numbers was rounded to about a kilometre before it ever left the phone. This app asks a
ContentProvider and draws a TextView; that is the whole of its involvement.

### Absent, never broken — and never a poll

No LightFog, an older LightFog, an empty cache, any failure at all: the line simply is not
there. A reading older than three hours is treated the same way, because stale weather
presented as current is worse than none. The query runs when the face shows and when the screen
wakes, on its own thread, exactly like the directions row and the calendar line — and never on
a schedule while the phone is dark. The line is not touchable and not dismissable; it is a
status glyph in the shape of a sentence.
