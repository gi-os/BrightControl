## BrightControl v4.33 — an app may ask to be the browser

**A new thing an app can ask this phone's shell for: the browser role.** The proper route is a
system dialog, LightOS ships no such dialog and no Default apps screen either, and the role stays
empty however politely an app asks. That is not cosmetic — with no role holder, a web address opens
nowhere, so a link in a message, a pass, or a scanned poster goes nowhere. One `cmd role` line sets
it, and this app has a shell.

**The request names nothing.** Whatever line arrives, the command that runs is rebuilt here against
the package the phone says sent the intent, so the only thing an app can ask for is that *it*
becomes the browser — the same rule every other verb on this screen follows. A request naming
another app is refused and says so. Any role other than the browser is refused too.

**The role service still has the last word.** It hands the role only to an app that qualifies for
it, so an app that is not a browser cannot become one by saying it is, and the holder is read back
afterwards rather than the command being trusted for printing nothing.

Web Tools 3.5 is the first app to ask. Its Settings row now opens this screen with the one line
already filled in, instead of telling you to come here and find GRANT ALL.

## BrightControl v4.32 — every notification gets the box, and the shade scrolls

**The hairline outline is now around every row on the lock face.** The banner had it. The score card had it. The rest of the shade was a column of text under a clock, and where one notification ended and the next began was left to the spacing. A box each, with a gap between them, and the face reads as a list.

**The shade scrolls.** Drag up on it and the rows move. Until now anything that did not fit was counted on the `+N MORE` line and reachable only by swiping the rows above it away. That was the honest answer while every vertical drag meant something else, and it stopped being true when swipe-up for the keypad was replaced by the Home button. A drag up the face had nothing left to mean.

**A drag only scrolls when it starts on the shade and the shade has somewhere to go.** Over the clock, over the player, or on an evening when everything fits, it does nothing, as before. Swiping a row left still dismisses it, and the axis is still locked at the first movement, so a diagonal never does both.

**The `+N MORE` line stays**, pinned at the foot, counting what is still below. It reads zero at the bottom of the list.

**Twelve rows are built instead of six.** The limit used to be what a screen could hold. It is now what a person reads on a lock screen before unlocking the phone.

## BrightControl v4.31 — the score is drawn, not parsed

**The lock face and the banner draw BrightSports' card as a card.** A hairline box. What happened on the left, with the team and its crest. The score on the right. The play under it, and the period and the clock at the foot. The half that is behind goes dim, the way a finished game reads in the app's own feed.

**The app says where the design cuts.** v4.30 took the title apart with a regular expression, which held only as long as the wording did. BrightSports v2.5 sends the five pieces as extras and the face reads them. A card without them is drawn like every other row on this face.

**Barlow Condensed, for scoreboard figures only.** `ONE-SCORE GAME` and `NE 7 · SEA 14` fit one line of a 3.9" panel. In Akkurat both run past the edge. Bundled, two weights, used nowhere else. The rest of the face is the phone's own face.

**The banner shows the same box.** The swipe hint rides on the foot line rather than taking a line of its own.

A BrightSports build older than v2.5 falls back to the v4.30 reading. An old app on a new phone still gets a heading and a score.

## BrightControl v4.30 — a score on the lock face looks like a score

**BrightSports writes its alert titles in a fixed shape.** `TD SEA · Patriots 7 · Seahawks 21`, `RED ZONE · SEA`, `ONE-SCORE GAME · NE 20 · SEA 24`. The banner has read that shape since v4.25 and drawn the kind large. The lock face drew the whole string as one dim line.

**Now the face reads it too.** What happened comes first, in the heading size, with the team beside it. The score sits under it in the row's usual size, and the play text under that. Same reading, same look, wherever the card lands.

**A red-zone row has no score line and still draws its body.** The old rule printed the body only under a title. That row is a kind and a down and distance, which is the whole point of it.

Nothing changes for any other app. A card whose title is not in that shape looks exactly as it did.

## BrightControl v4.29 — an ongoing card can ask to stay on the lock face

**The lock face drops every ongoing notification, and that is right nearly always.** A sync, a download, a VPN and a media session are receipts. A face full of receipts is what the filter exists to prevent. One class of card is the exception. Its whole content is the thing you want to read without unlocking.

**BrightSports' live score is the first of those.** The app runs a foreground service for the length of a game, and the service's notification is the score. The platform stamps the same two flags on it as on a download. No flag can tell the two apart. Only the app that posted it knows.

**So the app says so, in one boolean.** A card that sets `com.gios.lightcontrol.extra.LOCK_KEEP` skips the persistence rule and the importance gate. Nothing else about it changes. It is still hidden when the user hides that app by name. A swipe still takes it off for the session. It still cannot raise a banner: the banner takes the newest card that is not ongoing, which this one is. The filter treats every other app's permanent notice exactly as before.

