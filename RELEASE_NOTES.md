## BrightControl v3.87 — a message from Teams reads as a message, and the box sits square

**A notification box from Microsoft Teams was blank, and so was one from any other chat app.**
The banner and the lock face both read `EXTRA_TITLE` and `EXTRA_TEXT` off a notification and
nothing else. A `MessagingStyle` notification does not fill either of them in. It carries the
conversation under `EXTRA_MESSAGES` and lets SystemUI build the two lines at *draw* time, which is
a step a notification listener never sees. So Teams arrived, the phone woke, the box was drawn on
time, and it said the app's name over two empty rows.

This is the same fault the incoming-call card had with `CallStyle` in v3.34, and it is fixed the
same way. A new `NoteText` reads eight places an app may have written its words, in the order they
should be believed: the conversation first, then the big title and big text, then the base fields,
then the inbox lines, the summary, and last the ticker. The *reading* of the bundle stays in the
listener, where the phone is. The *choosing* is a plain object with eleven tests on it, because a
`Bundle` cannot be built in a unit test and a decision about which of eight fields wins should not
need a phone to check.

Two things fall out of it. A group chat now names the room in the title and puts the sender in
front of the line, because the title can only carry one of them. And a title with a newline in it
is flattened to one line, so the ellipsis lands where the words stop rather than at the first
break.

**A work-profile app was called "teams".** The app's name came from one package-manager lookup,
and a work-profile app is not installed for the user this service runs as, so that lookup throws
and the box fell back to the tail of the package name. It now asks three sources in turn: the
substitute name an app may set for itself, the package manager, and `LauncherApps` against the
notification's own user. A managed Teams or Outlook is exactly the case, and it is the case most
likely to be posting during a working day.

**The box sat too low.** It rested three grid units from the top of the screen against one unit in
from each side, on the argument that it should clear the status bar. It should not: three against
one reads as a rectangle that has fallen down the screen, and the strip it was politely leaving
alone holds a clock the box covers for four seconds and then gives back, which is what a heads-up
notification does on every phone. One unit on all three sides now, and no number in that file the
sides do not also use.
