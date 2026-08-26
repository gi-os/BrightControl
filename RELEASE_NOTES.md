## BrightControl v3.43 — an app may ask the shell to answer a pairing request

**Nothing on this phone can pair a device that asks for consent.** The bond raises a
`PAIRING_VARIANT_CONSENT` request, the request becomes
`com.android.settings/.bluetooth.BluetoothPairingDialog`, and LightOS's pairing fragment builds a
**null** dialog for that variant and dies in `DialogFragment.prepareDialog`. With the screen off
the platform posts a notification instead — and its "Pair & connect" button fires
`ACTION_PAIRING_DIALOG`, which starts that same activity and takes the pairing service down too.
Three routes, one dead end. It is why the Oura ring will not pair, and why the iPad this app's own
hotspot trigger waits for would not pair either.

**`setPairingConfirmation` answers with no UI at all, and the shell is allowed to call it.** The
permission is `BLUETOOTH_PRIVILEGED` — `signature|privileged`, so no sideloaded app will ever hold
it — but `com.android.shell` has it granted, along with `BLUETOOTH_STACK`. This app has held an adb
shell since v1. So there is now a third verb beside "start shizuku" and "repair settings":

```
confirm pairing AA:BB:CC:DD:EE:FF
```

and what runs is written here:

```
sh -c 'CLASSPATH=<the requester's own APK> app_process / <requester>.helper.Confirm <MAC> 24000'
```

**One thing crosses over, and it is six hex pairs.** The pattern admits hex and colons; a line with
a path, a space, a quote or a second command on it does not match at all, and `matchEntire` is what
stops `confirm pairing AA:… ; rm -rf /` from being a MAC address with a tail. The `CLASSPATH` is
the APK path *this phone* resolved for the requesting package, and the class is
`<that package>.helper.Confirm` — so an app can only run code it already shipped and you already
installed. What it gains is the uid, for the length of one command, to do the one thing the request
names. A package with no installed code is refused with that as the reason rather than guessed at.

And it still asks. The consent screen shows the built line before anything runs, and the bond it
answers is one you were already trying to make — this replaces a dialog you cannot answer, not one
you never saw.

Four new tests: the command is pinned to the requester's own APK and class, only a MAC gets
through, a lower-case address comes back rebuilt in upper case, and an uninstalled package is a
refusal rather than a path.
