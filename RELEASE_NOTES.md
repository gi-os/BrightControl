## BrightControl v4.12 — the Wi-Fi login screen keeps a log and reports its own failures

One screen, and it is the one that does not work: Wi-Fi login, the captive-portal page LightOS
has no browser for. Signing in from LightOS Settings always fails, on purpose, so this screen is
the only route through a hotel or café network — and on the phone it fails too, and nobody could
say why. Its whole account of itself was one status line. This release makes it explain itself.

### What it writes down

From the moment it opens, the screen keeps a timestamped log of everything it learns:

- the WebView provider on this phone (package and version), or the exception constructing one threw,
- LightOS's `Settings.Global.captive_portal_*` values — `captive_portal_mode = 0` would mean the ROM
  has told Android never to look for login pages, which is exactly "connects and then goes nowhere",
- every network the system knows, with its transport, INTERNET / VALIDATED / CAPTIVE_PORTAL bits,
  interface, DNS servers and proxy, and which one is the default,
- which Wi-Fi it picked and why, and what `bindProcessToNetwork` returned,
- every probe: HTTP code, `Location` redirect, server header and round-trip time — or the exception,
- every page the WebView started, finished, navigated to or errored on, main frame or subresource,
  HTTP errors, SSL errors, the renderer dying, and the page's own JavaScript console.

### When it reports

The failures it can recognise by itself file the log to light-reports without asking: no WebView,
no Wi-Fi network, the bind refused, the login page not finished after 25 s, five probes in a row
throwing rather than being redirected (nothing answers over this Wi-Fi at all), or the renderer
dying. Once per opening of the screen, and never more than one every ten minutes across openings —
the gap lives in prefs so a relaunch is not a fresh first offence. Leaving the screen after fifteen
seconds without getting through also files once per install, through the same ledger the pairing
reports use. The send unbinds the process first: the report should not have to ride the network
that does not work, though it queues to disk either way.

### On the phone

- **LOG** shows the log over the page, scrolled by the wheel; **SEND LOG** files it by hand, whatever
  the throttles say.
- A portal that redirects to an https page with a certificate for the wrong name used to be a blank
  page. It now says so and offers **LOAD IT ANYWAY**.
- The Wi-Fi login settings screen gained a THIS PHONE section: whether a WebView exists, and whether
  LightOS has login-page detection on, off, or set to avoid.

### For the next report

The log is in the report body under "What the app itself reported". Read the first dozen lines
first — WebView provider and `captive_portal_mode` decide whether anything below them could have
worked.
