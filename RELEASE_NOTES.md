## BrightControl v3.53 — it can switch the debugging service back on itself

**light-reports#63, filed by the phone twenty minutes ago:**

```
Could not run "CLASSPATH=<path> app_process / com.gios.brightoura.helper.Confirm <address>"
the connection is gone and could not be picked back up
```

Two things in one line. The hand-off works — another app asked for a pairing to be confirmed, the
request was accepted, and the command was built correctly. And it never ran, because there was no
shell to run it on.

**Wireless debugging goes off by itself.** A reboot clears it; so does a wander through Developer
options. When it goes, the daemon stops listening and every screen here reports the consequence —
*"the connection is gone"* — which is true and useless. The pairing is still on disk. There is simply
nothing to connect to, and until today the only way back was a cable, which on a phone whose whole
point is not needing a computer is a poor answer.

**So it says which of the two things is wrong, and offers the fix.** `adb_wifi_enabled` is an
ordinary global setting: readable with no permission at all, and writable by anything holding
`WRITE_SECURE_SETTINGS` — which this app granted itself on first run, for the colour writes. So the
ADB screen now leads with the cause rather than the steps:

```
WIRELESS DEBUGGING IS OFF
Nothing here can work while the phone's debugging service is not listening. The
pairing this app already has is kept — there is simply nothing to connect to.
[ TURN WIRELESS DEBUGGING ON ]
```

One tap writes the setting, waits for the framework to act on it, reads it back, and goes straight on
to connecting — the pairing is kept, the port is discoverable, so there is nothing left to do by
hand. If Developer options are off it says that instead, because wireless debugging lives inside
them and no amount of writing will conjure it.

The same button appears on the request screen, as **TURN IT ON AND RUN**, because that is where
people actually meet the failure — an app asked for something, and the reason it could not happen is
one tap away on the screen that reported it.

**Not done silently.** Switching a phone's debugging daemon on changes how exposed it is, and a user
who set this app up for the wheel and the camera button should not find it doing that on its own. It
is a button, it says what it did, and the read-back is what decides — which is also the honest way to
discover the grant has gone missing.

**And a failure now names the command rather than an install hash.** #63's label was sixty characters
of `/data/app/~~9Z7nxY0zTvXG3qtGAFJ-qw==` and none of the verb. Paths come out, the verb and its
subject stay in.
