## BrightControl v4.41: the Wi-Fi login screen retries the bind on the current Wi-Fi when the network it was handed is already gone

**Wi-Fi login could not bind the app to the Wi-Fi network (bindProcessToNetwork returned false, no VPN up).** light-reports#640 is the shape: the network the system's captive-portal notification handed to the screen was already dead by the time it was used, missing from the network list with no capabilities and no link properties, and the screen blamed Android for refusing to route the app.

**One fresh look for the Wi-Fi and a second bind before giving up. When there is no Wi-Fi left at all, the screen now says so.**

When the first bind fails and no VPN is up, the screen no longer trusts the network it was handed. It looks up the current Wi-Fi again, the same way it does when opened by hand, and if that turns up a different network it binds to that one instead and carries on with it for the page, the success checks and the log. If no Wi-Fi exists anymore, the message tells the user the network is gone and to reconnect in Settings, rather than reporting a routing refusal that never happened. A failed bind under a VPN is handled exactly as before.

Fixes [light-reports#640]: Wi-Fi login could not bind the app to the Wi-Fi network (bindProcessToNetwork returned false, no VPN up).
