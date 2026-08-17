## BrightControl v2.11 — BrightChat's background editor, and no more LightOS flash

### The background editor

The one from BrightChat's chat wallpapers, pipeline unchanged. **Settings → LOCK SCREEN →
Background.**

Pick a photo, choose how it meets the panel — **Fill** (drag the preview to frame the crop),
**Fit**, **Stretch** — then stack filters on it, in order, repeatable:

| Filter | What it does |
| --- | --- |
| **Dither** | Ordered Bayer dither to pure black and white, at a cell size you choose. 8× is chunky halftone that reads as deliberate; below 1× it dithers oversized and settles back down, which is softer and greyer than the panel's own grid could hold |
| **Black & white** | Plain luminance greyscale |
| **Opacity** | How much of the picture survives over the black behind it |
| **Corner blur** | Blur rising from a sharp centre out to the corners |
| **Corner fade** | The same reach, into black instead of blur — the edges dissolve into the background itself |

`−` and `+` nudge a filter, `↑` moves it earlier in the pass, `×` removes it. The preview is at the
panel's own aspect with a sample clock over it, because the only question a background has to
answer is whether the time survives on it. Nothing is written until Save.

The old arrangement — desaturate, 55% black — is gone. That is the one setting that works for every
picture and is right for none.

The photo is **copied** rather than held as a document permission. A persistable grant is one
revoked permission away from a lock screen that quietly goes black, and the copy means the
background still works after the photo leaves the camera roll.

### The flash of LightOS before your app

Gone, and the cause was the order. Taking the face down first uncovers whatever the *system* put in
front, which on this phone is LightOS — it holds the HOME role and comes forward the instant the
keyguard goes — and our launch then arrived over the top of it a beat later. Two screens for one
unlock.

The face is a window at layer 31, already above everything the handover involves, so it is now held
up *through* it: launch first, keep the face, drop it when the target reports itself in front. What
you see is the lock face, then the app. There is a two-second ceiling on the cover, because a
window held up waiting for something that never arrives is the same bug as a face that will not go.

The unlock poll also tightened from 300 ms to 120 ms, since that interval is now the delay between
your thumb landing and the app starting.

### Two smaller things

- **12-hour clock.**
- **The prompt is a toggle, and it is off.** "Press the power button" and "or tap for the keypad"
  were worth saying to someone who had just watched two versions fail to read their thumb. After
  that they are furniture on a screen whose whole argument is that there is nothing on it.
  Settings → LOCK SCREEN → Prompt.
