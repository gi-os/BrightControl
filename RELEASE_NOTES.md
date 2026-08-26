## BrightControl v3.63 — the left edge goes back out of the box

Swipe in from the left edge of the screen and the app goes back. **On, without being switched on.**

It shipped off, three releases ago, on the reasoning that this is the one feature in the app which
takes a *touch* rather than a key: a key this service declines is a key the app still gets, and a
touch that lands on the strip cannot be handed back. So it was framed as a decision to be made rather
than a default to be discovered.

That was right about the cost and wrong about the conclusion. **A phone with no back button is broken
in a way that a phone with a 14 dp strip down one edge is not.** An app that pushes a screen and
draws no arrow of its own is a dead end, and somebody who has just sideloaded their first app has no
reason to know the way out is three screens deep in a settings app they have not opened. A default
nobody discovers is a feature nobody has.

What keeps it honest is that the cost is small, visible and reversible:

- **14 dp**, and adjustable — 10, 20 or 28.
- **Off in one tap**, on its own screen under Controls → Edge gestures.
- **Excluded per app**, for anything whose left edge is a control of its own.
- **Gone entirely** with the EVERYTHING OFF switch at the top of the app, like every other key and
  gesture here.
- **A long drag still opens the app switcher**, and both bindings are still yours to change.

**The right edge stays off.** That is now a difference rather than a matching default, and the
difference is the point: the left edge repairs an absence, and the right one adds convenience to
something that already works, since a double press of home opens the same window. An absence is worth
filling. Convenience is worth opting into.

**Nobody's setting is overridden.** Turning the edge off writes a real `false`, so this default only
decides for a phone that has never had an opinion about it. If you switched it off, it stays off.

The first-run guide now mentions it, because a gesture that is on and undocumented is a gesture that
reads as the phone behaving oddly.
