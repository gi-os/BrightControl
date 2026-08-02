## LightControl v1.4 — Back to where you were

**A new home-button action that returns you to the app the screen went off in — once — and
otherwise behaves exactly like home.**

Using a remote, or a recipe, or a boarding pass, is minutes of picking the phone up, doing one
thing, and putting it down. The screen times out between every one of those and LightOS is what
comes back, so a two-second task costs finding the app again first.

Bind **Home button opens → Back to where you were**, then pick which apps qualify in the
**Resume apps** row that appears under it. Sleep in one of them, wake the phone, press home: the
app comes back. Press home again and you go home, because the offer is spent on use. So does any
press after you have opened something else — reaching another app under your own steam withdraws
it, rather than letting home yank you out of the thing you deliberately opened on the strength of
what you were doing last night.

Nothing is chosen by default and nothing changes until something is. An app not on the list
leaves the home button exactly as it was.

**Why this lives here and not in the apps themselves.** The obvious version — have an app
relaunch itself when the screen comes on — cannot work on Android 14. A backgrounded app is
cached, a cached app is frozen, and context-registered broadcasts to a frozen app are *queued
until it is unfrozen*. `ACTION_SCREEN_ON` therefore arrives only once something has already
brought the app forward, which is the thing it was supposed to do. An `AccessibilityService` is
bound by the system, so this process is never cached and never frozen; it already watches which
app is in front, and it already owns the home button. It was the only place the feature could
actually go. LightRemote v1.14 shipped the version that cannot work, and v1.15 takes it back out.

One wrinkle worth knowing about: LightOS's lock screen comes over *as* the screen goes off, so
naively it is what gets recorded as "where you were". A LightOS window that arrived in the last
two seconds before the broadcast is read as the lock screen arriving rather than somewhere you
navigated to, and the app underneath it is what gets remembered.

**Also fixed: a tap that launched something could have its press swallowed and its launch
dropped, silently.** `homeConsumable` checked whether the *hold* needed a background activity
start before agreeing to take the key, because the hold is the gesture that costs the key — but
it never asked the same of the tap. A tap bound to an app, with the overlay appop missing, was
therefore consumed and then dropped into the same silence the check exists to prevent. It now
checks both gestures; which one caused a dead press makes no difference to the thumb.

Also in this release: a `check.yml` workflow, so a change to a key filter can be compiled on a
branch before it reaches a phone. Until now the only build that ever ran was the one that also
published a release.
