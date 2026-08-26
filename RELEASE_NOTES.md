## BrightControl v3.68 — the switcher hold goes off in your hand

From light-reports#79, hand-typed on the phone: *"current launch of app switcher using press of wheel
relies on release of wheel. this can lead to accidental torch toggle if not held long enough. maybe
possible to have switcher launch after wheel is held for certain amount of time?"*

Yes, and it should have been that from the start. Every action here decides at the **release**, for a
reason worth keeping: an action that fires mid-press hands the rest of the press to a foreground that
has changed underneath it, and one hold of home used to bring LightOS's dashboard over and then carry
on into its menu.

But that objection is about actions that bring *something else* forward. The switcher is this app's
own overlay — and the cost of deciding at the release is that letting go a fraction early does not
mean "no switcher", it means **flashlight**, with no way to tell from the outside how long is long
enough.

So the switcher hold now fires at the threshold, while the wheel is still down. It announces itself,
and the release has nothing left to do: the hold owns the press whole, so no tap follows it and the
torch stays off. Release *before* the threshold and the timer is cancelled — a short press is still a
tap, and nothing was shown.

It is the only hold that behaves this way, deliberately. Everything else still decides at the
release, where the mid-press objection stands.

**Still open, from #80:** *"launch of app switcher using wheel hold does not appear to work
consistently within every app, possible conflict with lightos?"* Firing at the threshold makes the
timing deterministic, which removes one explanation — but if some apps never deliver the wheel's
DOWN at all, no timing helps. That needs the key log: Diagnostics → log keys, then hold the wheel in
an app where it works and one where it does not. Two traces and it is answerable.
