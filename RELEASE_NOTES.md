## BrightControl v4.7 — the switcher where you stand, and a reporter that says it once

**A wheel hold bound to the app switcher now works on LightOS's own screens — which is where you
actually press it — and the automatic failure reporter files one issue per problem instead of one
per retry.**

### The switcher hold, on the dashboard

Bind the wheel's hold to the app switcher and it worked everywhere except LightOS's home screen —
the one place you are standing when you want to switch apps. The mechanism was the hands-off gate:
LightOS's screens keep their keys unless `lightOsScreens` says otherwise, and that gate sits
*before* the switcher-hold special case in the key filter, so the press was eaten upstream of the
code that would have timed it. The setting saved, and then never applied where the thumb was.

The camera button hit this exact shape a year of releases ago, and the answer is the same answer:
one key gets an exception, with its own switch, gated on the binding actually existing. When the
wheel's hold opens the switcher, the click — and only the click — is claimed on LightOS's screens.
The turns stay LightOS's, because claiming turns there is what once made LightOS unstable, and
`lightOsScreens` already claims this same click wholesale without trouble. With nothing bound to
the switcher, nothing changes at all.

The switch lives under **Buttons → wheel click → On LightOS screens**, on by default, and only
appears once a wheel hold actually opens the switcher. The cost, spelled out on the row: a short
press of the wheel there fires your tap binding instead of reaching LightOS.

Fixes [light-reports#136] — app switcher set as toggle from holding wheel does not present
switcher when on LightOS home.

### One report per problem

The ADB plumbing reports its own failures, and that is right — a grant that fails silently is an
evening of reading logs over someone's shoulder. What was wrong was the arithmetic of *again*. The
throttle that keeps one problem from filing twice lives in process memory, and pairing is exactly
the flow people retry across restarts: relaunch, retry, fail, report, each time a first offence as
far as the throttle could remember. Seventeen issues about reading the pairing code. Eleven about
the dialog never appearing. Four problems, forty issues, and somebody has to read forty to learn
there were four.

An automatic report now goes out **once per fault family per install**. The ledger is on disk, so
a restart changes nothing. A repeat still shows its line on the phone — the failure is never
silent — and a shake still files a fresh report deliberately, because a person deciding to report
is a different thing from plumbing deciding to repeat itself.

Fixes the [light-reports] pairing-report flood — the four families now tallied on #217, #220,
#211 and #206.
