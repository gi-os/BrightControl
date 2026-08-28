## BrightControl v4.2 — the pairing reader stops mistaking its own screen for the dialog

**A report said the pairing code could not be read, and the screen it showed was this app's own setup screen.**

The pairing helper reads the six digits off the Settings pairing dialog. The report that landed carried the
app's **ADB & grants** screen text — "START OVER", "START OVER AND PAIR", "STEP 1 — TURN ON WIRELESS
DEBUGGING" — not the dialog. No dialog was open, and no code was on screen. The reader had looked at the
app's own window and decided it was the pairing dialog, because the help text on that screen literally
says "Pair device with pairing code".

The reader is declared to see only the Settings app, and it was assumed that was enough. It is not. That
declaration filters which *events* reach the service; it does nothing to `windows` and `rootInActiveWindow`,
which hand back every window on screen regardless of package. So while the reader sat armed, it offered the
app's own screen to the dialog test, the test said yes, and a false "could not read the pairing code"
report went out.

The reader now skips any window whose package is not `com.android.settings` before it even reads the text.
Every other window — the app's own screen included — is never offered to the dialog test, so a setup screen
describing how to reach the dialog can no longer be mistaken for the dialog itself.

Fixes [light-reports#177] — the reader read the app's own setup screen as if it were the pairing dialog.
