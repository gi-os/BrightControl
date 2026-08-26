## BrightControl v3.70 — the key service comes back after a force-quit, and thirty reports become one

**"When I force quit the app, the key service stopped working."** That is real, and it is the worst
bug of the evening, because it was caused by the previous ones: a screen that looked stuck got
force-quit, and the price of force-quitting was the wheel.

Force-stop kills the accessibility service and Android then **refuses to rebind it** — the package is
flagged stopped, and nothing belonging to a stopped app starts until a person launches it by hand.
Nothing was switched off, so every screen in here kept saying the service was enabled while the wheel
and the camera button did nothing. *Enabled* is a setting; *bound* is a fact, and only force-quitting
can make them disagree.

**So launching the app now brings it back.** Launching clears the stopped flag, which is the moment
the system will act on the enabled-services list again — so the list is rewritten, our entry out and
straight back in, and the framework binds what it finds. One line says so and goes away on a tap:

```
The key service had stopped — brought back.
```

Two writes, and every other service's entry preserved exactly as written, short forms included:
writing this setting carelessly is how an app switches off somebody's password manager or screen
reader, which is a much worse thing to do than leaving a wheel broken. Without the secure-settings
grant it cannot be done at all, and it says that instead of pretending.

The service only gets poked when it *should* be running and is not. A service genuinely switched off
stays off, and a live one is left alone — rewriting the list under a running service tears it down
and rebuilds it, which throws away the recents the switcher holds in memory.

**And the flood: thirty issues in a few seconds.** Auto-sending was right; making the message name
the command was not. Nine grants failing on one dead socket are nine *different* messages, so every
one looked like a first offence to the hourly throttle. Reports #81 to #113 are one problem, thirty
times over, and thirty reports of one problem are worse than none because somebody has to read all
thirty to find that out.

Two changes: the plumbing no longer files anything — the failure is returned, and whoever ran the
batch reports once with the count and the steps — and there is a hard floor of one minute between any
two reports whatever they say.
