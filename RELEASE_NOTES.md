## BrightControl v4.5 — a call and a text message, at last, at different volumes

**Volume → Calls apart.** Off by default. Two ways to do it, and you pick one.

This phone gives an incoming call and an incoming text the same loudness, and there is no setting
anywhere that separates them. Not on LightOS, and not on stock Android either. Turn the ringer down
far enough that a work Slack at 11pm does not wake the room and you have also turned down the thing
that is supposed to wake you when somebody calls.

### Why there is no slider for this

Android maps `STREAM_NOTIFICATION` onto `STREAM_RING` on any device that has a radio in it. It is a
resource compiled into the ROM — `config_alias_ring_notif_stream_types` — and it is not a setting, a
secure setting, or anything reachable from an app. This one already grants itself
`WRITE_SECURE_SETTINGS` over its own adb shell and it cannot touch it either. The two names refer to
one number.

This app had already tripped over it from the other side and not noticed: the volume strip has been
dropping duplicate broadcasts since v1.8 because "a notification volume mirrors the ringer". That
duplicate is the alias.

So a notification volume control would be a second handle on the ring volume, which is worse than
having none. What can be separated is not the number but when it applies.

### Calls ring, the rest is silent

Do Not Disturb, with calls on the allow list. Notifications still arrive, still appear in the shade
and still draw on the lock face — they make no sound. Calls ring at the normal ring volume. Nothing
is turned down, so there is nothing to put back, and no timing to get wrong.

It runs as a **Do Not Disturb rule this app owns**, added through `addAutomaticZenRule`, rather than
by writing the phone's global Do Not Disturb switch. The framework combines rules, so a Do Not
Disturb you turned on yourself is not silently overwritten by this one turning off. Where a build
refuses the rule, the global switch is the fallback, and then the four numbers of the policy it
displaced are saved and put back on the way out.

**Alarms, media and system sounds are on the allow list.** A zen policy is a whitelist, and a policy
written to silence text messages and nothing else silences the alarm clock and BrightMusic with them.

Needs `cmd notification allow_dnd`, the same grant the Wi-Fi ringer rules use. The ADB screen has it.

### Two levels

For when notifications should be quieter rather than silent. Two remembered levels: the ring level
goes on when the phone starts ringing, and the other one comes back when the call ends.

**There is nothing to type in.** The volume keys already set this number and always have. All this
adds is remembering which of the two you meant, which the phone can work out from whether it was
ringing at the time. Press them mid-ring and you have set the ring. Press them at any other moment
and you have set everything else. Both numbers are on the screen so you can watch which one follows
the keys.

Three things it will not do:

- **Raise a ringer that is down.** A phone on vibrate or silent is somebody's decision, and writing
  a level into it would unmute it. The feature stands down completely.
- **Argue with the Wi-Fi ringer.** A network marked silent means silent, calls included. A claim
  held by `WifiRinger` outranks a call, and a claim arriving mid-ring unwinds the boost.
- **Re-assert a level you moved.** Turn the ring down while it is ringing and it stays down, and
  that new number is what the next ring uses. Same rule as the speakerphone boost.

**The first moment of a ring can be quiet.** The telephony broadcast and the ringtone start at about
the same instant, so there is a fraction of a second where the old level is still playing. It cannot
be removed from an app — the only earlier hook is a `CallScreeningService`, and LightOS's own dialer
holds that role. It is small, because a volume change applies to a ringtone that is already playing:
the ring comes up to level before the first repeat, which is roughly what a ramping ringer does on
purpose.

### The failure this was built around

A process killed between raising the level and putting it back leaves the phone loud for ever, and
LightOS has no volume screen to discover that on. So the marker is in `SharedPreferences` rather than
in a field, every service bind reconciles it, and a ring that nothing ever ended unwinds itself after
three minutes. The same care is taken with the other mode for the same reason: LightOS ships no Do
Not Disturb screen either, so a phone left in Do Not Disturb by a crashed process could not be got
out of it by any other means. Every bind re-asserts the off state as firmly as the on state.

### Under it

`audio/SplitDecision.kt` is the rule engine, pure Kotlin with 22 tests, in the same shape as
`RingerDecision` — because the interesting cases here are the ones nobody hits until the phone is in
a pocket. `audio/RingerSplit.kt` is the Android side and `audio/QuietNotes.kt` owns the zen rule. The
call state comes from `LockCall`, which this app already runs unconditionally and which reads
telephony, the audio mode and the call notification together, because on this phone no one of the
three is reliable on its own.

Answered is not ringing: a picked-up call puts the level straight back, so a notification arriving
mid-conversation is at the notification level.
