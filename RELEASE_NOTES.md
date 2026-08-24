## BrightControl v3.13 — the two grants that could not answer for themselves

**GRANT ALL ran clean and still reported "2 could not be confirmed either way."** Brightness
(`WRITE_SETTINGS`) and Overlay (`SYSTEM_ALERT_WINDOW`) came back UNKNOWN with `Stream closed`,
while the four grants after them read OK. Both of those are real grants that had in fact been
applied, and the app said it could not tell.

The pattern in that log is the answer. The four that read OK are checked through `PackageManager`
and `Settings.Secure` — framework calls that touch no shell. The two that came back UNKNOWN were
the only checks still putting a question to adb, and adb had stopped answering by the time they
asked. The grants landed; the read-back is what was lost.

So they stop asking. `Settings.System.canWrite` and `Settings.canDrawOverlays` are the same
question `appops get` asks, put to the system directly, and an answer that never crosses a socket
cannot be dropped by one. Only for this app's own ops and only for `allow` — both APIs answer "can
this app do it", which is not the same question as "is this op set to deny rather than ignore" —
so another app's ops still go through `appops get`, where there is no framework call that asks on
someone else's behalf.

**And a command that dies on its first attempt now gets a second one.** A stream that fails at the
top of a batch is the shape of a connection reported up before the daemon had finished settling,
and the repair is the one v3.12 already carries: get another connection and try again. Once, so a
command that genuinely fails does not run twice. The check that follows is put to the reconnected
manager rather than to the socket it just gave up on.
