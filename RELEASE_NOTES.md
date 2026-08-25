## BrightControl v3.28 — the camera key only leaves LightOS when it has somewhere to go

**Pressing the camera button on LightOS's home screen no longer raises Android's "which app?"
dialog.** v3.25 let the camera button's binding fire on Light's own screens, which was right for
the case it was built for — a rebound button that could only ever fire where nobody presses it.
It was wrong for the default binding. `CAMERA` fires `INTENT_ACTION_STILL_IMAGE_CAMERA`, and on a
phone with two camera apps installed and no default chosen, Android answers that with a chooser.
So a key that used to open a camera started opening a dialog.

**So the key is claimed there only when a gesture names an app.** Point the tap or the hold at
Roll, or anything else, and it fires on the dashboard and the lock screen as it should. Leave it on
CAMERA and LightOS keeps the key and opens its own camera, which it does perfectly well — the only
thing LightOS cannot do is open *your* camera, and that is the whole of what this switch is for.

Reported alongside two things that are working as intended and are worth writing down:

**AUTO is missing from the stock camera's cycle because AUTO and COLOR are the same answer for it.**
`com.android.camera2` ships with a Color preset — a viewfinder in grey misrepresents a photograph
that comes out colour regardless — so a stored nothing already resolves to COLOR. The cycle skips
any step that would redraw the row unchanged, which is what stops presets from looking frozen.

**Setting the Light layer to COLOUR colours all of it, and cannot do otherwise.** The dashboard,
the lock screen, the camera, the album and directions are one package. Nothing outside it can tell
its screens apart, so one row is one decision for all of them. In practice the layer is drawn in
greys, so what changes is the content that was colour to begin with: a viewfinder, photographs, a
map.

## BrightControl v3.27 — LightOS's own layer gets a colour row

**The per-app colour list now offers LightOS.** It could not before, and the reason is a detail of
how launchers declare themselves: the list was built from `CATEGORY_LAUNCHER`, and a home app
publishes `CATEGORY_HOME` and need not publish `CATEGORY_LAUNCHER` at all. LightOS does not. So the
one package that draws the dashboard, the lock screen, the camera and the album was the one package
with no row — reported as "album has no colour filter option to toggle", which was exactly true.
Home apps are queried too now, which also picks up any other launcher installed.

**Its row says what it covers.** Every Light tool lives in that single package, so the row is one
decision for the dashboard, the lock screen, the camera and the album together. Set it to COLOR and
all of them keep colour; there is no way to separate the album from the dashboard, because as far
as the system is concerned they are not separate things. Nothing about that is obvious from
outside, so the row explains itself rather than leaving somebody hunting for a row named Album.

Worth knowing what this means for the mono look: LightOS's own interface is drawn in greys, so
turning the layer to colour changes nothing you can see except the things that were colour to begin
with — a viewfinder, and the photographs in the album.

## BrightControl v3.26 — the next overlay app cannot drop your colour either

**v3.24 named Edge Gestures; this stops the class of bug.** `Default` means *restore the
baseline*, and on this phone the baseline is monochrome — so any app with no colour rule that
raises a window-state event over what you are reading repaints it mono. Ryan's log showed exactly
that, alternating: `com.gios.lightchat COLOR want 0/-1 got 1/0 superseded`, and one line later
`com.ss.edgegestures DEFAULT want 1/0 got 1/0 ok`. Whether a colour app opened in colour came down
to whether the overlay happened to fire after it, which is why going back through the same apps
flipped which half looked broken.

**So a floating window with no opinion is no longer allowed to write.** The rule: a package with a
real rule is applied whatever kind of window it raised, and a genuine app switch arrives as an
activity — but the *baseline* write, the one with no opinion behind it, is refused when the window
is not an app screen. Named packages are still filtered upstream; this catches the next one, which
will not be on any list.

The delayed re-asserts inherit the same answer. Refusing the write on the event and then allowing
the identical write 250 ms later is a bug that looks like a race and is not.

## BrightControl v3.25 — hold a row to stop the app, and the camera button works where you press it

**Hold an app in the switcher to force stop it.** A long press on a row, or holding the wheel click
on the selection, stops that app and takes its row out of the list — going back to an app you just
stopped would start it again, which is the opposite of what the hold asked for.

**It says which of two things it did, because they are not the same promise.** With this app's adb
shell paired it runs `am force-stop`, the same command Settings' own Force stop button runs: every
process killed, alarms and jobs cancelled. Without it, the fallback is `killBackgroundProcesses`,
whose permission is *normal* and needs nothing granted — but which leaves a foreground app and its
scheduled work alone. So the line under the list reads STOPPED or BACKGROUNDED, never whichever
sounds better. Somebody stopping an app because it is misbehaving needs to know which happened.

