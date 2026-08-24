## BrightControl v3.10 — stop fighting over colour, and say whether a grant worked

Three changes, and the first two are the same mistake in two places: stating something and then
treating "we said it" as "it is true".

### PASS — for apps that set their own colour

**A fourth rule that writes nothing at all.** AUTO was the only way to say "no opinion" and it is
not one — it is the opinion *put the phone back to the baseline*, which on this phone means mono.
Stated over an app that drives the daltonizer itself, that is BrightControl winning an argument it
should not have been in, and two writers on one setting is exactly what a screen that flickers or
lands on the wrong colour looks like.

Roll and BrightChat both grant themselves `WRITE_SECURE_SETTINGS` and set their own colour, so
both ship on **PASS**. They still come up in colour — by not being interfered with, and by the app
that actually knows whether this particular screen wants it. The stock camera holds no such grant
and cannot ask, so it ships on **COLOR** and this app asks on its behalf.

PASS declines before the baseline is captured, not just before the write. Capturing there would
record whatever the app in front had set for itself as this phone's idea of normal, and every app
with no rule would inherit it from then on. Rows cycle AUTO → COLOR → MONO → PASS.

### AUTO resolves through a table instead of flatly meaning mono

Per-app colour shipped with no defaults at all, so every app on the phone resolved to the baseline
until somebody found the screen and set it by hand. The table is `Policy.colorPresets`, and it
holds whole package ids rather than the `com.gios.` prefix on purpose: the prefix would sweep in
LightNotebook and BrightRecorder, which are monochrome because that is the phone working rather
than the phone failing.

A row with nothing stored shows what the app will actually do and says where that came from.
Tapping it still overrides, and cycling back onto the preset clears the override rather than
pinning today's answer, so an app added to or removed from the table later still reaches phones
nobody has touched.

### A grant now says whether it worked

**Every ADB step is read back off the phone instead of being judged by what the command printed.**
RUN THESE and GRANT ALL both used to end on the word done regardless of what happened, and a step
that printed nothing was called ok. Both readings are wrong for the same reason: the adb `shell:`
service merges stdout and stderr into one stream and carries **no exit status**, so the text is the
only signal there is — and a command that fails quietly produces exactly the text a command that
succeeds produces.

The worse half was the run where nothing ran at all. One dropped socket makes every later call
throw `Stream closed`, and reaching the end of a list of six failures is indistinguishable, to a
button, from reaching the end of a list of six successes. So it said DONE, and then disabled
itself, which meant the single thing worth doing about the failure was the one thing the screen
would not allow.

A grant is a state, not an event, so it can be read back. Each step carries what to check — a
permission through `PackageManager`, an app op through `appops get`, an accessibility service or
notification listener through the secure list it belongs to — built where the package is already
pinned, so a check cannot be aimed at an app other than the one asking. Three outcomes, and the
middle one is the point:

- **OK** — read back and confirmed.
- **FAILED** — the command ran and the grant is still not there, with the reason on the line.
- **UNKNOWN** — nothing on the phone records this either way. Starting Shizuku is the only one,
  because it brings up a service that then asks the user per app in its own UI.

The permission check touches no shell, so it still answers after the connection has died — the run
that most needs an answer is the run where adb stopped working. When steps fail with the connection
down, the screen says so once, at the bottom, with a route back to ADB setup, instead of leaving
six identical lines to be read one at a time. The button reads TRY AGAIN and stays tappable.

### And the connection is probed before anything is fired into it

`connected()` reads a flag that is set when the socket opens and cleared when it is closed on
purpose. A socket the daemon dropped underneath us is neither, so the flag went on saying yes long
after nothing worked. The flag was never wrong about what it tracks. It was being asked the wrong
question.

`AdbManager.alive()` asks the question that has a real answer: send something trivial and see if it
comes back. RUN THESE and GRANT ALL both probe first, so a dead connection is found once, before
anything runs, instead of once per grant. The manager is reset so the next attempt starts on a
clean socket rather than reusing the half-open one, and the screen says the thing that is almost
always true — the debugging port changes every time wireless debugging is switched off and on, so a
setup that worked yesterday needs the port read again.
