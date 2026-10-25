# Manette: Android app

Status: the **protocol layer**, the **UDP transport** and the **layout + touch-mapping core** are done and tested.
The connect screen, play screen, controller surface and Android glue (WifiLock, immersive, gesture exclusion) are
written but NOT compiled. Setup steps are in `docs/setup.md` (step 4). Not started: layout editor, preset storage,
NSD discovery, QR scanning.

```
app/src/main/java/com/manette/app/
  core/GamepadState.kt          canonical controller state (+ Buttons, screenYToWire)
  protocol/Protocol.kt          constants (mirrors ProtocolConstants.cs)
  protocol/PacketCodec.kt       byte-exact encoder/decoder (mirrors PacketCodec.cs)
  protocol/ManetteCrypto.kt     HKDF, HMAC tags, key derivation (JDK crypto only)
  protocol/PairingCode.kt       Base32 / typed code / manette:// link parsing
  protocol/DeviceId.kt, Hex.kt, SequenceNumber.kt
  transport/Transport.kt        the seam: what the UI sees (state, send(), listener). Bluetooth would be a 2nd implementation
  transport/ConnectionProvider.kt   how PCs are found (manual IP now; NSD/QR/Bluetooth later)
  transport/Scheduler.kt        one-thread scheduler + monotonic clock (both replaceable in tests)
  transport/udp/UdpTransport.kt handshake, 20 Hz keepalive, release repeat, ping/RTT, reconnect watchdog, rumble
  transport/udp/PacketSocket.kt the tiny socket interface (real UDP and a fake for tests)
  transport/udp/ManualIpProvider.kt  "connect by IP" parsing (also covers USB tethering)
  layout/model/                 Control, Placement, Preset, ControllerDefinition (what a controller IS)
  layout/definitions/XboxDefinition.kt   default Xbox arrangement (NES/PS1 later = more definitions)
  layout/ResolvedLayout.kt      normalized preset -> pixels for a real surface
  layout/editor/LayoutEditor.kt  editing rules (drag, clamp on screen, scale/opacity, save only diffs), pure Kotlin
  layout/storage/                PresetJson (versioned, strict) + FilePresetRepository (atomic files, safe ids)
  json/Json.kt                  dependency-free JSON reader/writer
  layout/mapping/TouchMapper.kt multi-touch -> GamepadState (sticks, d-pad, sliding buttons), pure Kotlin
  ui/editor/LayoutEditorScreen.kt  edit screen (thin; rules are in LayoutEditor). NOT COMPILED
  session/PresetsState.kt       Compose state over the preset repository. NOT COMPILED
  ui/play/ControllerSurface.kt  Compose Canvas: pointer events -> TouchMapper, draws the pad. NOT COMPILED YET
app/src/test/java/com/manette/app/
  protocol/ProtocolVectorTest.kt   runs against ../protocol/test-vectors.json (shared with the C# tests)
  protocol/MiniJson.kt             tiny JSON reader so tests need no extra dependency
  core/GamepadStateTest.kt
  transport/UdpTransportTest.kt    the whole connection state machine against a fake PC, with manual time
  transport/TransportTestSupport.kt  fake PC (an independent implementation of the PC side), manual clock/scheduler
  transport/ManualIpProviderTest.kt
  layout/TouchMapperTest.kt        multi-touch behaviour, stick/d-pad math, random-input range fuzz
  layout/LayoutEditorTest.kt, layout/PresetStorageTest.kt, json/JsonTest.kt   editor rules, storage, JSON
  layout/LayoutTest.kt             resolver, presets, "default controls never overlap or leave the screen"
```

None of this code imports `android.*` or any third-party library, so it is plain Kotlin and its tests run on the JVM.

## Using the transport

```kotlin
val transport = UdpTransport.create(InetSocketAddress(host, port), deviceId, "Pixel 7")   // off the main thread
transport.addListener(object : TransportListener {
    override fun onStateChanged(state: ConnectionState) { /* post to the main thread, e.g. into a StateFlow */ }
    override fun onPaired(deviceKey: ByteArray) { /* PERSIST this; it is a credential */ }
})
transport.pair(secretFromQrOrTypedCode)          // first time
// transport.connect(savedDeviceKey)             // every time after
transport.send(gamepadState)                     // from the touch handler: never blocks, bursts are coalesced
```

Callbacks arrive on transport threads, not the main thread. The transport uses plain threads and callbacks rather
than coroutines so it has no dependencies; wrap it in a `StateFlow` in the ViewModel.

## Setting up the Android Studio project

I could not build with Gradle where this was written (no Android SDK or Maven access), so the Gradle files are
not included. The code was compiled with `kotlinc` 2.2.20 and tested separately. To use it:

1. In Android Studio create **New Project > Empty Activity** (Jetpack Compose) **inside this repo as `manette/android/`**.
   Package name `com.manette.app` is a placeholder; if you pick another, change the `package` lines and folders.
   Set **Minimum SDK: API 29**.
2. Copy the `core/` and `protocol/` folders from `app/src/main/java/com/manette/app/` and
   `app/src/test/java/com/manette/app/` into the matching places in the new project.
3. Run the tests: `./gradlew :app:testDebugUnitTest`. The Empty Activity template already includes JUnit 4.
   The tests find `protocol/test-vectors.json` by walking up from the working directory, which works when the
   project lives at `manette/android/`.

## Rules for this layer

- Keep `Protocol.kt` identical in meaning to `ProtocolConstants.cs`. Change the spec and vectors first.
- Nothing in `core/` or `protocol/` may import `android.*` or any third-party library.
- Writers fill a reusable buffer and return the length; parsers take an explicit `length` because receive
  buffers are larger than the datagram.

## The controller surface

`ControllerSurface(definition, preset, onState)` is the composable. Feed `onState` to `Transport.send`.
Everything behavioural is in `TouchMapper` and tested; the composable only translates pointer events and draws.
It is the one file here that has never been compiled. If Compose renames something (for example `consume()`),
the fix is a one-line change. Control ids in `XboxDefinition.Ids` are part of the saved-preset format: never rename one.