The stop runs on its own thread. The adb path opens a socket and waits for a command to exit, and
this service's main thread is the one key events are dispatched on — a filter that blocks is a
phone whose buttons have stopped answering.

**The camera button now fires on LightOS's own screens, and has its own switch for it.** Reported
from outside: "I rebound the camera button to Roll and it refuses to acknowledge my settings
change." The setting saved. It just never applied where the thumb was — button bindings are gated
on Light's home and lock screens behind **Buttons on LightOS screens**, which ships off, and the
camera button is pressed *from the home screen* almost by definition. So the only places a rebound
camera button could fire were the places nobody presses it.

That gate stays correct for the wheel and its click: claiming those on LightOS's screens is what
once made LightOS unstable. The camera button gets its own answer instead — **Buttons → Camera
button → On LightOS screens**, on by default. With the default binding nothing visible changes,
since the tap already resolves to the same camera LightOS would have opened. With a binding, it is
the whole point of having set one.
## BrightControl v3.24 — an edge-gesture overlay no longer drops the color

**Edge Gestures was quietly turning the panel monochrome.** The report counted five writes
`superseded`, and every one of them had the same shape: an app with a Color rule — Waze,
Spotify, BrightChat — asked for color, got it, and read it back gone a second later. The culprit
sat one line above each `superseded`: `com.ss.edgegestures`, an overlay that floats invisible
swipe zones over every app. It raises a window-state event, the service took that for "a new app
is in front", it has no color rule, and no rule means Default — put the panel back to the
monochrome baseline. The color dropped out from under the app you were actually looking at.

**An overlay is not the front app.** The service already refuses to let the notification shade,
the keyboard, and its own readout overlay stand in for the app in front, because any of them
would swap the color — and the key mapping — mid-turn. Edge Gestures is the same shape of thing
and was not on that list. It is now, so its window-state events are ignored and the Color rule of
whatever sits underneath holds.

