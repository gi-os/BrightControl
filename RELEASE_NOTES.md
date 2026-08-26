## BrightControl v3.42 — hold a row for App info, and the force-stop gesture goes

**The hold on a row opens Settings' App info page for that app.** By thumb, or by holding the wheel
click on the selection — the same pair as before, pointed somewhere better.

What it replaced: a hold that ran `am force-stop` over this app's own adb shell, and fell back to
`killBackgroundProcesses` when there was no shell to run it. Two different outcomes behind one
gesture, and the weaker one had to announce itself as **BACKGROUNDED · no adb for a full stop**,
because backgrounding an app is not what somebody holding a row on a misbehaving app asked for. The
honest message was the tell: a gesture whose meaning depends on whether a pairing has been done is
a gesture nobody can rely on.

App info has AOSP's own Force stop button on it. It needs no shell, no pairing and no runtime
permission, it is the real force stop every time, and Uninstall, storage and permissions are on the
same page. One hold, one meaning, and the thing it leads to is stronger than what the hold used to
manage on a good day.

So `switcher/ForceStop.kt` is gone, and with it the `KILL_BACKGROUND_PROCESSES` permission — this
app asks for one fewer thing than it did yesterday, which is the right direction for a permission
list this long.

**And SYSTEM SWITCHER is tap-only again.** v3.41 put App info on a hold of that button, which was
the wrong place for it: the app a gesture is about is the row, and the button at the bottom is the
control furthest from it. Tapping it still asks the platform for its own recents and still says
**NOTHING CAME UP** when nothing does.
