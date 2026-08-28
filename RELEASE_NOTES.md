## BrightControl v3.99 — Home in the switcher stops resolving to LightOS

**v3.97 pinned Home to the bottom of the switcher and then sent it to the wrong place.** The row read
its destination from the home button's tap binding, on the reasoning that home is whatever a single
press reaches. That is true, and it was not enough.

The shipped tap is a plain `CATEGORY_HOME` intent, and that reaches whoever holds the HOME role.
**On this phone the HOME role is always LightOS** — it has to be, or LightOS crash-loops. So for
everybody who had not deliberately re-bound their home button, which is everybody, Home resolved to
LightOS. Faithful to the binding, and useless if the launcher you actually use is Luma.

### The launcher you installed is the better signal

A tap bound to a package still wins outright, and so does one bound to LightOS. Nothing changes for
anyone who has already answered this question with a binding.

Otherwise: when the role holder is LightOS and there is **exactly one** other launcher installed,
the row goes to that launcher — and by launching it, not by firing a home intent that would go
straight back to the role holder. Nobody sideloads a second launcher onto a Light Phone III by
accident, and on this phone the role carries almost no information.

Two other launchers is a question this cannot answer. It falls back to the system's home rather than
guessing between them, and binding the tap is the way out — that is the only answer that cannot be
wrong about which launcher a person means.

### The setting says where Home is going

**Buttons → Home button → Home is pinned** names the app it resolved to instead of reading ON. A
rule with a fallback in it should not leave "where does this actually go" as something you find out
by opening the switcher — v3.97 shipped resolving to LightOS for nearly everybody, and nothing
anywhere said so. The switcher's log line carries the same answer.

### If you want the button to agree with the row

Point **Buttons → Home button → Tap** at your launcher. The row follows a named binding exactly, and
so does the press — which is the configuration where home means one thing rather than two. Holding
home still reaches LightOS's dashboard, as it always has.