Fixes [light-reports#43](https://github.com/gi-os/light-reports/issues/43) — per-app color: 7 held, 0 overwritten, 5 superseded.

## BrightControl v3.23 — touch the screen and the wheel gives the page back

**A finger on the glass drops the highlight.** A highlight is a claim about where your attention
is, and the moment a thumb lands the claim is wrong: the row under the highlight is not the row
under the thumb, and a click aimed at one would have opened the other. So a touch clears the
selection and hands the wheel back to scrolling the page, which is what a phone with a working
touchscreen should be doing anyway. Everything then behaves exactly as it did before v3.20 —
scroll with the wheel, tap what you want.

**A wheel click is the way back in.** With nothing selected the click highlights the first row on
screen instead of opening anything, so the click means one consistent thing: take the wheel
seriously, first by selecting, then by opening. Turn to move, click again to open.

**Which input you are using survives a screen change.** Somebody navigating by thumb does not want
the wheel to start selecting again every time a new screen opens, so the mode is a fact about you
rather than about the screen. The selection itself is still dropped at every screen change.

The touch is read in `dispatchTouchEvent`, before anything else can consume it — including the row
that is about to be clicked, which is the one touch that most needs the highlight gone first.

## BrightControl v3.22 — the switcher just appears

**No animation on the app switcher.** It was fading and rising into place over about a fifth of a
second, which is a fifth of a second in which the row you are reaching for is not yet where it is
going to end up. This window opens on a double press of the home button — a gesture people make
while they are already moving — and a switcher is somewhere you pass through, not somewhere you
look at. It is now at rest on the first frame it exists.

That is the last of the entrance effects: the dithered background went in v3.18 and the list's own
fade goes here.

**And the page no longer scrolls under your finger.** It could be dragged a little, and on a screen
whose only job is "the selection is here" a draggable page means the selection can be pulled out
from under itself — the row you were about to click is somewhere else by the time you click it.
Touch scrolling is refused outright rather than damped; the wheel still brings the selection into
view, because that path is programmatic and never went through touch.

## BrightControl v3.21 — three things about the new controls that did not work

**The wheel click now opens the highlighted row.** It was doing nothing, and the reason was two
features meeting: this app resolves to the scroll-aware rule, which passes turns through to the
app and keeps the *press* for the service — whose default binding is the torch. So the wheel moved
the highlight and the click turned on the flashlight, and the key never reached the activity at
all. While our own settings are in front and the wheel is driving them, the service now declines
that key.

**The highlight can no longer scroll off the screen.** Two bugs, both about measurement. The
viewport came from `screenHeightDp`, which excludes the system bars while the coordinates rows
report are measured from the top of the window — that gap is exactly how far the last row could
hide below the fold. And rows reported `boundsInWindow()`, which is *clipped to the parent*: a row
scrolled under the top bar reported a sliver at the clip edge rather than where it actually was.
Position and size, unclipped, against the window's real height. The check also runs on every
layout pass now instead of only at the notch, so an error left over from a scroll that was still
animating when the next notch arrived is corrected on the next frame rather than carried.

**The double press shows the switcher even with nothing in it.** The list was held in memory, and
every release of this app rebinds the service that holds it — so for the first minutes after every
update the order was empty, and an empty list refused to appear. Which is to say the gesture
looked broken in exactly the state everybody who has just updated is in. The order is now stored,
so it survives the update, and an empty list still shows and says so. The key log also records the
gap between the two presses, because a double press that does not fire has three possible causes
and from the phone they look identical.

**And per-app colour survives an update.** Reported from outside: "each update my apps go back and
forth as to whether the colours work — force-closing brings them back." Not a colour bug. A fresh
service has seen no window-state event, so it does not know which app is in front, and the rule is
driven from that package name; the app on screen kept whatever mode the phone was in until it was
force-closed and reopened, which is what produced an event. The service now writes down the front
app and, on starting, acts on that name while it is fresh and the phone is awake. Being wrong costs
one rule until the next window change corrects it — `ColorMode` states the desired state rather
than toggling, so nothing here can be stranded.

## BrightControl v3.20 — the wheel drives the settings

**The app switcher's controls, applied to this app's own screens.** Turn the wheel and a
highlight moves from row to row; press the wheel in and the highlighted row opens. The list
scrolls to follow, keeping a row's worth of air past the selection so you can always see where
the next press is going. Touch still works exactly as it did.

**Nothing is highlighted until you turn the wheel**, and the highlight is dropped on every screen
change. A screen that opens with a selection is a screen where a click does something you did not
ask for, and a selection carried across a screen change is a press aimed at whatever now happens
to be in that position. With nothing highlighted, the click goes back to being the flashlight.

**Rows register themselves.** Nothing walks the layout: a row with something to do reports where
it landed, and the cursor sorts what it holds by vertical position — the reading order of every
screen in this app. So rows that come and go, like the ones that only appear once a binding is
set to Resume, join and leave the order by existing. A screen with nothing selectable, like the
ADB log, falls through to plain scrolling, so the wheel is never dead.

**The highlight is a bar and a shade, not a border.** Anything that changes a row's size makes
the whole list twitch as the selection passes down it, which is the one thing a cursor must not
do.

**Turning towards the top of the phone moves up the list** — the switcher's convention, and the
opposite of what a turn does when it is scrolling. Dragging a page down with a thumb and moving a
cursor up are the same motion with opposite results, and selection wins: a highlight that goes
down when the wheel goes up is the version people get wrong every time.

Switch it off under **The wheel → Wheel selects rows**.

**Why only this app.** The version worth having is system-wide — the service walking the
accessibility node tree of whatever app is in front and clicking what is focused. That needs
`canRetrieveWindowContent="true"` on `ControlService`, which is false today and is the reason
this app can say it never reads what is on your screen. It is worth proving the interaction on
our own rows before spending that.

## BrightControl v3.19 — the lock face reads the Wi-Fi, not the tower

**On Wi-Fi, the bars are the Wi-Fi's.** They were meant to be already, and in the common case they
were — but the question was asked of `activeNetwork`, which is the *default route* rather than
"what is this phone on". Those are the same thing right up until they are not: a captive portal
nobody has signed into yet, a router with no upstream, the seconds inside a handover. In every one
of those the phone is on Wi-Fi, plainly, and the default route is cellular — so the glyph switched
to reporting the tower.

**Which usually meant no bars at all.** Cellular strength needs `READ_PHONE_STATE`, a runtime
permission LightOS has no screen to grant, and the face deliberately draws "not known" as four
empty outlines rather than as zero. So the symptom was an empty signal glyph on a phone with full
Wi-Fi, and nothing on screen to suggest which of the two things had gone wrong.

**Wi-Fi is now asked of Wi-Fi**, across every network the phone holds rather than only the default
one, with three sources in falling order of trust: the network's own `signalStrength`, then
`WifiManager`'s RSSI for builds that leave that unspecified, then full bars when the phone is
associated but nothing will say how strongly — a working Wi-Fi connection has, for every purpose
this glyph serves, signal. Cellular is unchanged and still what you get with no Wi-Fi.

Nothing new is granted for this. `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are both normal
permissions the app already held.

## BrightControl v3.18 — the dither is gone, the list is bigger

**The switcher's animated dither is removed.** v3.16 filled its background with an 8x8 Bayer
field of grey on black that swept down the screen while the grain grew. The reasoning held up and
the result did not: on the device it read as noise behind the text rather than as texture under
it. The background is plain black again, which is what every other full-screen surface this app
draws already is.

**Every size on the switcher is 15% larger.** It is read at arm's length in the second between
deciding to leave an app and leaving it, which is not the distance the SDK's body scale is set
for, and eight rows have the room to spend. The one deliberate departure from `LightType` in this
app.

The list still rises in as it opens. Nothing about the gesture changed.

## BrightControl v3.17 — what is playing, on the lock face

**The lock face carries a now-playing row.** Cover, track, artist, and previous / play-pause /
next, sitting under the notifications. It appears when something is playing and is not there when
nothing is. Tapping the track opens the player, once the phone is unlocked.

**This had to be built here, and that is the whole point of the release.** BrightMusic already
draws its own controls over LightOS's lock screen, and that row is a `TYPE_APPLICATION_OVERLAY`
— layer 11 in AOSP's `getWindowLayerFromTypeLw`. The Light face is a `TYPE_ACCESSIBILITY_OVERLAY`
at layer 31. So with the face on, the player's controls were painted underneath it: present,
correct, invisible and untouchable. No flag, permission or window trick lifts an ordinary app
above 31. Whatever draws over the face has to be the thing that owns the face.

**It reads the platform media session, not BrightMusic.** `MediaSessionManager` already carries
the title, the artist, the artwork and the transport controls of whatever is playing, for every
app on the phone at once, and BrightMusic's session has always been registered and correct — it
is only LightOS that ignores it. So there is no private contract between two apps to keep in step
across two releases, and the row works the same for a podcast, for the radio, and for any player
installed later.

**No new grant.** `getActiveSessions` is refused unless the component asking is an enabled
notification listener, and that is `LockNotifications`, which the face already needs for the
shade. Without that grant the row is absent rather than broken, and the settings row says so.

**Details that are deliberate.** A paused player still gets the row, because a lock screen you can
press play on is the point; a stopped one does not, because dead buttons under the last song you
played is worse than nothing. Buffering draws as playing, so two seconds of network does not flip
the glyph and read as a failure. The cover is desaturated to match a face that is otherwise white
and grey, and a missing cover is drawn as a square rather than as a gap, so the title does not
move when the radio changes show. The row is built once and hidden, never inserted, because a row
that arrives by insertion shifts everything above it the moment a track starts.

**The buttons are the only touchable things on the face, and that is why the gestures still
work.** A child with a click listener consumes the press before the frame's listener sees it, so
pressing skip cannot half-start the hold-to-enter, and a drag from anywhere else still reaches the
keypad. Opening the player is gated on the same arming as the hold: while the keyguard is up, the
tap does nothing at all.

On by default. Switch it off under Lock screen → Now playing.

## BrightControl v3.16 — the switcher fills in

**The app switcher's background is a dither now, and it arrives.** Press home twice and the
screen fills with an 8x8 Bayer pattern of grey on black, sweeping top to bottom, while the grain
itself grows from an eighth of a cell to twice one. So the texture resolves towards you instead
of appearing at rest, and the list rises into a ground that is already there rather than landing
with it.

**A dither rather than a fade, because of the panel.** The LPIII is black and white and LightOS
pins the whole phone to monochrome through the accessibility daltonizer, so the greys in a
cross-fade get quantised on the way to the glass — a smooth ramp arrives as two or three visible
steps in a direction nobody chose. A dither is the same idea in the panel's own language: the
grey is made of black and grey cells, every frame is already in tones the screen can hold, and
what animates is how many cells are lit.

**It is drawn as a shader, not per pixel.** The obvious version — one `IntArray` the size of the
screen, thresholded every frame — is a few million writes a frame at the fine end of that zoom,
which is a stutter on the one animation whose whole job is to feel immediate. Each coverage level
is baked once into an 8x8 tile and painted with a `REPEAT` shader under a scale matrix, so
growing the grain is a number in a `Matrix` and a frame is a couple of dozen `drawRect` calls.
The sweep is horizontal bands, one per grain row, with the tiles cached — there are only 65 of
them possible.

Nothing about the gesture changed. Home still fires the instant you let go, the second press
still opens the list, and the wheel still moves the selection.

## BrightControl v3.15 — press home twice for the apps you were just in

**A double press of the home button opens an app switcher.** LightOS has no recents screen and
no way back to the thing you were reading two minutes ago except finding it again, and this is
that way back: press home, press it again quickly, and the apps you have been in are listed
newest first. The wheel moves the selection, a click opens it, home closes it, and it closes
itself after six idle seconds. On by default, and switchable off under Buttons → Home button →
Double press.

**The first press is never held back.** The usual way to read a double press is to sit on the
first one until its partner could have arrived, which would put a third of a second on every
press of the key this phone is used with most. So home fires the instant you let go, exactly as
it always has, and the second press draws the list over whatever the first one landed on. The
price is a glimpse of home on the way to the switcher, which is the right thing to spend.

**The list is a window, not an activity — and it had to be.** An activity would push itself onto
the recents order it exists to display, so the switcher would always be the most recent thing you
used. It is drawn at `TYPE_ACCESSIBILITY_OVERLAY`, layer 31, the same layer as the lock face:
nothing is started, no background-activity-start appop is involved, and there is no task to get
stuck in.

**`GLOBAL_ACTION_RECENTS` is deliberately not what this does.** That asks SystemUI for a recents
screen and this phone ships none — the call returns true for "injected" and nothing appears,
which is the worst answer available: a gesture that reports success and does nothing.

**Where the recents order comes from.** `getRecentTasks` has been privileged since Lollipop and
`UsageStatsManager` needs a special-access grant with a Settings screen LightOS does not ship, so
the honest source is the one this service already receives for free — the window-state event
naming each app as it comes to the front. The list therefore starts empty at boot and is as long
as the phone has been awake, which is the right length for a switcher. Nothing is stored and
nothing leaves the process. LightOS's own shell is left out, because one press of home already
goes there.

**Safety, since this consumes the home key while it is up.** The window covers the screen, so a
key falling through it would act on an app nobody can see; every key it takes it takes only while
it is showing. It closes on idle, on the screen going off, on a service fault, and on unbind. If
the window fails to appear, the press is treated as an ordinary tap — a gesture that swallows the
key and then produces nothing is the one failure this file is written to avoid.

## BrightControl v3.14 — two features that were pretending to be finished

**Wi-Fi login and Hotspot now say they are unfinished, on the screen itself and in the README.**
Both of them sat in the SYSTEM section looking exactly like Colour and Lock screen look — a row,
a subtitle, a guide paragraph written in the same confident voice as everything else. Neither is
in that state, and the app was the last place you would have found that out.

The honest description of each is short.

**Wi-Fi login needs a system WebView, and whether this phone has one is unconfirmed.** The whole
feature is a WebView pinned to the captive network; on a build without one there is no page, no
error worth reading, and nothing the screen can do from its side. It was written against the
assumption that LightOS carries the standard component, which has never been checked on a device.

**The hotspot has three separate ways to quietly do nothing.** The Bluetooth pairing has to have
exchanged an identity key, or the iPad's rotating address never resolves and presence triggering
cannot work at all. The adb shell has to have survived the reboot, or `SoftAp` has nothing
privileged to call. And the iPad has to choose to join, which it only does for a network it
already knows — so the documented step 2, joining by hand once, is load-bearing and is the step
everybody skips. Any one of those leaves a feature that looks like it is running and is not.

Neither is being removed. They work when their preconditions hold, and the preconditions are
listed now rather than discovered. The rows read `IN DEVELOPMENT ·` before their subtitles, and
each screen opens with a paragraph naming what specifically can leave it dead.

**And the README was rewritten around what this app has become.** It still opened with "the
brightness wheel, camera button, and home button" and a Quick start whose first instruction was to
find a computer and whose sample command named `LightControl-v1.0.11.apk`. Since then the app has
grown per-app colour, a lock face, a volume HUD, a captive-portal browser, a hotspot, and the ADB
self-grant that means no computer is needed for any of it — and the `Layout` block still listed
`ui/SettingsScreen.kt`, which was split into a hub and eight section screens two minor versions
ago, and named none of `lock/`, `hotspot/`, `portal/`, `adb/` or `report/`.

It is now organised the way the app is: Controls, The screen, System, then the safety rules. Quick
start leads with the phone granting itself everything, because that is what actually happens, and
keeps the adb commands for the one grant that cannot be self-applied and for people who prefer a
cable.

No behaviour changed in this release outside those two guide strings and two subtitles.
