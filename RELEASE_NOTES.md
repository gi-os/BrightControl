## BrightControl v3.55 — a running command says what it is doing, and gives up when it said it would

**RUNNING… for forty-five seconds, and then everything at once.** That is what the request screen did
while another app's command was in flight, and from the outside it is indistinguishable from a button
that hung. Worse, the command it was hiding is the most talkative one there is: the helper that
answers a Bluetooth pairing request prints `createBond true`, then `state BONDING`, then either
`setPairingConfirmation true` and `RESULT bonded` or the reason it gave up. None of that reached
anybody, because the output was accumulated until the stream closed.

It arrives as it happens now:

```
WHAT IT IS SAYING
· Answer the pairing request · 4C:6B:CA:60:96:28
device 4C:6B:CA:60:96:28 state NONE
createBond true
state BONDING
waiting… 21s left, state BONDING
setPairingConfirmation true
RESULT bonded
```

Whole lines only — half a line on screen reads as corruption — with a trailing fragment flushed at
the end, because the last thing a command says is usually the answer and often unterminated. The
last forty lines are kept, since the tail is the part that matters.

**And a request with more than one command says which one it is on.** The button reads `RUNNING 2/3…`
rather than `RUNNING…`, and the steps run one at a time with the step's own name pushed into the
transcript in front of its output. A wait with a shape is a different experience from a wait without
one, even when it takes exactly as long.

This is also the only place the ring's own answer has ever been visible. Every attempt so far has
been read afterwards, out of a logcat, on a laptop.

**And it gives up when it said it would.** A command allowed three quarters of a minute could run for
a hundred seconds, because the deadline was handed to *each* attempt rather than to the whole thing:
forty-five seconds, a twelve-second reconnect, then forty-five more. Somebody watching a button that
promised to stop at forty-five is entirely right to think something is wrong at ninety.

There is one budget now. The retry gets whatever is left of it, and if that is under two seconds
there is no retry — a command that needs half a minute and has three seconds left should report what
actually went wrong the first time rather than manufacture a timeout to report instead.
