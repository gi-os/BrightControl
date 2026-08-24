## BrightControl v3.11 — the colour log names the app in full

**Three colour rules were lost to something the log called `edgegestures`, which is not a package
id.** You cannot look it up, you cannot grant it a rule, and the per-app list only offers apps with
a launcher icon — so the one line in a report that names what overwrote your colour was the one
line you could do nothing with.

The log line was built by cutting the package to whatever followed its last dot. That reads well
for the apps you set rules on yourself — `lightchat`, `lightcamera` — and those are never the
interesting ones. A rule is lost to whatever wrote *after* it, and what writes after it is
generally a package nobody chose: a system window raises a window-state event, BrightControl takes
it for the app in front, that package has no rule so AUTO fires, and AUTO means the baseline, which
on this phone means mono. The colour drops out from under the app you are actually looking at, and
the log records the culprit under a bare word.

Packages are now written whole: `16:04:13 com.gios.lightchat COLOR want 0/-1 got 1/0 superseded`.
Longer lines on a small screen, and worth it — the next report can be acted on instead of read.
The outcome stays at the end of the line, because the screen's headline counts held, overwritten
and superseded by how each line finishes. The line is assembled in `ColorOutcome`, next to the
naming it already does and away from anything Android, so both facts are covered by a JVM test.

Nothing about the colour itself changed. The window that steals it is still stealing it — see
[light-reports#38], which stays open with what is known so far.

Fixes [light-reports#38] — per-app colour: 9 held, 0 overwritten, 3 superseded.
