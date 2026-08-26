## BrightControl v3.62 — STOP gives the buttons back immediately

*"When stop happens we should be able to press the buttons again — that's the main goal."* That is a
different requirement from the one the last two releases were solving, and it is the right one.

Every version so far treated stopping as a request the work had to *notice*: set a flag, close the
socket, wait for the loop to agree. That cannot give the buttons back, because a command is blocked
in a read with a deadline of up to forty-five seconds and a lookup already inside mDNS discovery
cannot be interrupted at all. The screen stayed exactly as stuck as before, which is why STOP got
reported three times.

**So the run is declared over the moment you press it.** The socket closes, nothing further starts,
the phase goes to Done, and the buttons come back — while whatever is still unwinding in the
background is *abandoned* rather than awaited.

**Abandoned work must not come back to haunt the next attempt**, and that is the only subtle part. A
stopped run's steps keep going for as long as their deadlines take, and nothing stops their results
arriving a minute later, on top of a run you have since started. Each run now carries a generation
number, bumped by both starting and stopping:

```kotlin
fun finished(generation: Int, results: List<StepResult>) {
    if (generation != this.generation) return   // a question nobody is asking any more
    …
}
```

Late results from a stopped run are dropped on arrival. Press STOP, press RUN again straight away,
and the second run owns the screen — no crossed wires, no results from the run you cancelled
appearing under the one you did not.
