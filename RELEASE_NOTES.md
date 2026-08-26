## BrightControl v3.47 — stop asking the socket and just send the command

**NFC ON worked and GRANT ALL said the connection was gone, on the same socket, in the same
minute.** That pair of facts is the whole bug. NFC asks nothing: it sends its command. GRANT ALL and
the request screen both called `ensureAlive()` first and refused to run on the answer — and
`ensureAlive` proves a connection by sending a command of its own, down a socket that may have been
connected milliseconds earlier. **The first command on a new socket is the one that dies.** This file
already knew that about batches, in a comment, and then trusted a single probe against it anyway.

So a working connection answered "no", and the two screens that asked reported that nothing ran,
while every screen that simply sent a command was fine.

Three changes, and they all point the same way — state the intent, then ask the phone what happened:

- **The probe is asked three times**, a fifth of a second apart, after any reconnect. Free on the
  path that works; removes the settling race instead of reporting it as a broken setup.
- **A "no" no longer stops the run.** Both batches keep the answer as a warning and run the steps
  regardless — each step reconnects and retries on its own, and what the phone says about each grant
  afterwards is a better answer than what a probe said about the socket beforehand.
- **"Nothing got through" is now a claim about the run**, not about a prediction. It appears when
  every step failed, and it says what to do: press TRY AGAIN, and if it happens twice, wireless
  debugging is off.

GRANT ALL logs the probe's opinion when it disagreed with reality — *"the connection did not answer
a probe first — ran anyway"* — because a probe that keeps being wrong is worth seeing, and it is no
longer allowed to be the thing that decides.
