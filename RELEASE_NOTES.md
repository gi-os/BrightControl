## BrightControl v4.15 — the login page names the VPN that is stopping it

The first reports from v4.14 (light-reports #242, #243, #244) are good news wearing a failure.
Everything up to the last step worked: the phone was on the café's network, LightOS had flagged it
`CAPTIVE_PORTAL` (so login-page detection is on after all), the WebView was there, and the screen
picked the right network. Then `bindProcessToNetwork` returned false and every probe died with
`EPERM (Operation not permitted)`.

The log also showed why. Network 108 — `cell+vpn`, `tun0`, `dns=10.8.0.10` — was the default. A VPN.
netd's rule is that a UID whose traffic goes through a VPN may not explicitly select any other
network unless the VPN app allows bypass. The system's own CaptivePortalLogin is exempt by
privilege; this app is not, and nothing it can do lifts that.

### What changed

- When the bind fails and a VPN network is up, the portal screen says so in words: *A VPN is on
  (app), and Android does not let an app under a VPN talk to any other network — turn it off, sign
  in here, then turn it back on.* It names the VPN app when the phone will say (the always-on VPN
  package is readable; a VPN started by hand is not), offers **OPEN VPN SETTINGS**, and stops the
  probe loop instead of logging `EPERM` every four seconds.
- The Wi-Fi screen shows the same row under THIS NETWORK whenever a VPN is up and the network is
  not online, so you see it before the login page opens.
- A bind failure with *no* VPN up is now a distinct report family, so the two never get triaged as
  one problem.

### What the reports settled

- LightOS does not switch captive-portal detection off: every `captive_portal_*` setting was at the
  platform default and the network carried `CAPTIVE_PORTAL`.
- The WebView is `com.android.webview 113.0.5672.136` — present.
- Joining by suggestion works; the phone was on the network within the same minute.
