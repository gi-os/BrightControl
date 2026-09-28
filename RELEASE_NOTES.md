## BrightControl v4.40: the pairing reader gives the dialog a second window instead of failing a user still in Settings

**The 90-second pairing window expired on a user who was still looking for the box.** light-reports#540 is the shape: the reader saw Developer options and Wireless debugging but the "Pair device with pairing code" box was never opened, so the window ran out on someone mid-flow and filed a failure.

**One automatic second window when the user is evidently still in Settings.** If the dialog was never seen but Settings screens were, the reader now re-arms itself once for another 90 seconds instead of failing, with a fresh scroll budget and the screens seen so far kept for the report if the second window also expires. If the reader's sweeps stop partway through (the helper switched off, or its process gone), the report now says so instead of listing screens.

Fixes [light-reports#540]: the pairing window expired before the "Pair device with pairing code" box was ever opened.
