## BrightControl v3.67 — one button for the whole recovery, and the second report was noise

**Reports #77 and #78 arrived one second apart and only one of them was true.** Grant one failed with
*"the phone no longer trusts this app's pairing"*; grant two then reported *"the connection is gone"* —
because the reconnect behind the first failure had dropped the socket. Two reports, one problem, and
the second one pointing at the wrong thing.

A pairing the phone has stopped trusting fails **every** command, so the first failure is the only
honest one. The batch stops there now and says what to do:

```
1/9  Brightness (WRITE_SETTINGS) — FAILED
stopped — the phone does not trust this pairing
use FORGET THE PAIRING above, then pair again
```

**And that sequence is now one press.** Recovering by hand is seven things in order: forget the
pairing, switch the daemon on, arm the reader, open Settings, find Wireless debugging, find *Pair
device with pairing code*, leave the box up. Every failure tonight has been somewhere in that chain
rather than in the pairing itself. **START OVER AND PAIR** does the first four in order and tells you
what each one did:

```
forgot the old pairing
wireless debugging is on
armed — open the pairing box and leave it up
```

Then Wireless debugging → Pair device with pairing code, and the reader takes it from there. No new
machinery: the same buttons, in the only order that works, without the chance to do them out of
order.

**The auto-report line stays up for ten seconds**, and can be swiped or tapped away before that. Four
was not enough to read a command and a reason if you happened to be looking elsewhere when it
appeared. Only the line itself takes touches — a gesture handler on the box around it would have
eaten every tap in the app for ten seconds.
