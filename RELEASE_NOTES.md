## BrightControl v3.60 — STOP now actually stops

The STOP button worked exactly as designed and achieved nothing, which is a particular kind of
annoying. Stopping closes the socket, because closing the socket is the only thing that ends a read
blocked with no timeout. And then `runVia` did what it does for *any* dead socket: reset, reconnect,
run the command again. So pressing STOP reconnected the connection it had just closed and started the
command over.

A retry is right for a socket that died on its own and wrong for one somebody killed on purpose, and
nothing in the code could tell those apart. Now it can:

```kotlin
val first = bounded(context, command, timeoutMs, onLine)
if (!first.startsWith(DEAD)) return first
if (aborting) return "error: stopped"   // ← the whole fix
```

`abort()` closes the socket **and says why**, so the retry stands down, a command already blocked
ends, and no further command starts while the stop is in force. The flag is cleared when the next run
begins — including GRANT ALL, which is not a request run and would otherwise have been cancelled by a
stop from ten minutes earlier.

**Which matters more than it sounds**, because GRANT ALL does fail sometimes with nothing to be done
about it: reports #70 and #71 are its first two grants failing back to back with *"the connection is
gone and could not be picked back up"*. Being able to stop that and try again, without force-quitting
and losing the transcript, is the difference between a diagnosis and an evening.

**Still true, and worth repeating:** on this phone right now there is no pairing on disk. The reader
had been reading the Wireless-debugging list rather than the pairing dialog (fixed in v3.59), so no
code was ever captured and no pairing was ever completed. Until PAIR AUTOMATICALLY succeeds, every
grant and every relayed request will keep reporting a connection that is gone — correctly.
