## BrightControl v3.76 — the reader looks on a timer, not only when told

*"Now sometimes it never sees the pair with device code."*

The reader read on accessibility **events** — that is, when the framework said something had changed.
A dialog that arrives while the app is settling, or whose window announces itself with an event type
this service does not subscribe to, produces no read at all: the box is sitting there with six digits
on it and nothing looks again until something else moves.

So while armed it now sweeps every window every half second, as well as on every event. A pairing code
stays on screen for as long as you leave it there, so this only has to be quicker than a person's
patience. The sweep stops on exactly the two conditions that disarm the reader — a code found, or the
ninety seconds up — so it costs nothing the rest of the time.

Events are still the fast path. They are just no longer the only one.
