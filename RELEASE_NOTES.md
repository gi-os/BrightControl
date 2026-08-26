## BrightControl v3.52 — the pairing code is read in every shape it comes in

**light-reports#61, from a real phone, an hour ago:** *"pairing box present but numbers within not
detected."* That is the auto-pairing reader finding the dialog and failing to read the six digits off
it — which is why pairing has been a fight all evening, and why every route past it has been
work-arounds.

The reader had one pass: a line that is *exactly* six digits. That is the shape AOSP renders, and it
is not the only shape there is. So the digits are on that screen looking like something else, and the
somethings are enumerable — there are five passes now, tried in order of how much each one proves:

1. **Six digits alone on a line.** Unchanged, and still the only pass strong enough to need no
   corroboration.
2. **Grouped.** `123 456`, `123-456`, and the non-breaking and thin spaces Android renders instead of
   a plain one. Six digits once the separators come out, and nothing else on the line.
3. **One digit per view.** A row of six views flattens to six lines; consecutive single digits are
   joined, so a digit from a label at the top and five from the bottom cannot be read as a code.
4. **Beside its label.** The digits following the word "code", on that line or the two after it.
5. **Any six-digit run**, last, once the text is known to be the dialog.

Passes 2 to 5 only run after the text is recognised as the pairing dialog, so a stray number
elsewhere in Settings still cannot start a pairing. And **the dialog is recognised without an
address in the same window** now: the old test wanted both the word "pair" and an `ip:port`, which
would return false about the very screen it exists to find if a reskin split them across views.

**An address is never read as a code.** Worth stating because the first version of the labelled pass
did exactly that: scanning forward from the label it walked into `192.168.1.10:37103` and read
`192168`, which then fails against the daemon for a reason nothing on the screen could explain. It
works line-wise now and skips any line carrying a colon or a dot.

**And the reader files what it saw.** #61 had to be typed by hand and arrived with no trace of the
text that was on screen — the one thing needed to fix a reader that cannot read. A dialog it
recognises but cannot read now raises the report chip with the flattened text in the issue verbatim,
once an hour at most.

Twelve tests hold the shapes, including the two that must never match: five digits and seven.
