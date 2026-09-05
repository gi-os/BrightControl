## BrightControl v4.19 — BrightHermes gets the whole wheel

**BrightHermes's two controls did nothing, for the reason Roll's dial lock once did nothing.** In BrightHermes, holding the wheel in is push-to-talk and a click walks the deck. Under the `com.gios.` ScrollThrough rule the wheel's *turns* reached the app and its *press* did not: this service saw the press first and spent it on the torch, and the camera key on opening the camera. From the phone that reads as an app ignoring its buttons.

### What changed

`com.gios.brighthermes` joins `ownsWheelPrefixes`, beside Roll and BrightRecorder, so it resolves to `AppRule.Off`: every key goes to the app untouched while it is in front. The torch and the camera key do nothing there, which is the trade the app asked for — it has no flash to lose and handles the camera button itself. As with the other two, this is consulted from the built-in table alone, so a per-app rule stored earlier cannot keep eating the press.

`PolicyTest` pins both the `ownsWheelClick` answer and the resolved rule.
