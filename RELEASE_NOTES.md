## BrightControl v3.73 — the switcher shows each app's icon

The recents list was a column of names. Names have to be read, and the app switcher is the one
screen in this app nobody looks at — it is passed through, in the second between deciding to leave
an app and leaving it. So each row now carries **the app's own icon, inline, ahead of its name**:
the part of an app you already know by shape, doing the work the reading was doing.

It costs nothing on the screen. The icon is drawn **exactly one line tall**, tied to the type beside
it rather than to the grid, so a row with an icon in it is the same height as a row without — which
means the same number of apps still fit, and the app furthest back (the one a switcher is actually
for) does not get pushed below a fold this list deliberately cannot be scrolled past.

The selection still reads the same way. Brightness is the only thing this screen has to say "here",
so **the icon dims with the name it belongs to** — full strength on the selected row, the same grey
as the text everywhere else. Eight icons at full strength would be eight bright things arguing with
the one that means something.

Two small things behind it:

- **Icons are loaded once and held.** A resource read per app, on the main thread, in the moment the
  window opens is the one place in this app where a few milliseconds are the whole feature.
- **An app whose icon will not load gets an empty box the same size**, so its name still lines up.
  A row that shuffles left because its icon was missing is worse than a gap.

Nothing else changed: the wheel still moves, a click still opens, a hold is still App info, home
still closes.