## BrightControl v2.14 — The camera button works from the lock face

Pressing the camera button while the phone was locked *did* start the camera. The problem was that
the lock face is a window at `TYPE_ACCESSIBILITY_OVERLAY`, layer 31 — above everything, including an
app that has just come to the front. So the camera was running underneath it: the shutter fired, the
photos were taken, and the viewfinder was never visible.

Any binding that brings something forward — the camera, a launched app, LightOS, Resume — now takes
the face down with it, and leaves it down until the next time the phone sleeps. Roll opens over the
keyguard the way it always did; there is simply nothing painted on top of it any more.

The torch and the volume keys deliberately do **not** do this. They change nothing about what is on
screen, and putting the lock face away for them would mean the lock screen vanishing every time you
reached for the flashlight in the dark.
