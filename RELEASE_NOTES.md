## BrightControl v4.17 — the pairing reader stops mistaking the QR screen for the code dialog

The one-tap pairing helper was filing a false "could not read the pairing code off the dialog"
report against the QR pairing screen. The report named the screen it actually saw — *Scan QR
code / Pair device over Wi-Fi by scanning a QR code* — which is the smoking gun: that screen says
"pair" and "code" but has no six-digit code to read, because the phone pairs by scanning the QR.

### What changed

`AdbPairCode.looksLikeTheList`, the test that keeps Settings screens without a code from being
taken for the pairing dialog, already excluded the Wireless debugging list — the screen that
produced the same false report back in #65 and #68. The QR screen slips past it because its
wording contains "pair" and "code" without any of the list's telling phrases. The test now also
rejects any screen that says "QR", so the QR pairing screen is left to the sweep-and-navigate
path instead of being filed as an unreadable dialog.

Fixes [light-reports#264] — the pairing reader mistook the QR pairing screen for the code dialog
and filed "could not read the pairing code" against a screen with no code on it.
