## BrightControl v3.89 — the ringer follows the Wi-Fi, and the volume strip is a bar again

### The ringer, by network

**Volume → Ringer by Wi-Fi.** A network is a place. Mark the ones where this phone should be silent
and the ones where it should ring, and joining one sets the ringer. A network you have not marked is
never touched, which is nearly all of them.

On a phone with no profiles, no automation and no Do Not Disturb schedule, the ringer is a switch
you remember to flip and then forget to flip back. That costs a morning of missed calls about once a
month, and it is the kind of thing a phone should know without being told twice.

Two things here are load-bearing and neither of them is the rule:

- **Only a silence this app applied is ever undone.** A phone you muted by hand is not this app's to
  unmute, so the network the silence was applied for is written down, and the ringer comes back only
  when that network is behind you.
- **Turning the ringer up by hand beats the rule.** Do it while standing on a silent network and the
  rule stops applying until you leave. Without that, the next Wi-Fi capabilities change — and there
  is always a next one — would put the phone back to silent, which reads as a broken ringer rather
  than as a setting.

The list of networks is built by remembering, because nothing unprivileged can enumerate the
networks a phone has saved. Networks appear in that screen as the phone joins them, whether or not
the feature is on, so it starts nearly empty and fills up over a week. The screen says so.

Off by default, and it needs two grants LightOS has no screen for — both already in the ADB
screen's batch, and both reported on the settings screen rather than assumed:

```
adb shell cmd notification allow_dnd com.gios.lightcontrol
adb shell pm grant com.gios.lightcontrol android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.gios.lightcontrol android.permission.ACCESS_BACKGROUND_LOCATION
```

Muting a phone is a Do Not Disturb operation as far as Android is concerned, so without the first a
silent rule does nothing at all and ring rules still work. Since Android 10 the network's *name* is
redacted from any app that cannot locate the phone, so without the other two no rule can match.
Nothing here reads a location, and only the names you write a rule for are stored.

### The volume strip

**The bar is one bar.** It was notches — one box per press, with a gutter between them — on the
argument that a discrete control deserves a discrete bar. On a black strip the gutters *are* the
background, so what the eye actually read was a row of black lines through the bar, and at fifteen
media steps they were most of it. The level is still exact; nothing draws the gap.

**The percentage is gone.** A number that changes on every press reads as the thing to watch and it
is the wrong thing — the bar already says roughly how loud, and the label's job is saying which
volume the keys are moving. It was also a lie about precision: a seven-step ringer cannot be at 43%.
VIBRATE and SILENT stay, because those are states rather than numbers.

**Tapping the strip opens a list of every volume this phone has** — media, ring, notifications,
alarm, system, tones, speech, and the call stream during a call — each showing where it currently
sits. Tap one and the keys move that one. It used to be a cycle: one tap, one stream, so the alarm
was three taps past media, each tap left the keys pointed at something you were only passing
through, and all of it happened inside a strip that vanishes after a second and a half.

### Both volume settings now ship off

**Show the level** and **Tap to pick a stream** were both on by default and are now off. The strip
draws a window over whatever you are looking at, and the selector is the one setting in this app
that lets a volume key be *consumed*. Neither belongs in the set of things that happen to you before
you have asked.

If you were using them, they are in **Volume** and are one tap each. This is the only thing in this
release that takes something away, and it is deliberate.
