## BrightControl v4.0 — pick the Home app, rather than the phone guessing it

**Two releases were spent trying to work out which app the switcher's Home row should open. Both got
it wrong for somebody.** v3.97 read it off the home button's tap binding; v3.98 added a rule on top —
if the role holder is LightOS and exactly one other launcher is installed, use that one. Each was
defensible and each was a fallback nobody could see the output of.

There was never a signal here worth deducing from. **LightOS holds the HOME role on every one of
these phones**, because it has to or it crash-loops, so the role says nothing at all about the
launcher you actually use.

### It is a list

**Buttons → Home button → Home app.** Tap the app you want. That is the whole rule, and the row on
the Buttons screen names whatever it resolved to, so where Home goes is never something you find out
by opening the switcher.

The list is launchers first, and it is built from `CATEGORY_HOME` **and** `CATEGORY_LAUNCHER`
together — a launcher publishes the first and need not publish the second at all, which is why Luma
appears in no other app picker in this app. On the one screen whose job is choosing a launcher, that
had to be fixed rather than worked around.

Picking LightOS gets LightOS's own action rather than a plain launch, so arriving there is still a
*visit*: the state where the home button belongs to LightOS while you walk through its menu.

### Show Home

**Buttons → Home button → Show Home** is the toggle. Off, and there is no pinned row — your launcher
is listed by its own name and icon like any other app. On is the default.

### Smaller things

- **Unset means the system's home**, which is what a plain home intent does. Honest, and one screen
  away from being changed.
- **An uninstalled choice falls back** to the system's home. The setting keeps your answer, because
  a package can go away after you pick it, but the row will not sit there opening nothing.
