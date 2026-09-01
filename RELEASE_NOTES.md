## BrightControl v4.8 — settings that travel

**Everything this app knows can now leave the phone as one file, and come back.**

Two rows at the bottom of the home screen. **Save settings to a file** writes every stored
setting — bindings, gestures, edge swipes, colour rules, lock-face choices, ringer networks, all
of it — as one JSON document, through the system file picker. **Load settings from a file** reads
one back. Keep the file in Downloads and an update, a reinstall, or a new phone costs you nothing
but the two taps.

Why a file and not a cloud: this app deliberately has no account and no backend, and the one
file picker this phone actually has — the system document picker — already works (LightOCR leans
on the same one). A file the person placed in Downloads also survives the uninstall that wipes
everything else the app knows, which is the exact moment a backup earns its keep.

The shape of the file is deliberate in two ways. Every key carries its type, because
SharedPreferences will happily store the same name as a different type tomorrow, and an untyped
import that guessed wrong would corrupt exactly the setting somebody went to the trouble of
carrying over. And an import is a **merge, never a wipe**: keys absent from the file keep their
current value, so loading last month's export cannot delete a setting that did not exist when it
was saved.

Most of the app reads its settings live, so an import applies as it lands; a screen already open
may show its old numbers until reopened.

Fixes [light-reports#137] — no option to export/import settings from BrightControl.
