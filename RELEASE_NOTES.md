## BrightControl v3.88 — the volume strip steps aside where LightOS draws its own

**A volume readout was appearing on top of LightOS's own volume readout, on SDK apps.**
LightOS v572 added its volume overlay to the apps built on the light-sdk — the `com.thelightphone.`
namespace — while still leaving it off for the plain sideloaded APKs this HUD exists for. BrightControl
only knew to stand down over LightOS's *own* screens (`com.lightos`), so on an SDK app both drew:
LightOS's overlay and this strip, one on top of the other, saying the same number twice.

The HUD's front-app gate now treats the light-sdk namespace the same way it treats LightOS itself:
when the app in front is one the platform already draws a volume overlay for, the strip does not
show. Nothing else changes — the ringer and alarm levels this strip is the only way to reach are
still unreachable through LightOS's overlay, and the strip still appears everywhere LightOS's does
not, which is every plain sideloaded app.

Fixes [light-reports#132] — the volume strip doubled up on LightOS's own overlay in SDK apps.
