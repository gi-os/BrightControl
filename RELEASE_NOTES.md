## BrightControl v3.78 — the connect port was on screen the whole time

Report #122, from a phone that had just paired successfully:

```
Could not connect after a pairing the daemon accepted.
the pairing was accepted and mDNS then found nothing to connect to.
```

Everything up to that point worked — the reader found the code, the daemon accepted the key — and
then discovery answered nothing. Which it does, sometimes, on this phone.

**The port was never a mystery.** The Wireless debugging screen prints it, in the same window the
pairing reader is already flattening to look for a code:

```
IP address & Port
192.168.10.220:38675
```

So the reader takes it while it is up, and every connect falls back to it when mDNS finds nothing.
A phone whose discovery answers silence is still perfectly connectable; there was simply nothing
written down.

**Only from the list screen, and that distinction matters.** Both screens show an `ip:port` and they
are **different ports** — the dialog's is the *pairing* port, thrown away the moment the box closes,
while the list's is the connect port and stays put while wireless debugging is on. Connecting to the
wrong one produces a failure indistinguishable from the one this fixes, so it is gated on exactly the
test written to keep the list from being mistaken for the dialog. Three new tests hold that line.

This also means the port field I removed in v3.44 is properly gone rather than merely absent: nobody
has to read a number off a screen and type it back, because the phone reads its own.
