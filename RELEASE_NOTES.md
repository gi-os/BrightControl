## BrightControl v4.45 -- the Wi-Fi login screen waits for a Wi-Fi that disappears mid-handoff

**Opening Wi-Fi login while the network was being replaced failed at once with "Android refused to route this app over the Wi-Fi network."** light-reports#686 shows the screen opening on a network that was already torn down: it had no capabilities and no link properties, and it was gone from the system's network list, so the bind failed. No Wi-Fi was listed at that instant, so the screen skipped the retry it runs for a Wi-Fi that is still joining and filed the failure. A moment later a Wi-Fi was listed again, the replacement for the same network, but the screen had already given up.

A network list sampled twice in the same millisecond can say two different things while a Wi-Fi is tearing down and rejoining. The screen no longer treats one empty lookup as a verdict: when the bind is refused with no VPN up, it waits and tries again on the same schedule as before (0.25, 0.5, 1, 2 and 3 seconds), looking the Wi-Fi up afresh on every attempt. A Wi-Fi that never comes back is still reported as gone once the retries are spent. A refusal under a VPN is still reported at once, because waiting does not change it.

Fixes [light-reports#686] -- could not bind the app to the Wi-Fi network (bindProcessToNetwork returned false, no VPN up) on a network that was torn down and rejoining.
