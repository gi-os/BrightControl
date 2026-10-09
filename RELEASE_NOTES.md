## BrightControl v4.42 -- the pairing walk starts from the top-level Settings screen

**Could not find the pairing dialog in 90 seconds: the reader was armed while the phone sat on the top-level Settings screen, and the walk never engaged.** light-reports#654 lists the windows seen while waiting: Settings, Network & internet, Connected devices, Security, System, About phone, Developer options. The user went through Settings by hand for the whole window and nothing was tapped for them.

The walk only knew how to move from Developer options onward. On the main Settings list there is no pairing row, no Wireless debugging row and no Developer options row, so it decided there was nothing to do and waited. The System screen had a second problem: it lists Developer options as a row, and the walk took that as a sign it was already on Developer options and scrolled a list that would never show Wireless debugging.

The walk now recognizes both screens by their heading, the first line the reader sees, together with rows that only those screens carry. On the main Settings list, headed "Settings" and showing both "Network & internet" and "About phone", it taps System. On the System screen, headed "System" with a Developer options row, it taps that row. From Developer options the existing walk takes over as before. Like the rest of the walk, these matches use English labels.

Fixes [light-reports#654] -- could not find the pairing dialog in 90 seconds; the walk never engaged on the top-level Settings screen.
