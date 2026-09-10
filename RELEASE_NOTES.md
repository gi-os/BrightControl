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
