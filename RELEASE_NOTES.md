## LightControl v1.5 — Resume no longer costs you your home screen

**"Back to where you were" now has a configurable second destination. Press home once after a
wake and the app comes back; press again and you get whatever you choose — the default
launcher, or an app like Luma.**

v1.4 shipped Resume with plain home hardcoded as the fallback, which was wrong in a way that
only shows up on this phone. LightOS has to keep the HOME *role* or it crash-loops, so pointing
the home *button* at a different launcher is the only way to actually get a different home
screen — and "home" therefore resolves to LightOS, not to the launcher you use. Anyone whose
home tap was set to Luma and who then bound Resume over it would have silently lost their home
screen in exchange for the new feature.

So Resume no longer *replaces* the binding it sits on, it **wraps** it. Under **Resume apps**
there is now an **Otherwise open** row: Home, or any launchable app. Set it to whatever the tap
used to be and the feature becomes purely additive — the app comes back when there is one, and
every other press does exactly what it always did.

That fallback is what most presses hit, which is the point. Nothing slept in, nothing ticked,
already looking at the app in question, offer already spent: all of them land there.

An app chosen here that is later uninstalled falls through to home rather than doing nothing,
the same as any other launch binding.
