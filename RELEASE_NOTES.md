## BrightControl v3.39 — an app may now ask to fix a broken system app

**One request was refused that should not have been.** BrightOura asked this app to reset the
Settings app, and got the loud no that a request naming somebody else's package is supposed to get:
*an app may ask to set up itself and nothing else.* That rule is right almost everywhere, and it was
wrong here. On this phone Settings crashes on the Bluetooth pairing screen — so nothing pairs. Not a
ring, not the tablet this app's own hotspot trigger waits for. The fix is one command a shell runs
in a second, and one a person with no computer cannot run at all. Refusing it did not make the phone
safer, only quieter about being broken.

**So there is now a second thing a request may ask for that is not about itself.** `repair settings`
— or `repair bluetooth`, or the `pm clear` line a README would print — and nothing else. It is
written the same way "start shizuku" is written, as a **word rather than a command**: the request
carries the word, the word is looked up in a fixed table of two system packages, and the commands
that run are built here from the table. The package in a `pm clear` line is matched only so it can
be checked and thrown away. Nothing typed by the sender reaches the shell.

What that buys, precisely:

- **No third-party package is nameable.** The escalation this file exists to stop is one app
  reaching another app, and it still cannot happen. `pm clear com.whatsapp` is refused, by name, and
  the refusal says which package it turned down.
- **Two verbs, and one of them is an un-break.** `pm uninstall`, `pm disable`, and `pm grant` naming
  those same packages are all still unreachable.
- **What is lost is a system app's own settings.** Not your files, not an account, not an installed
  app. Settings rebuilds its state the next time it opens, which is the whole reason to run it.
- **Enable comes before clear.** A package that whatever broke it also disabled will refuse the
  clear outright, and then the run reports a failure for the step that was meant to be the fix.

**The consent screen says so out loud.** It used to promise that every line names the app in front
of you — a promise it can no longer make for these two, so it does not make it. A repair step is
labeled as touching another app, above a paragraph that says what a reset actually costs, and
nothing runs until you say so. A request is still all or nothing: one line nobody can parse and the
whole thing is turned down.

Five new tests hold the shape: the two commands are pinned whatever the request said, the word and
the command line come out identical, only the phone's own system apps are reachable, no other verb
can be smuggled onto them, and a refusal names the package it refused.
