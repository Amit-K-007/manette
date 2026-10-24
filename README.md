# Manette

Turn Android phones into separate virtual Xbox controllers on a Windows PC, over Wi-Fi (or USB tethering).

```
docs/protocol.md                 the wire spec: source of truth
protocol/test-vectors.json       byte-exact vectors (generated)
protocol/generate_test_vectors.py  regenerate the vectors when the spec changes
companion-windows/               C# / .NET 10 Windows companion
  src/Manette.Core/              protocol, pairing, sessions, server logic. No driver, UI or socket code.
  src/Manette.Network/           UDP socket, tick loop, mDNS advertiser, ManetteHost (the engine)
  src/Manette.Backend.ViGEm/     the only project that references ViGEm: virtual Xbox 360 pads
  src/Manette.Cli/               headless companion ("manette"), runs the whole engine from a console
  tests/Manette.Core.Tests/      xUnit tests, driven by the shared vectors
  tests/Manette.Network.Tests/   real-UDP loopback end-to-end tests + DNS tests
  tests/FakeClient/              console "phone": reference client and test tool
android/                         Kotlin protocol layer (done, tested); UI/transport not started
```

## Manette.Core in one picture

```
 UDP socket (Manette.Network, TODO)
        |  bytes + source address
        v
  ManetteServer.HandleDatagram   <- rate limit, version check, HELLO/INPUT/PING/BYE dispatch
        |             |
 PairingManager   SessionManager  <- one session per phone, keys, seq order, Active/Stale/Grace
 (QR secret,       |
  5-min window)    v
                IVirtualPad / IVirtualPadFactory   <- implemented by Manette.Backend.ViGEm (TODO)
```

Core never touches a socket or a driver. The network layer implements `IDatagramSender` and calls
`HandleDatagram`; the ViGEm backend implements `IVirtualPad`. Call `ManetteServer.Tick()` about every 50 ms.

## Try it (no phone needed)

```
# terminal 1: the companion. Windows with ViGEmBus installed drives real pads; --fake-pads works anywhere.
dotnet run --project src/Manette.Cli -- --fake-pads
#   prints a typed pairing code and a manette:// link

# terminal 2: a fake phone
dotnet run --project tests/FakeClient -- pair --host 127.0.0.1 --code <typed code> --seconds 10
dotnet run --project tests/FakeClient -- play --host 127.0.0.1 --script sticks     # reconnects, no code
dotnet run --project tests/FakeClient -- discover                                  # finds the PC over mDNS
```

On Windows, drop `--fake-pads`, install ViGEmBus, and open a gamepad tester: the fake phone appears as an Xbox 360 controller.

## Build and test

```
cd companion-windows
dotnet build
dotnet test
```

Requires the .NET 10 SDK. The tests read `protocol/test-vectors.json` from the repo root.

## Changing the protocol

1. Edit `docs/protocol.md`.
2. Update `protocol/generate_test_vectors.py`, then `python3 protocol/generate_test_vectors.py --out protocol/test-vectors.json`.
3. Update `ProtocolConstants.cs` / `PacketCodec.cs` until `dotnet test` passes.
4. Commit all of it together.