Needs BrightSports v2.3 or later, which sets the extra.

## BrightControl v4.28 — Wi-Fi login: the handoff carries the binder, and a portal is found by address

Three reports, two faults, both of them in the ten seconds after the screen opens.

**Android's own sign-in page was being opened with nothing to draw.** `ACTION_CAPTIVE_PORTAL_SIGN_IN`
is the string `android.net.conn.CAPTIVE_PORTAL`, and two activities on this phone answer it:
`com.android.captiveportallogin` and this app's own Wi-Fi login screen. That filter is the only
reason a Light Phone has a sign-in page at all — and it means the *"Sign in to network"*
notification this app fires can re-launch this app. [light-reports#329] and [#330] are one phone
twelve minutes apart doing exactly that, the second one carrying the system's `CaptivePortal`
binder in the intent.

The round trip is not the bug. It is how a screen opened by hand gets hold of a binder it never
had. The bug was the next step: the direct launch carried the network and nothing else, so the
platform's login app opened with no URL, no binder, and no reason to stay. It now forwards **the
whole extras bundle** — binder, portal URL, probe spec, user agent, and anything a future ROM adds
— and names the component, which cannot resolve back here. When this screen already holds the
binder it skips the notification entirely. And a launch that arrives with the binder within three
minutes of our own handoff is recognised as the round trip, so it no longer files a second report
about itself.

**A portal whose DNS answers nothing.** [light-reports#286] and [#287]: the bind worked, the
network was flagged CAPTIVE_PORTAL, the WebView was fine — and every hostname died with
`ERR_NAME_NOT_RESOLVED` after eight and a half seconds. A resolver that answers only for the
network's own names, or for nothing at all until a device is admitted, is common in cheap hotel
gear, and it makes every name on the phone useless including the one this screen loads.

The page is still there, at an address. So: the WebView starts at the URL **the system probed**
(`EXTRA_CAPTIVE_PORTAL_URL`, out of the intent that launched us, checked to be `http` or `https`
before it is loaded — this activity is exported, and `loadUrl` of an extra is otherwise a hole),
and when a name will not resolve it falls back once to the network's own address: the DHCP server,
then the default gateway, then any resolver on a private range. A public resolver is never tried —
8.8.8.8 has no login page.

**And getting through no longer depends on DNS either.** A failed probe now reads the network's
capabilities, which costs no socket and no name: VALIDATED without CAPTIVE_PORTAL is the system's
own probe saying the gate is open, and on a network where nothing resolves it is the only way this
screen can see it.

The report a dead resolver files now says so, rather than "nothing answers over this Wi-Fi", which
was true of the name and false of the network.

### How

`portal/PortalRoute.kt` is new and holds the address decisions as pure functions — which URL to
start at, which of the three known addresses to try, and how to tell a DNS failure from a dead
network — with eight tests on it. `SystemSignIn.open` takes the launching intent. `PortalActivity`
gained `retryAtGateway`, the capability read in `probe`, and `Prefs.portalHandedOffAt`.

## BrightControl v4.27 — the neighbours' grants: passkeys, autofill, the browser

**GRANT ALL now sets up the apps around this one, when they are installed.** LightOS has no page
for any of these, and the platform keeps them behind secure settings only the shell can write:

- **Passkeys go to Bitwarden.** `credential_service` and `credential_service_primary` name
  Bitwarden's credential provider, so a passkey request from Web Tools (Firefox's engine, which
  uses Android 14's Credential Manager and needs no Google) is answered by the vault, and a new
  passkey is saved there. Bitwarden asks once, on the first request, whether to trust Web Tools
  as a browser; say yes and it remembers.
- **Passwords in apps come from Bitwarden.** `autofill_service`.
- **Web Tools is the browser.** `cmd role add-role-holder android.app.role.BROWSER`, so every link
  from every app opens there. The role dialog Web Tools asks for itself may not exist on LightOS;
  this line does not need it.

Each step is skipped when its app is not on the phone, and each is read back like the others, so
the screen says OK, FAILED or UNKNOWN per line.

## BrightControl v4.26 — the wake turns the panel on

**A sleeping phone with banners on and the lock face off did not wake for a notification.**
Reported on Discord with a photograph of the settings screen, which was set exactly right: Banners
ON, Wake the screen ON, and nothing. Every banner drew perfectly the moment the screen was already
on. With the screen off, nothing happened at all.

**Waking the panel was one deprecated wake lock and nothing else.**
`SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`, acquired inside a `runCatching`. A display that
does not act on that request does not throw, so a wake that never happened looked exactly like a
wake that did, in the log and everywhere else.

**A screen wake lock holds a panel on. It is not what reliably turns one on.** So the wake is two
things now. A 1x1 transparent window carrying `FLAG_TURN_SCREEN_ON` brings the display up, and the
wake lock keeps it up for the length of the banner. The window is added, shown, and taken down
1.5 seconds later; it takes no touches and paints one transparent pixel.

**The flag is on a window of its own, and that is deliberate.** It fires when a window is shown,
and the lock face is added *as the screen goes off* — the flag there would light the phone up every
time it was put down. The banner's own window would have worked, but only for a phone with banners
on: with the lock face on the box is deliberately not drawn, because the face already carries the
row. One window owned by the wake means both settings wake the same way.

**Nothing about the keyguard changes.** No activity, no `FLAG_SHOW_WHEN_LOCKED`, no occlusion, so
the fingerprint reader stays armed. That is the whole reason BrightChat's `turnScreenOn` activity
was never reused here.

**If this was you, it took the wake out of BrightChat too.** BrightChat is told to stand its own box
down while BrightControl's banners are on, and that box was also its wake — a `showWhenLocked` +
`turnScreenOn` activity. So one broken wake lock was two apps that had stopped waking the phone.
Both come back with this version and there is no setting to change.

### How

`BannerWake.poke()` raises the window and arms its own removal; a second banner while one is up
re-arms the removal instead of adding another. `BannerWake.release()` is public and called from
`ControlService.onUnbind`, for the unbind that lands inside those 1.5 seconds. The wake lock is
untouched.

## BrightControl v4.25 — a BrightSports banner leads with what happened

**BrightSports 2.0 writes its alert titles in a fixed shape.** The kind comes first: `TD SEA · NE 7 · SEA 14`, `RED ZONE · SEA`, `ONE-SCORE GAME · NE 20 · SEA 24`. The banner now reads that shape and draws the kind large, in the heading size, with the team beside it. The score sits under it in the usual title size. The play text stays on the body line.

**Only that one app.** `NoteText.sportsKind` answers for `com.gios.lightsports` and nothing else. A chat message that happens to start with FG keeps its title whole. Every other banner is exactly what it was.

**Nothing changes on the phone's side.** BrightSports still posts a plain title and text. The banner reads them the same way as before and takes the label apart in one place, `NoteText`, which has tests.

## BrightControl v4.24 — the pairing box is not unreadable while it is still empty, and a refused key throws itself away

**Two of the reports the pairing path files were the app complaining about its own timing.** Both
are fixed by knowing the difference between a thing that has gone wrong and a thing that has not
finished.

**A dialog with nothing in it is not a dialog that cannot be read.** Three reports —
[light-reports#300], [#285] and [#284] — filed "could not read the pairing code off the dialog",
and all three carried the same screen:

```
Pair with device
Wi‑Fi pairing code
IP address & Port
CANCEL
```

Both labels, and neither value. The address is the tell. Settings does not wait for anything to know
its own IP and port, so a box missing the address as well as the code is a box that has not been
populated at all. Settings asks `IAdbManager.enablePairingByPairingCode()` and gets the digits back
in a broadcast, and the reader sweeps twice a second, so it looks straight into the gap between the
box appearing and the broadcast landing — every time. All three of those reports are pairings that
very likely went on to work, with a bug chip raised over them.

An empty box is now left alone and looked at again 500 ms later. A box with the address on it and no
six digits is still reported, because that one really is a shape the reader does not know — and even
then only after three seconds of staying that way, which covers the narrower race where the address
arrives before the digits.

**A key the daemon will not trust now throws itself away.** Six reports — [light-reports#307],
[#301], [#267], [#250], [#249] and [#237] — carry the same sentence, ending "This is the state
FORGET THE PAIRING exists for." The app had worked out exactly what was wrong and then asked the
user to go and press the button about it.

By that point the verdict is not a guess: the connection is asked four times a fifth of a second
apart and then reconnected and asked again, so the key is dead. A dead key is worth nothing,
and keeping it costs a press. So the app forgets it, turns wireless debugging back on if it needs
to, arms the reader and reopens Developer options — the whole of START OVER AND PAIR, without the
press.

It cannot finish the job alone. A new pairing needs six fresh digits and only Settings can make
those, so the last step is still "open the pairing box." And it happens once: the second refusal
says so plainly, because a phone that rejects a key it has just accepted twice is not a stale key on
this phone and should not be described as one.

### How

`AdbPairCode.looksUnpopulated()` is the new shape test — the dialog, no code, and no address —
sitting beside the rest of the string handling with no Android import and four unit tests on it.
`AdbPairSession.startOver()` is the recovery, and its budget survives its own re-arm so a phone that
refuses everything cannot walk through Settings forever.

Fixes [light-reports#300], [#285], [#284], [#307], [#301], [#267], [#250], [#249] and [#237].
