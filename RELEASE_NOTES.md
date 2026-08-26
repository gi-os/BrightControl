## BrightControl v3.49 — no adb command can hang the app any more

**1/9, 2/9, and then nothing.** The third grant never printed because it never returned, and it never
returned because of this:

```kotlin
stream.openInputStream().use { input ->
    while (true) { val read = input.read(buffer); if (read < 0) break … }
}
```

`runCommand` reads until EOF. A stream that **stalls** rather than closing never reaches EOF, so the
read blocks forever — the coroutine never finishes, the buttons stay greyed, and nothing more is
printed, because a step's line is written *after* its command comes back. From the outside it is
indistinguishable from a button that quietly gave up, which is exactly how it was reported.

Every adb call in the app had that hole. The first two grants are `appops set`; the third is
`pm grant`, and it is only where the hole was first fallen into.

**Every command now has a deadline.** A read that ignores interruption cannot be cancelled, so it is
not cancelled: the command runs on a thread the app walks away from, and closing the socket is what
actually ends the read and lets that thread die. Twenty seconds for anything ordinary, forty-five for
the one command that is *supposed* to sit there — a pairing confirmation, which has to wait for a
request the platform raises several seconds after the bond starts. A check gets eight, because a
read-back that has to be waited on is one that is not going to answer.

Bounded now, not just in the grant batch: the app-op read-back, the hotspot's `svc wifi` calls, NFC,
Shizuku, the command box, and the grant button on the pairing overlay — which also reports
`granting… 4/9` as it goes and stops when the connection goes away, instead of saying "granting…"
forever with a dead button behind it.

**What you should see now.** A stalled command prints `no answer in 20s — the connection was closed`
against the step that stalled, the batch stops there, and the buttons come back. That is a worse
outcome than a grant working and a much better one than a screen you have to kill the app to leave.
