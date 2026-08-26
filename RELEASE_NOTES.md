## BrightControl v3.45 — GRANT ALL works again, and the request you came for is on the page

**v3.44 broke GRANT ALL, and it broke it by removing a button.** The CONNECT button asked mDNS for
fifteen seconds to find the daemon. `ensureAlive()` — now the only reconnect left — asked for three,
on the reasoning that a service already being advertised turns up at once. It does not: coming back
from Settings the lookup regularly takes longer than that, and the fifteen-second button had been
quietly covering for it all along. So GRANT ALL reported *"could not reach the phone's debugging
service"* on a phone whose debugging service was running perfectly. Twelve seconds now, which is
long enough to find a daemon and still short enough that a phone with wireless debugging switched
off says so instead of hanging.

**The same three seconds were why the request never came back.** The hand-back waits for the
connection flag to go true, and the flag was only ever written when something on the screen was
pressed — so a screen nobody had touched sat there saying "Not connected" over a live socket, and
every effect keyed on it, including that one, simply never ran. The connection is now asked about on
arrival, and again the instant the automatic pairing reports Done.

**And the request is on the page, not just in a redirect.** At the top, above the walkthrough:

```
BRIGHTOURA IS WAITING
Its request                          1 LINE
confirm pairing 4C:6B:CA:60:96:28
[ GO TO BRIGHTOURA'S REQUEST ]
```

It shows whether or not there is a connection yet — knowing the request survived is worth as much as
being able to run it — and before pairing it says plainly that the request screen will not run
anything without one. The automatic jump still happens, but now it is keyed on the *pairing*
finishing rather than on the connection flag: being moved to another screen the moment your setup
succeeds is the point, being moved there for walking in with a working connection is a screen
fighting you.

**Two ways back to where you came from.** A button at the bottom of the ADB screen opens the app
that sent you, and the request screen offers the same thing under NEXT once a run has a result —
after a failure as much as a success, because a failure is also something to go and look at over
there. A trip that ends on the ADB screen is a trip that has not ended.

Also: a run that dies mid-way now sets the request aside before offering ADB setup, same as the
not-connected route already did. That is the case where coming back to the same list matters most.
