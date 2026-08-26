## BrightControl v3.77 — the switcher stays after a wheel hold, and ADB commands are readable

**The app switcher opened by a wheel hold no longer closes the moment you let go.**
When the hold-threshold change (v3.68) moved the switcher's hold to fire mid-press, the
release key event arrived *after* the switcher was already up — and routed straight to
`onSwitcherKey`, which read it as "choose the selected app" and took the list straight back
down. The release now carries no selection when the press started before the switcher
existed. The hold fires at the threshold and the list stays up for the wheel to navigate.

**Tapping an ADB grant row now shows the whole command.** The detail line was clamped to two
lines with ellipsis, so longer commands like the notification listener grant were cut off
before you could read them. Tapping to show the command now gives it six lines — enough for
any grant this app issues, and enough to copy.

Fixes [light-reports#81] — the switcher opened by a wheel hold closed on release.
Fixes [light-reports#115] — long ADB commands were truncated with "..." in the notifications
settings.

---

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

---

## BrightControl v3.75 — the check I added was condemning good pairings

So a working pairing was being declared untrusted, its grants abandoned, and its owner told to delete
it. A diagnostic that manufactures the failure it was written to detect is worse than no diagnostic.

**It is patient now:** four asks a quarter-second apart, then one reconnect and a final ask. Only
after all of that is a refusal a fact about the key rather than about the moment — and the trail says
which try answered, so a connection that needs three goes is visible rather than silently fine.

The plumbing's wording is fixed the same way. It now says a shell stream was refused **twice over**,
suggests forgetting the pairing rather than instructing it, and admits that a daemon which has just
come up can refuse a first command too.

If you were told to forget a pairing today, that advice may well have been wrong. Try GRANT ALL on
this build before throwing anything away.