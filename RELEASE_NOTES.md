## BrightControl v2.12 — BrightChat's photo grid

v2.11 picked the background photo through the system document picker. That was wrong for this
phone, and wrong for a reason worth stating plainly: **the picker reads MediaStore, and nothing on
LightOS keeps MediaStore current.** There is no media provider doing the scanning a normal Android
build does, so a photo taken minutes ago is simply not offered. Using SAF instead of the photo
picker changed the door, not the room behind it.

The editor now uses the grid BrightChat wrote for the same problem: a walk of DCIM and Pictures,
newest first, three across, one tap to choose. A directory listing cannot go stale — a photo is
there the moment it is written, which on a phone whose camera app we also wrote is the difference
between choosing a background and waiting for one to appear.

- Thumbnails are decoded EXIF-upright at 256px and cached by **bytes** rather than by count. A
  count-based cache of quarter-megabyte bitmaps quietly retains tens of megabytes for the life of
  the process.
- Screenshots and HEIC are included; `.trashed-*` and `.pending-*`, which are MediaProvider's own
  bookkeeping and pass an extension filter otherwise, are not.
- One tap chooses, with no confirm step. There is nothing to protect — the tap only moves to the
  editor, and nothing is written until its Save.

It needs `READ_MEDIA_IMAGES`, which is an ordinary runtime prompt and is what makes reading another
app's image file by path legal. The grid asks for it itself; no adb line.
