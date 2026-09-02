## BrightControl v4.13 — Wi-Fi: join the networks Settings calls "not supported"

*This network ("DFS Guest") is not supported by The Light Phone.* That is what LightOS Settings
says to an open network, and it is a rule in Light's Settings app — the Android Wi-Fi stack
underneath it is stock and will join anything. This app already holds the phone's own shell for
its grants, and the shell has `cmd wifi`, the tool the platform's own tests join networks with. So
the Wi-Fi login screen is now a Wi-Fi screen, and it joins.

### What it does

- **Scans** over the shell (`cmd wifi start-scan`, then `list-scan-results`) and lists every network
  it hears, one row per SSID, strongest first, with signal bars, band (2.4 / 5 / 6 GHz) and security.
- **Joins** with `cmd wifi connect-network <ssid> open|owe|wpa2|wpa3 [passphrase]`. Open and OWE
  networks join on a tap; WPA2 and WPA3 ask for the password (a WPA2/WPA3 transition AP is joined
  as WPA2, which every AP that advertises PSK accepts). Enterprise (802.1X) and WEP are shown greyed:
  the shell cannot supply a certificate, and will not add WEP.
- **Watches the verdict.** `cmd wifi status` confirms the association within 20 s; then the network's
  capability bits decide the next step. VALIDATED means online. CAPTIVE_PORTAL, or connected but
  still unvalidated after 12 s, means a login page — and the portal screen (v4.12's, with its log)
  opens by itself.
- **Saved networks** are listed with FORGET (`forget-network <id>`), and a switched-off radio gets
  a TURN ON row.
- If the shell is not paired yet, the screen says so and sends you to ADB & grants.

### Why the shell and not the Wi-Fi APIs

A third-party app on Android 10+ cannot join a network of its own choosing. `addNetwork` returns -1;
the suggestion API only lets the *system* pick the network up later and needs a per-app approval
that LightOS has no UI for; a `WifiNetworkSpecifier` request yields a network the system refuses to
route the internet over. The shell has none of those limits, and this app has the shell.

### Details

- `wifi/WifiShell.kt`: pure parsers for `list-scan-results`, `list-networks` and `status`, the
  `connect-network` line (single-quoted for `sh`, because SSIDs have spaces and passphrases have
  everything), and the blocking calls over `AdbManager.runVia`. Unit-tested.
- `ui/WifiScreen.kt` replaces `WifiLoginScreen.kt`; the home row is now **Wi-Fi**.
- The THIS PHONE diagnostics (WebView, login-page detection) and the MAC-clone fallback stay at the
  bottom of the screen.
