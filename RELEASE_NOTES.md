## BrightControl v3.40 — the card asks telephony, and goes and fetches the call screen

**The card was reading the wrong window.** v3.38 taught it to find the caller in a `CallStyle`
notification's `Person` rather than the empty title, which was a real bug and not this one. The
actual answer is worse: on this phone **there is no call notification at all**. LightOS's dialer is
a system app that shows its own incoming-call activity, and a system app does not have to go
through the shade to take over a screen. A notification listener was never going to see a name that
was never posted.

So the card asks telephony now. `ACTION_PHONE_STATE_CHANGED` carries the number on the ringing
broadcast, `PhoneLookup` turns it into the name on the contact, and neither route needs this app to
be the dialer. The notification stays as the first source where it exists — a dialer that wrote a
name has often done something this app cannot, a business lookup or a spam label — with the contact
name behind it, the number behind that, and the old wording only when all three are empty. The
number goes on the second line under the name, grouped for the region, because a lock screen is
read at arm's length.

**Two new grants, and the ADB screen has them.** `READ_CALL_LOG` is what makes the number on that
broadcast non-empty — Android P moved it behind that permission — and `READ_CONTACTS` is the name.
Both are in the one-tap run with the rest. Without them the card falls back to exactly what it says
today, so an ungranted phone is no worse off than it was; the diagnostics log says `tel=NO GRANT`
so the reason is on the screen rather than a guess.

**And the in-call screen: `showInCallScreen` was being believed.** With no notification there was no
full-screen intent and no content intent to send, so the whole hand-off rested on
`TelecomManager.showInCallScreen`, which returns nothing, reports nothing, and is only a request
passed to the dialer's in-call service. On a phone where that request goes unanswered, a call was
answered into a stock lock screen: the face had stood down, and nothing had come up behind it.

It is still asked, and it is no longer believed. When nothing verifiable fired, the face goes and
fetches the dialer itself — resuming its task, which during a call is the call screen, and on this
phone is the one LightOS activity that draws the ring, the call and the lock screen in turn. That
route is only ever reached on a phone that posted no call notification, so a dialer that would have
landed on a keypad instead of a call never gets there.

**A line in the log saying what the phone actually said.** `note=none who=- fsi=- tel=ok name=yes
dialer=lightos`, once per ring and again on a failed hand-off. Both previous attempts at this card
were aimed at the wrong half of the problem, and the reason is that this line did not exist. Shake
to report carries it now.
