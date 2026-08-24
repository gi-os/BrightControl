## BrightControl v3.9 — the camera is color, and a grant says whether it worked

**Roll, the stock camera and BrightChat are now color without anyone setting them.** Per-app
color shipped with an empty table, so every app on the phone resolved to the baseline, and on a
LightOS phone the baseline is monochrome. That is the right default for almost everything and
the wrong one for a camera: Roll's output is color no matter what the panel is doing, so a grey
viewfinder was not a calm framing of the photo, it was a misrepresentation of it. The same
applies to a photo arriving in BrightChat, where grey-on-arrival and grey-when-sent look
identical.

The table is `Policy.colorPresets`, and it holds whole package ids rather than the `com.gios.`
prefix on purpose. The prefix would sweep in LightNotebook and BrightRecorder, which have
nothing to show in color and are monochrome because that is the phone working rather than the
phone failing.

**AUTO now resolves through the table instead of flatly meaning mono**, which is the same shape
the wheel's per-app list has had since it grew defaults. A row with nothing stored shows what
the app will actually do and says where that came from; tapping it still cycles COLOR → MONO →
AUTO and still wins. Cycling back onto the preset clears the override rather than pinning
today's answer, so an app that is added to or removed from the table later still reaches phones
that were never touched.

Nothing about the daltonizer writes changed. This is the table that was always missing under
them.

### A grant now says whether it worked

**Every ADB step is read back off the phone instead of being judged by what the command printed.**
RUN THESE and GRANT ALL both used to end on the word done regardless of what happened, and a step
that printed nothing was called ok. Both readings are wrong, for the same reason: the adb `shell:`
service merges stdout and stderr into one stream and carries **no exit status**, so the text is the
only signal there is, and a command that fails quietly produces exactly the text a command that
succeeds produces.

The worse half was the run where nothing ran at all. One dropped socket makes every later call
throw `Stream closed`, and reaching the end of a list of six failures is indistinguishable, to a
button, from reaching the end of a list of six successes. So it said DONE, and then disabled
itself, which meant the single thing worth doing about the failure was the one thing the screen
would not allow.

A grant is a state, not an event, so it can be read back. Each step now carries what to check —
a permission through `PackageManager`, an app op through `appops get`, an accessibility service or
notification listener through the secure list it belongs to — built where the package is already
pinned, so the check cannot be aimed at an app other than the one asking. Three outcomes, and the
middle one is the point:

- **OK** — read back and confirmed.
- **FAILED** — the command ran and the grant is still not there, with the reason on the line.
- **UNKNOWN** — nothing on the phone records this either way. Starting Shizuku is the only one,
  because it brings up a service that then asks the user per app in its own UI.

The permission check goes through `PackageManager` and touches no shell, which means it still
answers after the connection has died — the run that most needs an answer is the run where adb
stopped working. When steps fail with the connection down, the screen says so once, at the bottom,
with a route back to ADB setup, rather than leaving six identical lines to be read one at a time.
The button reads TRY AGAIN and stays tappable.

## BrightControl v3.8 — send the log

**Color → Send log** files the whole colour diagnostic as an issue on `light-reports`, from the
phone, with no typing and no computer. It carries the log, the live pair, the captured baseline,
every per-app rule, and whether the secure-settings grant is actually present — the last of which
has been assumed rather than checked in every round of this bug so far.

The title is written from the log rather than from a symptom chip, because the one fact that
decides which bug this is is already in there: *"per-app color: 3 held, 1 overwritten"* says
something is writing after this app, and *"0 held, 0 overwritten"* says the rule never ran.

It rides the queue shake-to-report already uses, so it is written to disk before anything touches
a socket and survives being sent underground.

**WHAT HAPPENED now says something when it is empty**, too. An empty log is not an empty state —
lines are written when a rule is applied, so nothing there means no rule was ever applied, which
is a different bug from one that applies and does not hold.

## BrightControl v3.6 — make the phone say which one it is

v3.3 fixed two real bugs in per-app color and the symptom did not move: an app with a Color
rule still opens grey unless you visit the Android layer first. So this release stops guessing
and instruments it, and takes the one remaining shot that is free.

### The log

**Color → what happened** lists every rule this app applied and the state the phone read back
900 ms later:

```
14:22:07 roll COLOR want 0/-1 got 0/-1 ok
```

Three outcomes, three different bugs, and no way to tell them apart from looking at a grey
screen:

- **`ok`** — the write landed and stayed. Nothing is fighting this app; the phone is ignoring a
  setting it agrees with, and the fix belongs on the side that reads it.
