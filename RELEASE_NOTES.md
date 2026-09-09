## BrightControl v4.24 — the pairing box is not unreadable while it is still empty, and a refused key throws itself away

**Two of the reports the pairing path files were the app complaining about its own timing.** Both
are fixed by knowing the difference between a thing that has gone wrong and a thing that has not
finished.

**A dialog with nothing in it is not a dialog that cannot be read.** Three reports —
[light-reports#300], [#285] and [#284] — filed "could not read the pairing code off the dialog",
and all three carried the same screen:

```
Pair with device
Wi‑Fi pairing code
IP address & Port
CANCEL
```

Both labels, and neither value. The address is the tell. Settings does not wait for anything to know
its own IP and port, so a box missing the address as well as the code is a box that has not been
populated at all. Settings asks `IAdbManager.enablePairingByPairingCode()` and gets the digits back
in a broadcast, and the reader sweeps twice a second, so it looks straight into the gap between the
box appearing and the broadcast landing — every time. All three of those reports are pairings that
very likely went on to work, with a bug chip raised over them.

An empty box is now left alone and looked at again 500 ms later. A box with the address on it and no
six digits is still reported, because that one really is a shape the reader does not know — and even
then only after three seconds of staying that way, which covers the narrower race where the address
arrives before the digits.

**A key the daemon will not trust now throws itself away.** Six reports — [light-reports#307],
[#301], [#267], [#250], [#249] and [#237] — carry the same sentence, ending "This is the state
FORGET THE PAIRING exists for." The app had worked out exactly what was wrong and then asked the
user to go and press the button about it.

By that point the verdict is not a guess: the connection is asked four times a fifth of a second
apart and then reconnected and asked again, so the key is dead. A dead key is worth nothing,
and keeping it costs a press. So the app forgets it, turns wireless debugging back on if it needs
to, arms the reader and reopens Developer options — the whole of START OVER AND PAIR, without the
press.

It cannot finish the job alone. A new pairing needs six fresh digits and only Settings can make
those, so the last step is still "open the pairing box." And it happens once: the second refusal
says so plainly, because a phone that rejects a key it has just accepted twice is not a stale key on
this phone and should not be described as one.

### How

`AdbPairCode.looksUnpopulated()` is the new shape test — the dialog, no code, and no address —
sitting beside the rest of the string handling with no Android import and four unit tests on it.
`AdbPairSession.startOver()` is the recovery, and its budget survives its own re-arm so a phone that
refuses everything cannot walk through Settings forever.

Fixes [light-reports#300], [#285], [#284], [#307], [#301], [#267], [#250], [#249] and [#237].
