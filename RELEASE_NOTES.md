## BrightControl v3.1 — Wi-Fi login

**The phone can now sign in to hotel and café Wi-Fi — the networks that want a webpage
before they let you through, on a phone that has no browser to show one.**

A captive portal answers every request with its own login page until the page is submitted.
LightOS connects to the network, the portal answers, and there the story ended: nothing on the
phone could draw the page, so the network sat "connected" and useless. Settings → Wi-Fi login
is the missing piece — a WebView pinned to the captive network that loads the portal's page,
lets you sign it (the wheel scrolls it), and closes itself the moment the network lets you
through.

Two decisions carry it. The activity **binds its process to the captive network**, because an
unvalidated Wi-Fi is exactly what Android routes around — unbound, every request would ride
cellular and the portal would never see one. And **success is probed, not inferred**: every few
seconds one request goes to a 204-endpoint over that network, and the day it answers 204 instead
of the portal's redirect, you're through. Portals end their flows a dozen different ways; the
probe is the only signal that means anything.

The settings screen also answers the question the phone otherwise leaves you guessing at,
reading the platform's own capability bits: **Sign-in required** (a portal announced itself),
**Online** (validated, nothing to do), or **Connected, not yet online** — the common quiet case,
where opening the page forces the question.

### The edges

- The system's own "sign in to network" flow lands here too (`ACTION_CAPTIVE_PORTAL_SIGN_IN`),
  and success is reported back through its `CaptivePortal` handle so LightOS marks the network
  usable instead of giving up on it.
- If LightOS ships no WebView, the screen says so instead of crashing, and the settings screen
  carries the workaround: sign in from a computer whose MAC is set to the phone's — portals
  remember devices by MAC.
- Cleartext http is now allowed app-wide. Deliberate: the probe is http *on purpose* (a portal
  can only hijack a request it can read), and portals themselves are routinely http. Nothing
  else in the app speaks http.
