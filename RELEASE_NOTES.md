## BrightControl v3.59 — the pairing reader was looking at the wrong window

Two reports came in carrying the text the reader had actually read, which is exactly why that was
added — and it was not the pairing dialog:

```
Wireless debugging · Navigate up · Use wireless debugging · Device name
Light Phone III · IP address & Port · 192.168.10.220:43139
Pair device with QR code · Pair device with pairing code …
```

That is the Wireless debugging **list**. There has never been a code on it. A dialog is its own
window, and `rootInActiveWindow` was handing back the activity behind it — so the one window that
has ever held the six digits was the one window never being looked at, and the failure was reported
against the wrong screen entirely.

**Every window is read now**, each on its own rather than joined together: the strongest signal is a
line that is *exactly* six digits, and concatenating the dialog with the list behind it surrounds
those digits with a screenful of other numbers.

**And the list has stopped passing for the dialog.** It matched every test — it says "pair", it says
"code" (in the row labelled *Pair device with pairing code*), and it shows the `ip:port`. Two phrases
belong only to the list, and either settles it. That false positive is also why a "could not read the
code" report arrived while the dialog had not even been opened.

**This is why GRANT ALL cannot connect either.** A code was never read, so no pairing was ever made,
so there is nothing on disk for the connection to use. "Already paired" and "the pairing succeeded"
are different claims, and only the second one gets you a shell.

**A STOP button, everywhere a command runs.** A command can be waiting three quarters of a minute on
a phone whose owner has changed their mind, and a screen whose only affordance is waiting is a
screen that gets force-quit — which loses the transcript, the run, and any idea of what happened.
Stopping closes the socket, because that is the only thing that ends a read blocked with no timeout:
the flag tells the loop not to start the next command, and the reset ends the one already going.
