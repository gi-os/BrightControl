## BrightControl v3.46 — a request arriving finds the page, and a dead socket gets another go

**An app asking for setup while BrightControl was already open got BrightControl's home page.** The
activity is `singleTop`, so a second launch does not run `onCreate` again — it calls `onNewIntent`,
and nothing was listening. The request was parsed exactly once, at launch, and any request that
arrived afterwards was read by nobody. It looked precisely like being ignored, because it was. There
is an `onNewIntent` now, and a request always wins the page: nobody presses a button in another app
in order to look at this one's home screen.

**"Stream closed" no longer takes the retry button away with it.** The daemon drops its listener
when you leave the Wireless-debugging screen, so the first press after setting anything up regularly
lands on a socket that is already gone. Nothing runs, which is fine — but the screen answered that
by clearing the results and deciding it was *not connected*, which replaced TRY AGAIN with a trip
back to ADB setup that had nothing to do there. The pairing is on disk and the port is discoverable;
the next press usually just works. So it says what happened, in those words, and leaves TRY AGAIN
exactly where it was. Twice in a row means wireless debugging is off, and it says that too.

**The commands that had no retry at all now have one.** NFC, Shizuku, the advanced command box and
the grants the automatic pairing applies all went straight at `runCommand`, so a dead socket was
reported as the command failing. They go through `runVia` now: one reconnect, one retry, and only
for the IOException that means the connection went away — never on the strength of what a command
printed, because `shell:` carries no exit status and printing something is not an error.

**And a request is set aside the moment it is known it cannot run yet**, rather than when somebody
taps GO TO ADB SETUP. People leave with Home. A request lost that way has to be asked for again from
the app that sent it, and when it carries a Bluetooth address that means a rescan, because the
address has rotated by then.
