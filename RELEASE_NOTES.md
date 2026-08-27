## BrightControl v3.84 — the edge strips reach the apps that were quietly refused them

**Edge swipes never worked in BrightMusic, and the reason was its name.** The strips ask one
question before they go up: is this app one of Light's? That question was answered by the wheel's
hands-off list, a list of package prefixes, and two of those prefixes are `com.thelightphone.` and
`com.lightphone.`. BrightMusic ships as `com.lightphone.spotify`, an id it inherited from the phono
fork and cannot change without stranding every installed copy. So the strip was refused in front of
it, silently, for looking like Light software.

No software of Light's ships under `com.lightphone.` at all. Light keeps its tools inside
`com.lightos` and its keyboard is `app.lightphonekeyboard`. Every package known under that prefix is
an ordinary sideloaded app: BrightMusic, and Audiobooks, Chats and Passes. Four apps with no back
button, refused the one gesture that gives them one, on the strength of a name.

**The edge strips have their own table now.** The wheel's list answers a different question, which
is whose keys are not ours to reinterpret, and it was already carrying a hand-written exception for
BrightMusic for the same reason. Sharing it a second time repeated the same mistake in a subsystem
that had no exception.

What the new table still refuses, and why each one already has a way back:

- **LightOS.** It has a gesture-navigation switch in its own settings, on the same edges. A strip
  over the top would be two gestures on one edge and ours is the weaker of them.
- **The light-sdk tools**, `com.thelightphone.*`. This one is a real refusal rather than an
  inherited one. An SDK tool subclasses `LightScreen` and navigates with `navigateTo`, so the SDK
  draws it a back button and the Android back stack is not what it moves through. A strip there
  would take an edge and hand back nothing.
- The keyboard, SystemUI, and the AOSP launcher and camera, unchanged.

Nothing about the wheel moved. A test now pins both halves, because the two tables agreeing was the
only thing holding them together.

## BrightControl v3.83 — a call from a number the phone cannot name, the charging bolt, and a panel that lights for a notification

**A call from an unknown or withheld number never reached the lock face.** Three things can tell
this app the phone is ringing, and for that one call all three said nothing. The dialer posts no
notification — LightOS's is a system app that raises its own activity — so the shade had nothing.
The audio mode does not reliably move to `MODE_RINGTONE` for a call the ringer is not going to play
out loud. And telephony, the one source that always speaks, was being thrown away: the
`ACTION_PHONE_STATE_CHANGED` receiver read the number off the broadcast and returned early when it
was empty, which is exactly what an unknown caller looks like. **The whole ring was dropped because
the caller had no name.**

The state and the number are now two separate facts. RINGING is published whether or not a number
came with it, and the card is drawn on the strength of the ring alone. Where the number really was
withheld — telephony announced the call, the grants are in place, and no number arrived — the card
says **Unknown number** rather than "Incoming call" under a heading that already reads INCOMING
CALL.

A ring that only telephony is asserting expires after two minutes. The IDLE broadcast is what ends
one, a broadcast can be missed, and nothing else would contradict it: a card stuck on the lock
screen until reboot is a worse bug than the one being fixed.

The stage decision is its own object now, `CallStage`, with tests. It has been wrong twice and
neither time was there anything to test.

**The battery showed no bolt while charging.** It asked `BatteryManager.isCharging`, which is not
the question it looks like: that call goes to battery *stats*, which means the run of charging it
has decided to count, and applies hysteresis before saying yes. Plug a phone in and it stays false
for a while. Plug one in at full and it can stay false altogether. Those are the two moments
somebody plugs a phone in and leaves it, and LightOS's own status bar drew a bolt the moment the
phone was unlocked, so ours read as broken. It now reads the sticky `ACTION_BATTERY_CHANGED` —
`EXTRA_PLUGGED` for a cable, `EXTRA_STATUS` for a dock or a pad — which is immediate and true.
`isCharging` stays underneath as a fallback. The level falls back to the same broadcast too.

**The phone now lights up for a notification with banners off.** The wake was gated on banners
being on, and banners are off by default — so a phone running the Light lock face and no banner
never lit for anything. A message arrived, the shade took it, and you found out at the next press
of the power button. That is not what a lock face is for.

Wake the screen has moved out from under Banners in **Notifications** and now covers both: with
banners on it is what it always was, and with banners off it turns the panel on and the lock face
comes up carrying the notification as a row, with no box over the top of it. It is on by default
and does nothing unless there is something to land on — with banners *and* the lock face both off
the row says so and stays dim, because waking to LightOS's own lock screen is what picking the
phone up would have shown anyway.
