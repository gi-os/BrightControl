## BrightControl v3.44 — one way through ADB setup, and your request survives it

**Two of the four steps could not work and one of them said so.** The setup screen offered a
six-digit pairing code to type in, above a paragraph explaining that the box holding that code dies
the moment you leave Settings — "a second device or a very fast thumb". It is gone. The automatic
route reads the code off the box while it is still open, which is the only way this has ever
worked.

**The CONNECT step is gone too.** Pairing connects on its own, and every batch of grants calls
`ensureAlive()` before it runs anything, so the port is found again whether or not anybody pressed
a button. The port field went with it: the daemon announces itself over mDNS, and in a full evening
of setting this up nobody typed a port once.

What is left is what actually happens: turn on wireless debugging, tap PAIR AUTOMATICALLY, done.

**And the request you came in with is no longer dropped on the floor.** When another app asks for
grants and there is no connection yet, the screen says *"this request will still be here"* — and
then sending you to setup lost it, leaving you looking at GRANT ALL. That button is this app
granting itself its own permissions. It was never the thing you were asked to approve, and it was
the only thing on offer.

The request is now set aside before you leave, and handed straight back the moment there is a
connection to run it with — which on the automatic route happens while you are still standing on
the setup screen. It survives the trip through Settings, because it is stored rather than held in
memory: the pairing helper is an accessibility service, and coming back from that screen is
precisely where a process does not.

**A request that waited says so.** Ten minutes or more and it comes back with its age on it,
because a Bluetooth address goes stale in about fifteen — a ring rotates its own every quarter of
an hour. It is still offered, exactly as it arrived; you are the one holding the ring, so you are
the one who can tell. Only requests older than six hours are dropped, and that is to stop a
forgotten session yanking you somewhere you did not ask to be.

GRANT ALL is now labelled for what it is — BrightControl's own grants — and says that another app's
request comes back on its own rather than living behind that button.
