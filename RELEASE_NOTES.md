## BrightControl v4.20 — June can take the player's slot on the lock face

**One card, for the very important thing.** BrightHermes now exposes `content://com.gios.brighthermes.deck/lock`: at most one row — title, text, when it expires — the one thing June has decided cannot wait for the phone to be opened. The face reads it like it reads BrightWay's turn and the Notebook's next event, and draws it where the music player goes, *in place of* the player. Music comes back the moment the card is gone or its clock runs out.

### How it gets there with the phone dark

The face queries on show and on every wake, and BrightHermes's provider answers from its cache at once and then — only while the screen is on — asks its gateway for a fresher card and `notifyChange`s if one arrives. So a card posted while the phone lay on the desk is on the face about a second after the first wake, with no service running and nothing polling a dark panel.

Tapping the card once unlocked opens BrightHermes, gated on the same arming as the player's title.

### Also

`LockHermes` follows `LockNav` line for line — window-scoped watcher, observer ping ignored against a dark panel, absent app means no row. The manifest's `<queries>` names the provider so Android 11's package visibility lets the query through.
