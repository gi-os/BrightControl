## BrightControl v4.23 — one-tap pairing walks the whole way, and stops switching the phone off

**The button that pairs the phone to itself was failing two ways at once, and both were the walk
through Settings rather than the pairing.** PAIR AUTOMATICALLY opens Developer options and arms a
reader that watches for the pairing dialog, pressing the rows that lead to it. Between them, the
three faults below sit under twenty-two of the reports filed against pairing this summer.

**It never scrolled.** Developer options is about four screens long on this phone and the Wireless
debugging row sits well below the fold. A row that has not been laid out is not in the text the
reader flattens and has no node to press, so the walk had nothing to match, did nothing, and the
ninety seconds ran out with the row one swipe away. Seven reports whose entire diagnostic reads
`windows seen while waiting: Developer options` are that, exactly. Looking further is now a step of
its own: the walk scrolls the list a page and reads again, capped at twelve scrolls per attempt and
stopping as soon as the list says it is at the bottom.

**And when it did reach the right screen, it pressed the wrong thing.** The Wireless debugging
screen can also open above its pairing rows. In that state the walk fell through to its second
choice — press the row labeled "Wireless debugging" — on the one screen where that phrase is the
title over an on/off switch. Node text is matched as a substring, so it matched **Use wireless
debugging**, which is clickable, and clicked it. That is the report reading *"the pairing was
accepted and mDNS then found nothing to connect to — wireless debugging may have been switched off
by the trip through Settings"*: it was, and the app was one of the things that could switch it off.
Thirteen reports read that way. How many were the app's own doing cannot be told apart from outside
the phone, but this was a route to it and the route is closed — the Wireless debugging screen is now
recognized by that switch's own label and is only ever scrolled, never tapped.

**A third fault sat under the first two.** Reaching the row and matching it still did not press it.
A Settings row is a list item holding a frame holding a column holding the label, so the clickable
node is three levels above the text — and the walk only ever looked at the label's immediate parent,
found nothing clickable, dispatched no click, and reported that it had acted. Two reports reached
the right screen and stopped there. The climb now goes up to three ancestors and takes the first one
that accepts a click, stopping short of the root, because pressing a whole screen presses whatever
the framework decides is under the middle of it.

### How

The decision — press this row, scroll, or leave this screen alone — moved out of the reader into
`AdbPairWalk`, which has no Android import in it and is pinned by unit tests screen by screen. That
is the same split `AdbPairCode` already uses, and for a stronger reason here: this is the code that
decides to touch somebody's Settings, and a wrong decision does not fail quietly, it turns wireless
debugging off. The reader now only carries the step out.

Fixes [light-reports#302], [#296], [#290], [#266], [#255], [#254] and [#247] — the walk stalling on
Developer options. Fixes [light-reports#311] and [#235] — the right screen reached and the row never
pressed. Addresses [light-reports#318], [#314], [#312], [#310], [#308], [#303], [#288], [#270],
[#265], [#260], [#258], [#234] and [#229] — wireless debugging off between a pairing the daemon
accepted and a connection that found nothing.

Still open: the labels are English, so a phone set to another language gets no walk at all
([light-reports#259], filed in French). Which languages to carry is not a call to make from here.
