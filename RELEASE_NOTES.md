## BrightControl v3.84 — an app can ask for colour instead of holding the grant

**Five apps were carrying a privileged permission to fight over two settings.** Showing a
photograph on this phone means moving the accessibility daltonizer, moving it needs
`WRITE_SECURE_SETTINGS`, and that permission is `signature|privileged` — no runtime prompt, no
LightOS screen, so `pm grant` from a computer and again after every reinstall. Roll, BrightChat,
BrightNotebook and BrightMusic all held it and all carried their own copy of the writer.

Which is five grants to lose, and worse, five writers. Two writers with different opinions about
one setting do not average out, they alternate: BrightMusic held colour per album cover and put
grey back between them, this app answered every restore by re-asserting colour, and the panel
flickered on every scroll. `ColorRule.Passthrough` exists because of that — this app standing down
so the other one could win an argument it should not have been in.

This is the other way round. The app says what it wants, this app writes it, and there is one grant
on the phone.

**Two ways for an app to say so.** A manifest tag, for an app with one opinion:

```xml
<meta-data android:name="com.gios.brightcontrol.color" android:value="color" />
```

`color` or `mono`, read off the package manager. That is what retires the hand-kept preset table in
`Prefs.kt`: a new app decides for itself, by whoever knows the answer, and it is true from the
moment the app is installed rather than from the next release of *this* app. Third-party developers
can use it, which nothing else here has ever allowed.

And a live request, for an app that changes its mind screen by screen — a camera, a chat thread, a
photo grid. New exported service, `color/ColorService.kt`, one method: `want(state, token)`.

**Why an exported service is safe here.** The same argument as `adb/GrantRequest.kt` one floor down.
That file holds a shell and refuses to run one character of what it is sent. This one holds the
grant and accepts two numbers, neither of which can name anything.

- **The caller is identified by the kernel.** `Binder.getCallingUid`, resolved to a package by the
  package manager. There is no field in which a request could claim to be another app. A uid with
  more than one package is a shared user id and is refused rather than guessed at.
- **A request is honoured only while its sender is in front.** Rules are read by
  `ColorMode.applyFor`, which is only ever called for the foreground package, so the worst a
  request can do is repaint a screen the caller was already drawing. That gate is the shape of the
  code rather than a check that could be forgotten: there is deliberately no `front` field in the
  registry to be kept in step with the service's own by hand.
- **The vocabulary is three states.** Colour, mono, nothing. Not a setting, not a value, not a
  package name. A state this build does not recognise is read as wanting nothing, because forcing a
  colour nobody asked for is the worse guess.

**The token is the release.** A request has to end when the process that made it stops existing, or
an app that is swiped away leaves the phone repainted with nothing to take it back and no LightOS
screen to undo it. AIDL gives a server no per-client identity, so the caller passes a plain binder
and this app links death to it. That also makes two processes of one app behave: each holds its own
request, and one dying does not drop the other's.

**A rule now comes from four places and the order is the whole behaviour**, so it is one pure
function with tests rather than four lookups inline. What the user set, then what the app is asking
for, then what its manifest declares, then the built-in table. The request sits above the table on
purpose: a migrated app is still carrying the `Passthrough` preset from when it wrote the settings
itself, and reading the table first would answer a polite request with the rule that means "ignore
this app" — a release where the screen goes grey and nothing in the log explains it. The other half
of the same property is that an app which has *not* migrated is still left alone, because it never
asks.

`Prefs.storedColorRule` is new and is why any of that works: "no choice made" and "chose AUTO"
stopped being the same answer, and while they were flattened together every app on the phone
resolved to an explicit AUTO and nothing below the first step was ever consulted.

**Color → Apps asking now** lists who is asking. An empty list on a phone with a migrated app is a
finding rather than an empty state — either the app never bound, or its request went when its
process did. Every colour bug so far was diagnosed off that screen and not off a hunch.

Nothing is a hard dependency in either direction. An app that never asks is unaffected. An app that
asks on a phone where this app is missing, ungranted, or has the colour switch off gets `INERT`
back and falls through to its own writer, and the request is kept so it takes over the moment that
changes.

### Under the hood

- New: `color/ColorService.kt`, `color/ColorRequests.kt`, `aidl/com/gios/lightcontrol/IColorProvider.aidl`.
  `buildFeatures { aidl = true }`, because AGP 8 leaves AIDL off.
- `Policy.resolveColorRule` and `Policy.declaredColorRule`, with `ColorResolveTest`.
- `ColorMode.ruleFor` caches the manifest read per package. A manifest cannot change without a
  reinstall, and a reinstall kills the process, so the cache cannot go stale while it is consulted.
- The re-apply callback is held in a field and compared on unbind, the same way the banner callback
  is: a fast toggle of the service lands the old instance's unbind after the new one's create, and
  clearing it unconditionally would leave every request recorded with nothing acting on any of them.

### Next

The apps migrate one at a time: delete the app's own `ColorMode.kt`, call `ColourEffect()` from
light-common 1.7.0, then move that app off `PASS` here. In that order — dropping an app's own writer
before this release is on the phone makes it grey for everybody who has not updated.

Roll is the one to read carefully. It still carries the **edge-based** `ColorMode` that BrightMusic
and BrightChat had fixed, so the stranded-holder bug is latent in it today. The migration deletes
the file rather than repairing it.
