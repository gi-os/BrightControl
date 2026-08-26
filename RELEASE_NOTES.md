## BrightControl v3.74 — BrightNotebook was drawing a second box

Banners knew about two apps with a heads-up box of their own, and there were three. BrightNotebook
has had a reminder box since long before this feature existed, and nothing here named it — so from
v3.65 to v3.71, a reminder coming due drew BrightNotebook's box and then this app's over the top of
it. The only symptom was two boxes, which is the one thing the stand-down arrangement exists to
prevent.

It is on the list now. BrightNotebook v1.53 is the other half; both are needed.

Worth saying plainly, because it will happen again: **an app this one has never heard of keeps
drawing its own box.** The list is named rather than discovered — walking every installed app
looking for one that might listen would be a permission and a guess to save three lines — so a new
app with a box of its own has to be added here.


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