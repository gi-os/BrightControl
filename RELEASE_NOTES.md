## BrightControl v3.51 — a command that cannot run files itself

**`Stream closed` is the failure this app produces most, and the one least likely to get reported.**
It names nothing, it looks like a phone being a phone, and by the time anybody thinks to mention it
the log line that would have explained it has scrolled off a screen they already left. Two evenings
of this were diagnosed by reading a log over somebody's shoulder.

So an adb command that cannot be run now says so through the same report chip everything else uses.
One tap files it — the failure, the reason, and *which* command stalled, which is the whole
diagnosis: a stalled `pm grant` and a stalled `app_process` are different bugs with different fixes.

Two things it does not do. It does not nag: the same failure asks once an hour, so nine grants dying
on one dead socket asks once rather than nine times, which is how somebody turns reporting off for
good. And it does not carry an address: any MAC in the command becomes `<address>` before it goes
anywhere, because these are public issues and a device address is nobody else's business.

Also in this build, from the previous push that GitHub never built: **the screen stays on while adb
is working.** Not a nicety — the pairing dialog this app reads its code from belongs to Settings, and
Settings *pausing* is what tears the pairing session down, so a screen that sleeps while you are
still finding Wireless debugging has already killed the pairing. And whether the phone is interactive
decides which branch a Bluetooth pairing request takes, so a screen changing state mid-attempt
changes the answer. Held across all of setup and for the length of any run, as a per-view attribute
that lifts itself if you navigate away.
