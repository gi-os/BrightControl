## BrightControl v4.14 — Wi-Fi joins without the shell

v4.13's Wi-Fi screen failed on its first outing: *Scan failed: the connection is gone and could not
be picked back up*, standing next to the network it wanted. The reason is structural, not a bug in
the connection code. Android's `AdbDebuggingManager` refuses to start the wireless debugging daemon
unless the phone is already on a Wi-Fi network, and switches it off the moment Wi-Fi drops. The
shell this app holds therefore cannot exist at the one moment this screen matters: off Wi-Fi,
wanting on. Chicken, egg.

### The second route: suggest

A plain app on Android 10+ cannot join a network itself, but it can **suggest** one
(`WifiNetworkSuggestion`), and the system joins it on its next scan. Off Wi-Fi, the screen now does
that: pick a network, and Android joins it — with a password for WPA2/WPA3, enhanced-open for OWE.
Suggestions are kept, so a network joined once is rejoined on its own next time, and the KNOWN TO
THIS PHONE list shows them with FORGET.

Scanning off Wi-Fi is `WifiManager.getScanResults`, which Android gates behind fine location *and*
the location toggle. Both are checked and named on the screen, with a GRANT row that asks the
normal way (ADB & grants already gives it silently) and a row that opens location settings.

### The catch, and three ways round it

Android ignores an app's suggestions until the user has approved that app once, through a
notification with Allow / No thanks — a notification LightOS has no shade to answer from. So:

- **Self-grant.** `cmd wifi network-suggestions-set-user-approved com.gios.lightcontrol yes` is a
  step in ADB & grants now, verified with the new `GrantCheck.ShellSays` (runs
  `network-suggestions-has-user-approved` and reads the `yes`). Run it once at home and the café
  is already approved.
- **The APPROVAL row.** Whenever the shell is reachable (on Wi-Fi), the row shows the current
  answer and offers APPROVE.
- **Press the notification's own button.** This app's notification listener can see the system's
  question. `LockNotes.approvalAction` finds an action titled Allow/Yes/Accept on a notification
  that names this app and a network, and the screen shows ALLOW — ANSWER ANDROID'S QUESTION, which
  fires that action's PendingIntent. Checked during a join, when the question appears.

### What stays

On Wi-Fi with the shell up, the v4.13 route is still used: `cmd wifi connect-network` joins
instantly, `list-networks`/`forget-network` manage saved networks, and the radio can be switched on.
The screen decides per action, and says which route it is on ("joining X (suggestion)…"). After
either route: association within 30 s, then the system's verdict — VALIDATED is online, anything
else opens the login page.

THIS PHONE gained a *Shell* row that says plainly why it is unreachable off Wi-Fi.
