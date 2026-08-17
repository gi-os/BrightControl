## BrightControl v2.8 — The lock face in LightOS's own type

Same screen, correct typography. Everything on the lock face was hand-picked sp and dp; it is now
the real design system, ported from `lightphone/light-sdk`'s `sdk/ui` module.

**Akkurat, resolved by weight** off the system font list rather than by family name — asking for a
family that isn't there gets you a synthesised default that looks deliberate and isn't. The clock
takes Light, body takes Regular, the small tracked labels take Medium, matching what the app's
Compose screens already do.

**Named sizes, scaled by screen height.** The SDK measures type in design pixels against a 600px
reference and converts with `px * screenHeightDp / 600`, so a hardcoded size is right on exactly
one device. The face now uses:

| Element | SDK name | Design px |
| --- | --- | --- |
| Clock | `title` | 115 |
| Notification headline | `copy` | 30 |
| PRESS THE POWER BUTTON | `button`, 15% tracking | 30 |
| Notification body, sub-line | `detail` | 20 |
| Top bar, date | `fine` | 25 |
| App name above a notification | `superfine` | 16 |

**Spacing in grid units.** LightOS's grid is 27 wide by 31 tall and every inset is
`screenWidthDp / 27 * units` — one unit in from each side, three at the bottom, the same figures
every Light tool on the phone uses. No dp left on the screen.

**`contentSecondary` is `#BBBBBB`**, which is the SDK's actual value rather than the `#9A9A9A` this
was approximating.

Nothing about how it works changed: still a window at `TYPE_ACCESSIBILITY_OVERLAY`, still never
occluding the keyguard, still your thumb on the power button.