- **`LOST`** — something wrote after this app, and the line names the values it preferred.
- **no line at all** — the rule was never applied, because the window-state event announcing the
  app never arrived. That would make this a key-service problem wearing a color problem's face.

Only writes that changed something are logged, so a dozen lines is a dozen real applications
rather than a second of re-asserts.

### The nudge

The settings provider drops a write of the value already stored, and a value that never changes
notifies nobody. This app does not paint the screen — it states two ints and something else acts
on the notification — so a reader that missed the original transition and now holds a stale idea
of the filter can never be corrected by writing the same ints again. That is precisely the shape
of a screen that stays grey while every value reads correct.

With the mode at `-1` there is no filter at either end, so the enable flag can be picked up and
put back down 60 ms later and nothing on screen moves: a genuine change notification, invisible.
Only around "off" — the same trick around mono would flash the phone out of monochrome to
announce itself.

## BrightControl v3.3 — the color that would not stick

**Per-app color now survives coming back from LightOS.** Two bugs, one visible symptom: an app
with a Color rule opened in black and white, and the only way to get its color was to leave for
the Android layer and come back to it.

### Off was being written as monochrome

The daltonizer is two secure settings, and they are not independent. `accessibility_display_
daltonizer_enabled` is the switch; `accessibility_display_daltonizer` is *which* filter, where
`0` means monochromacy. Turning an app to color wrote `enabled = 0` and left the mode at the
captured baseline — which on this phone is `0`. So the state on disk still read "monochrome,
currently off", and anything that re-read the pair reconstituted grey from it. Off is now
written as mode `-1`, the one value no reader can interpret as a filter.

### Only this app's writes were ever the last word by accident

Color was applied when an app came to the front and never again. Whatever wrote those settings
after that owned the screen until the next app switch — and LightOS writes them, because
monochrome is how the whole phone is meant to look. Coming from LightOS, LightOS wrote last.
Coming back from Android settings, this app wrote last. One path worked, the other did not, and
from the outside it looked like the grant or the write failing.

Two things close it. A **content observer** on both settings re-states the front app's rule
whenever anything else moves them, from any direction — the rule is now a fact that gets
repaired, not an event that can be missed. And an app arriving re-states its rule again at
250 ms, 800 ms and 2 s, which covers a launcher that repaints after its own animation is over.
Neither can loop or thrash: the writer only writes on a difference, so a re-assert that finds
the screen already right costs two reads and writes nothing.

### Also

- **Color → Live filter** shows the two ints and what they add up to, and re-reads on tap. This
  bug was guessed at three times from the outside; being able to read the state settles it.
- Not this app: the night-light **sunrise/sunset** toggle is `night_display_auto_mode`, a
  different service entirely, and nothing here writes it. `settings put secure
  night_display_auto_mode 2` sets it, and twilight needs a location fix to have a sunset to work
  from.

## BrightControl v3.1 — Wi-Fi login

**The phone can now sign in to hotel and café Wi-Fi — the networks that want a webpage
before they let you through, on a phone that has no browser to show one.**

A captive portal answers every request with its own login page until the page is submitted.
LightOS connects to the network, the portal answers, and there the story ended: nothing on the
phone could draw the page, so the network sat "connected" and useless. Settings → Wi-Fi login
is the missing piece — a WebView pinned to the captive network that loads the portal's page,
lets you sign it (the wheel scrolls it), and closes itself the moment the network lets you
through.

Two decisions carry it. The activity **binds its process to the captive network**, because an
unvalidated Wi-Fi is exactly what Android routes around — unbound, every request would ride
cellular and the portal would never see one. And **success is probed, not inferred**: every few
seconds one request goes to a 204-endpoint over that network, and the day it answers 204 instead
of the portal's redirect, you're through. Portals end their flows a dozen different ways; the
probe is the only signal that means anything.

The settings screen also answers the question the phone otherwise leaves you guessing at,
reading the platform's own capability bits: **Sign-in required** (a portal announced itself),
**Online** (validated, nothing to do), or **Connected, not yet online** — the common quiet case,
where opening the page forces the question.

### The edges

- The system's own "sign in to network" flow lands here too (`ACTION_CAPTIVE_PORTAL_SIGN_IN`),
  and success is reported back through its `CaptivePortal` handle so LightOS marks the network
  usable instead of giving up on it.
- If LightOS ships no WebView, the screen says so instead of crashing, and the settings screen
  carries the workaround: sign in from a computer whose MAC is set to the phone's — portals
  remember devices by MAC.
- Cleartext http is now allowed app-wide. Deliberate: the probe is http *on purpose* (a portal
  can only hijack a request it can read), and portals themselves are routinely http. Nothing
  else in the app speaks http.
