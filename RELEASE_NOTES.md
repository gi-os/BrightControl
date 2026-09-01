## BrightControl v4.9 — four fixes in the seams

No new features. Four bugs, all found by reading the code rather than reported from a phone, and
all in the seams between things that separately worked: banners, the service's own restart, the
lock face at night, and the last press before a pocket.

### A withdrawn banner stays withdrawn

Banners wait two seconds before drawing, so an alert already read at a desk can be cancelled by
its app before it lights the phone. That wait kept one record for everything: when a second app's
notification arrived inside the first one's two seconds, the record only remembered the second —
and when the first drew, its timer wiped the record entirely, orphaning the second. Still due to
draw, but invisible to every check: the app withdrawing it could no longer stop it, and neither
could switching banners off. Each waiting banner now has its own record. A withdrawal cancels
exactly the banner withdrawn, the off switch cancels everything queued, and two different apps
arriving close together still both show — that part was always deliberate. A single notification
behaves exactly as before, two seconds and all.

### A fast toggle no longer disconnects anything

Toggling the key filter off and on lands the old service's teardown *after* the new one's setup,
and the teardown cleared shared hooks the new instance had just claimed. Banners and colour
requests were already guarded against exactly this; now every process-wide hook is cleared only
by the instance that owns it. Before the fix, a fast toggle could silently disconnect the
edge-strip setting, the settings screen's recents noting and volume test button, the lock face's
shade updates, the keyboard button, and the app an unlock resumes to — each until the next full
restart, with nothing on the phone to say why.

### The lock face sleeps when you do

The face rebuilt its notification rows and re-rendered media, navigation, NEXT UP and the call
card on every minute tick, every battery report and every shade change, all night, against a
panel that was off — and worse on a charger, which reports battery constantly. It now follows the
rule its own navigation and calendar rows already had: an update against a dark panel is ignored,
and the face repaints once, in full, the moment the panel lights. Nothing visible changes; the
first glance after a wake is as current as it ever was.

### The torch stays off in your pocket

The wheel holds a tap back for a fifth of a second to see whether a double tap is coming. A tap
released just before the screen went off left that timer running, and it fired after — and the
wheel's default tap is Torch. Click the wheel, pocket the phone, flashlight on in the pocket.
Screen-off now sweeps every pending tap and hold timer, the same way a fault already did.

### Notes

The screen-off sweep sits inside the key filter itself. It is deliberately three lines, but the
filter is the one piece of this app every press passes through, so give the buttons a minute
after updating: a tap, a double tap and a hold should all land exactly as before.
