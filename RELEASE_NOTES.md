## BrightControl v4.16 — under a VPN, hand the login to Android's own sign-in app

v4.15 explained the VPN and asked for it to be switched off. For one tester that is not an option:
the VPN is accountability software, always-on, not his to disable. So "turn it off" was a
diagnosis, not a fix. This is the fix.

### The route a VPN cannot block

netd's rule — a UID under a VPN may not select any other network — has an exception class: apps
holding `CONNECTIVITY_USE_RESTRICTED_NETWORKS`. The platform's own **CaptivePortalLogin** is one.
It is how a stock Android phone signs in to hotel Wi-Fi with a VPN up, and it is installed on the
Light Phone too. What LightOS lacks is the *"Sign in to network"* notification that launches it,
and a shade to tap it from.

This app has a notification listener. ConnectivityService still posts that notification (as
package `android`) the moment a network is flagged `CAPTIVE_PORTAL`; the listener still receives
it; and its content intent carries the `CaptivePortal` binder that lets a successful login be
reported back to the system. Firing that intent *is* tapping the notification.

### What changed

- `portal/SystemSignIn`: opens Android's sign-in app — first through the system notification
  (`LockNotes.signInAction`, matched on the title's words), then by launching the activity directly
  with `ACTION_CAPTIVE_PORTAL_SIGN_IN` and the network. It logs which route worked, and the system
  notifications it could see, so the next report says what this ROM allows.
- The portal screen does this by itself the moment a VPN refuses the bind, and says so. **OPEN
  ANDROID'S SIGN-IN PAGE AGAIN** re-fires it.
- **CHECK** works under a VPN now: it cannot open a socket, but it can read the network's
  `VALIDATED` bit — the system's own probe saying the gate is open — after asking the shell (which
  is reachable, the phone being on Wi-Fi) for `cmd connectivity reevaluate <netId>`.
- The Wi-Fi screen's VPN row opens the system sign-in page rather than VPN settings; settings are
  the fallback when the phone has no sign-in app.
- `LockNotes.systemNotes()` lists the platform's own notifications for the log — package and title
  only, never anything from a user's apps.

### Still to learn from the phone

Whether LightOS's ConnectivityService posts the sign-in notification at all, and whether the
direct launch renders the page without the binder. The handoff is reported once either way.
