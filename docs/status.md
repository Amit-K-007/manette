# Manette status (for picking up in a new chat)

Repo layout: `docs/` (protocol.md = source of truth, setup.md), `protocol/` (test-vectors.json + generator script),
`companion-windows/` (C# .NET 10), `android/` (Kotlin; see android/README.md).

## Done and tested
- Protocol spec v1 + byte-exact vectors, both C# and Kotlin codecs pass them.
- C#: Core (codec, crypto, pairing, sessions, server), Network (UDP, mDNS, host), Backend.ViGEm (compiles against
  real ViGEm source, not run), Cli, FakeClient. 61 tests.
- Kotlin: protocol, UdpTransport (state machine), layout model, Xbox definition, TouchMapper, connect-input parsing. 84 tests (plain JVM).
- Live cross-language runs against the C# companion passed (Kotlin phone <-> C# PC) on Linux with fake pads.

## Decisions
Wi-Fi/USB-tether UDP only for v1 (Bluetooth deferred behind the `Transport` seam); Windows only; ViGEm for virtual
pads; HMAC-authenticated, not encrypted; QR/typed code pairing; single-use 5 min pairing window; triggers digital;
transport uses threads+callbacks (no coroutines) and is wrapped in a StateFlow by the UI layer.

## Android app files (written, NEVER COMPILED; first build will need small fixes)
`MainActivity.kt`, `session/ConnectionController.kt` (owns transport, prefs, Compose state), `ui/connect/ConnectScreen.kt`,
`ui/play/PlayScreen.kt` + `ControllerSurface.kt`, `platform/PlayEnvironment.kt` (WifiLock low-latency, keep-awake,
immersive, gesture exclusion). No Gradle files: user creates the project with Android Studio (see docs/setup.md step 4).

## Known gaps / unverified
ViGEmBus is RETIRED (archived Nov 2023, no updates): still works on Win10/11, but kernel driver with no future fixes and an
updater-domain security caveat (see setup.md step 1). Backend is isolated behind IVirtualPad so it can be swapped.
ViGEm never run on real Windows. All Android/Compose code uncompiled. Device keys plain text (prefs and Windows json).
Package name `com.manette.app` is a placeholder. No QR scan, NSD discovery, layout editor, presets, or tray app yet.
Triggers are digital (0/255). Real-device latency/touch feel unmeasured. Layout sizes/positions are guesses.

## User is about to run (report back what happened)
1. Windows: CLI + FakeClient + gamepad tester (setup.md steps 1-2). 2. Phone: Android Studio project + first run (step 4).

## Next
1. Fix whatever the first Windows/phone runs reveal (that is the priority).
2. Then: layout editor + JSON preset storage, NSD discovery, QR scan, Windows tray app, installer (Inno Setup).
