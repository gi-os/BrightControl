## BrightControl v3.11 — the connection reconnects itself

**"I set it up, I came back, and I get Stream closed."** Nothing was wrong with the setup. It had
simply ended while the user was in transit.

The daemon's TLS listener does not survive leaving the Wireless-debugging screen on this phone,
and it comes back on a new port. So the connection made at step 3 is dead by the time anyone has
walked back to the app that needed it — which is every single time, because walking back is how
you get to the button. Every screen here then offered that button anyway, on the strength of a
flag that says "a socket was opened and not deliberately closed", and fired a list of commands
into a socket the daemon had dropped. Six grants, six identical `Stream closed`.

v3.10 made that visible instead of calling it done. This makes it not happen.

### Reconnecting is not a thing to ask a user about

The part that needs a human is the pairing, and it is already done and kept: the key pair and
certificate live in `filesDir`, so the daemon recognises this client for as long as the phone
remembers the pairing. Everything after that is discovery. `_adb-tls-connect._tcp` is advertised
by the daemon whenever wireless debugging is on, and it names the current port by definition —
this app has been able to find it since the day it learned to pair, and was asking anyway.

`AdbManager.ensureAlive()` drops the dead socket, discovers the port again, reconnects, and proves
it by sending a command. RUN THESE and GRANT ALL both call it in front of the batch, and the grant
request screen calls it when it opens, so the connection is normally back before the screen has
been read. A second on the failure path, one round trip on the success path.

**So: no, you do not need to go back to the ADB screen, and you do not need to keep it open.** Pair
once. After that the port being stale is the app's problem, not yours.

### The buttons stopped refusing to do the thing that would have fixed it

GRANT ALL was disabled unless the flag said connected, and the flag reads false after any trip
through Settings. So the one action that would have re-established the connection was the one
action the screen would not offer, and the advice on screen was to go and set up a pairing that
was already there. It is enabled now and reconnects when tapped.

The grant request screen no longer opens on GO TO ADB SETUP either. It tries first, and only says
it cannot reach the phone when reconnecting has actually failed — which means wireless debugging
is switched off, or the pairing has been forgotten. Both are real, and both are worth a screen.
Neither was what was happening.
