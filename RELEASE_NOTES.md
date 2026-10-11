## BrightControl v4.44 -- the Wi-Fi login screen waits for a Wi-Fi that is still joining

**Opening Wi-Fi login right after the phone joined a network failed at once with "Android refused to route this app over the Wi-Fi network."** light-reports#672 shows the screen finding the Wi-Fi 0.4 seconds after it opened. The network was listed but had no DNS server and was not yet validated, which means the phone was still joining it. The bind failed, the screen looked the network up again 2 milliseconds later, found the same one, and filed the failure. The whole attempt took 4 milliseconds.

Android lists a Wi-Fi as soon as the phone starts joining it, before the network has an address. An app can't bind to it until the platform has finished setting it up, and that takes a moment. The v4.41 retry only helped when the network had been replaced by a new one. Here it was the same network, just not ready yet.

When the bind is refused with no VPN up and the Wi-Fi is still listed, the screen now says "waiting for the Wi-Fi to finish connecting…" and tries again after 0.25, 0.5, 1, 2 and 3 seconds. The first bind that holds loads the login page as before. The probes that check whether the gate has opened wait until then. If every retry is refused, the failure is reported as before, now noting the retries. If the Wi-Fi drops while the screen waits, it says the network is gone. A refusal under a VPN is still reported at once, because waiting doesn't change it. A retry never binds while the screen is in the background; it runs when the screen comes back.

Fixes [light-reports#672] -- could not bind the app to the Wi-Fi network on a Wi-Fi that was still joining.
