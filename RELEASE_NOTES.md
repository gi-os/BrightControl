## BrightControl v4.39 — the Wi-Fi login page no longer reports itself as not loaded when it is on screen

**A portal that rendered fine filed "could not load the Wi-Fi login page within 25s".** The report
carried the log, and the log told the story: the page started, navigated through a redirect, drew,
and set its title — "Aislelabs" — at 4.7 seconds, and the screen never "finished". Aislelabs splash
pages keep their sockets open and never signal the load as done, so the `onPageFinished` callback
that the watchdog waits for never came. At 25 seconds the watchdog fired anyway and filed a report
against a login page that was sitting on the screen the whole time.

**A page title arriving is the page having drawn.** The watchdog now accepts the first title the
WebView receives as proof the login page came up, exactly as it accepts `onPageFinished`. A portal
that sets its title and then holds its connections open is no longer reported as unloaded; a page
that truly never draws still raises the watchdog after 25 seconds, unchanged.

Fixes [light-reports#519] — a mall's Aislelabs portal drew its login page but never fired
`onPageFinished`, so the 25s watchdog filed "could not load the Wi-Fi login page" against a page
that was on screen.
