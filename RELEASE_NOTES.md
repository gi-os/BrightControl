## BrightControl v3.57 — the request screen says what is happening, and keeps saying it

*"I have no clue its actual status."* Fair. A run that had not started, one in flight, and one that
had finished badly all looked identical: a button, and nothing else.

**Status first, three facts, none of them inferred:**

```
STATUS
Shell                  READY   connected to the phone's debugging service
Wireless debugging     OFF     the daemon is not listening, which is why nothing runs
This request           14s     running — the transcript below is live
```

Whether there is a shell, whether the daemon is even listening, and what this request is doing. The
middle one is the answer to most of tonight, and it was never on the screen that needed it.

**And the run no longer disappears when you look away.** It lived in the composition, and a run
takes up to three quarters of a minute — long enough to put the phone down, be taken to another app,
or come back through the launcher. Every one of those rebuilds the composition, and local state
rebuilt is local state gone: the screen came back saying nothing had ever happened, *in the middle of
something happening*. Which is indistinguishable from the thing not working, which is what it was
reported as, twice.

The run lives outside the screen now — the same shape the automatic pairing has always used — so the
screen is a window onto it rather than its owner. Leave mid-run and come back: it is still running,
with its transcript. Come back after: it says what it said. A run belonging to a different app's
request is dropped rather than shown against this one.

**The wait counts.** `RUNNING… 14s`, or `RUNNING 2/3 · 14s` when there is more than one command, and
the transcript header counts too. A wait that counts is a wait somebody can believe; a wait that
merely asserts it is working is a wait somebody force-quits.
