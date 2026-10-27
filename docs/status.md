# Manette status (for picking up in a new chat)

Repo layout: `docs/` (protocol.md = source of truth, setup.md), `protocol/` (test-vectors.json + generator script),
`companion-windows/` (C# .NET 10), `android_src/` (our Kotlin sources; the user's Android Studio project lives in `android/`).

## Done and tested
- Protocol spec v1 + byte-exact vectors, both C# and Kotlin codecs pass them.
- C#: Core (codec, crypto, pairing, sessions, server), Network (UDP, mDNS, host), Backend.ViGEm (compiles against
  real ViGEm source, not run), Cli, FakeClient. 64 tests (incl. QR render + link install id).
- Kotlin: protocol, UdpTransport (state machine), layout model, Xbox definition, TouchMapper, connect-input parsing. 126 tests (plain JVM) incl. JSON, presets, layout editor, panel placement, discovery logic, host picker.
- Live cross-language runs against the C# companion passed (Kotlin phone <-> C# PC) on Linux with fake pads.

## Decisions
Wi-Fi/USB-tether UDP only for v1 (Bluetooth deferred behind the `Transport` seam); Windows only; ViGEm for virtual
pads; HMAC-authenticated, not encrypted; QR/typed code pairing; single-use 5 min pairing window; triggers digital;
transport uses threads+callbacks (no coroutines) and is wrapped in a StateFlow by the UI layer.

## Android app files (Compose/Android files NEVER COMPILED by me; the user's first build of connect/play worked)
`MainActivity.kt`, `session/ConnectionController.kt` (owns transport, prefs, Compose state), `ui/connect/ConnectScreen.kt`,
`ui/play/PlayScreen.kt` + `ControllerSurface.kt`, `platform/PlayEnvironment.kt` (WifiLock low-latency, keep-awake,
immersive, gesture exclusion). NEW, uncompiled: `ui/editor/LayoutEditorScreen.kt`, `session/PresetsState.kt`, layout chooser
in ConnectScreen, preset passed to PlayScreen, `ImmersiveMode()` (editor must be immersive so its surface equals the play surface).
Pure+tested: `json/Json.kt`, `layout/storage/` (PresetJson, FilePresetRepository), `layout/editor/LayoutEditor.kt`. No Gradle files: user creates the project with Android Studio (see docs/setup.md step 4).

## QR + discovery (added)
PC: `Manette.QrCode` (vendored MIT github.com/manuelbl/QrCodeGenerator, unmodified; console half-block renderer verified by
decoding with OpenCV), CLI prints the QR (`--no-qr` to skip), link has optional `i=<installId>`, ManetteHost.InstallId.
Phone: pure+tested `connect/HostPicker.kt`, `discovery/DiscoveryLogic.kt` (TXT parse, tracker, one-at-a-time queue,
AddressFollower). UNCOMPILED: `discovery/NsdDiscovery.kt` (NsdManager), `platform/QrScanner.kt` (Google code scanner,
dependency play-services-code-scanner), `session/DiscoveryState.kt`, ConnectScreen/Controller/MainActivity wiring.
Android multicast discovery against the PC's mDNS is UNTESTED on a real phone (PC needs UDP 5353 firewall rule).

## Known gaps / unverified
ViGEmBus is RETIRED (archived Nov 2023, no updates): still works on Win10/11, but kernel driver with no future fixes and an
updater-domain security caveat (see setup.md step 1). Backend is isolated behind IVirtualPad so it can be swapped.
ViGEm never run on real Windows. All Android/Compose code uncompiled. Device keys plain text (prefs and Windows json).
Package name `com.manette.app` is a placeholder. No tray app/installer yet.
Triggers are digital (0/255). Real-device latency/touch feel unmeasured. Layout sizes/positions are guesses.

## Field results (user ran it)
Windows + real Android phone work end to end; controller UI feels right. Connect screen needed padding (fixed with
safeDrawingPadding). controllertest.io numbers: sticks ~20 ms, face/bumpers/triggers ~80 ms, d-pad ~35 ms. That tool
measures time BETWEEN PACKETS, not touch-to-game latency, so see docs/latency.md. Changes made: unbuffered touch
dispatch and DSCP EF were tried then REMOVED (it feels fine; recipes kept in latency.md).

## Next
1. Layout editor/presets verified working on device. Editor panel is now draggable (header handle) and minimizable to a
   draggable "Layout" pill (PanelPlacement.kt tested; the Compose gestures are uncompiled, user to confirm).
2. Then: Windows tray app + installer (Inno Setup), key protection (Keystore/DPAPI), analog triggers,
   optional low-latency settings switch, NES/PS1 definitions.
