## BrightControl v3.50 — the screen stays on while adb is working

The adb work here is slow by design: a reconnect looks for the daemon for twelve seconds, a pairing
confirmation waits three quarters of a minute for the platform to raise its request, and a batch of
nine grants is all of that in a row. Long enough for a phone with a short timeout to go dark in the
middle — and going dark is not just a result nobody sees:

- **The pairing dialog this app reads its code from belongs to Settings**, and Settings *pausing* is
  what tears the pairing session down. A screen that sleeps while you are still finding Wireless
  debugging has already killed the pairing you were trying to make.
- **Which branch a Bluetooth pairing request takes is decided by whether the phone is interactive.**
  A screen that changes state mid-attempt changes the answer — that is the whole reason a bond can
  be made with the screen off and not with it on.
- **A run whose result nobody saw gets pressed again**, and for a bond that means starting over with
  an address that has rotated since.

So the ADB screen holds the screen on for the whole of setup — not only while a command is in
flight, but while the pairing session is waiting, pairing or granting — and the request screen holds
it for the length of a run.

It is a per-view attribute rather than a window flag, which means it lifts itself when the screen
goes away and cannot be left switched on by something that navigated off mid-run.
