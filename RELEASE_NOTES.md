## LightControl v1.6 — Shake the phone to report a bug

**LightControl can now file its own bug reports, and you can say what went wrong in your own words.**

Until now only Roll, Notebook and Phono could do this. Every other app on the phone failed
silently: you would notice something wrong on the subway, have nowhere to put it, and have
forgotten it by the time you were near a computer. This is the same feature, ported.

Shake the phone twice — there and back, twice — and a sheet comes up. Pick what happened from
five chips, and add a note if you have something to add. The note is optional but it is the part
that carries anything: "Something looks wrong" is a shrug, and what you type becomes the title of
the issue. Under it the report carries the screen you were on, the app and firmware versions,
free space, heap, and the stack trace if the app died the last time you had it open.

Three things raise the sheet. A shake, because you noticed something. A crash last run, asked
once on the next launch, because that is the only moment the stack trace is still worth anything.
And a failure the app noticed by itself — those are the reports that otherwise never get filed,
because a screen that quietly came back empty looks ordinary.

Reports queue on disk before anything is sent, always. A phone that reports a freeze is by
definition a phone that was just misbehaving, and a report that exists only in flight is the one
report guaranteed to be lost. If there is no network, or this build has no reporting key, it
waits on the phone until a build that does installs over it.

The gesture is tuned to be hard to trigger by accident: it counts reversals rather than force,
because setting the phone down hard clears any threshold a shake clears, but only a shake
*reverses*. Walking never fires it. That arithmetic now has unit tests in every app that has the
feature.

The accelerometer only runs while you are looking at the app.
