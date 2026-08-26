## BrightControl v3.69 — the pairing attempt leaves a trail, and proves itself before claiming success

Hours of reports tonight about commands failing, and **not one** about the pairing — which is the step
every one of those commands depends on. That is not luck, it is a design fault: the attempt's progress
lived in memory, and the whole sequence happens while this app is in the background behind the
Settings screen it sent you to. The one screen that showed it was the screen nobody was looking at.

**So every step is written to disk as it happens**, and shown on the ADB screen afterwards:

```
LAST PAIRING ATTEMPT
20:14:02  armed — waiting for the pairing box
20:14:19  code read, pairing
20:14:21  pairing accepted
20:14:23  connected
20:14:23  shell REFUSED a command
```

Five lines, and the failing one is the answer. Whatever happens in between, it is readable when you
come back.

**And "paired and connected" is no longer taken as success.** A connection that is up and refuses
every command is exactly the state that has been reported all evening as *Stream closed* — and at the
end of pairing it is indistinguishable from working unless something asks. So something asks now,
once, immediately: one command down the connection it just made. If the shell answers, the pairing is
real. If it does not, the screen says the key is not trusted and points at FORGET THE PAIRING, instead
of reporting success and leaving you to discover it nine grants later.

**Each of the three failures reports itself**, with what it means rather than what it was:

- the daemon refused the pairing — usually a box that had closed, sometimes a stale key
- accepted the pairing and then nothing to connect to — wireless debugging went off in Settings
- accepted both and refused a shell stream — the key is not one it trusts

Those are three different fixes, and they have been arriving as one message all night.
