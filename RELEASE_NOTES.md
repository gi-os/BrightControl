## BrightControl v3.54 — a running command says what it is doing

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
