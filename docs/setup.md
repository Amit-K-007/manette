# Manette setup and first run

## What works today

| Piece | State |
|---|---|
| Windows companion (`manette` CLI): UDP server, pairing, sessions, ViGEm virtual Xbox pads | Built. Tested on Linux with fake pads. **ViGEm on real Windows has not been run yet.** |
| FakeClient (console "phone") | Built and tested. |
| Android protocol + transport + layout/touch core | Built and tested on a plain JVM. |
| Android app: connect screen, play screen, Wi-Fi/keep-awake/immersive glue | Written, **never compiled** (no Android toolchain was available). Expect a few small compile fixes. |

Do step 1 (Windows) first, then step 4 (phone).

## 1. Windows PC

**You need:** Windows 10/11, the .NET 10 SDK, and the ViGEmBus driver.

1. Install the **.NET 10 SDK** from dotnet.microsoft.com. Check with `dotnet --version`.
2. Install **ViGEmBus**: download the latest installer from the nefarius/ViGEmBus releases page on GitHub and run it
   (admin, may need a reboot). Note: the project is no longer actively developed, but still works. Confirm it installed
   in Device Manager under "System devices" as "Nefarius Virtual Gamepad Emulation Bus".
   **Security step (do this after installing):** ViGEmBus is retired (see its end-of-life statement at
   docs.nefarius.at/projects/ViGEm/End-of-Life). Its bundled auto-updater was pointed at a domain the maintainers no
   longer control, which they describe as a potential security risk. Edit (as admin)
   `C:\Program Files\Nefarius Software Solutions\ViGEm Bus Driver\ViGEmBus_Updater.ini` and change
   `URL=https://updates.vigem.org/api/github/ViGEm/ViGEmBus/updates` to
   `URL=https://aiu.api.nefarius.systems/api/github/ViGEm/ViGEmBus/updates`, or disable/uninstall the updater service.
3. **Firewall:** nothing to add by hand. The first time Manette runs, Windows asks to allow it: click **Allow access**
   and tick Private and Public (see "Windows Firewall prompt" below). If a managed PC has that prompt switched off and
   phones cannot connect, add this once (admin PowerShell), where the program path is your `Manette.exe` (or `dotnet.exe`
   for the console build): `netsh advfirewall firewall add rule name="Manette" dir=in action=allow program="<path>" protocol=UDP profile=any`
4. Build and start the companion from the repo root:
   ```
   cd companion-windows
   dotnet run --project src/Manette.Cli
   ```
   It prints a **QR code**, a **typed pairing code** and a `manette://` link, valid 5 minutes, single use.
   The QR needs a terminal that understands colours (Windows Terminal does); use `--no-qr` if it shows garbage. Keys while running:
   `p` new code, `s` status, `q` quit. If it says the driver is missing, ViGEmBus is not installed or not running.
5. Package restore needs internet the first time (it downloads `Nefarius.ViGEm.Client` and the test packages).

### Tray app and installer (instead of the console)
The tray app is the everyday way to run Manette: an icon near the clock, a pairing window with the QR code, a list of
connected and trusted phones (click to disconnect or forget), "Start with Windows", and a clear message if the driver is
missing. **It has never been compiled** (no Windows toolchain where it was written), so expect small fixes on first build.
- Run it from source: `cd companion-windows` then `dotnet run --project src/Manette.App`. Double-click the tray icon to pair.
- Do not run it and the console `manette` together: they share the same UDP port and data folder.
- Settings (port, PC name, discovery on/off) are in `%APPDATA%\Manette\settings.json`; bad values are repaired, not fatal.
- Logs: `%APPDATA%\Manette\manette.log`.
- **Installer:** install Inno Setup 6, then `powershell -File companion-windows\installer\build.ps1`. It publishes the app
  (self-contained, a folder, not single-file) and builds `installer\output\Manette-Setup-0.1.0.exe`, which adds one program-scoped
  firewall rule (UDP, all profiles), an optional "start with Windows", repoints ViGEmBus's updater if the driver is
  already installed, and offers the driver download page if not. It does **not** bundle ViGEmBus. Untested: try an install and
  an uninstall on a clean PC.
- **Protected keys:** trusted-phone keys are now encrypted with Windows DPAPI (current user) in `trusted-devices.v2.json`;
  an old plain-text `trusted-devices.json` is imported once and deleted. To check: pair a phone, quit, start again, reconnect
  without a code; the file must not contain the key. (DPAPI was written without Windows to run it; this check is how we know.)

## 2. Check the PC half with no phone

In a second terminal on the same PC:
```
cd companion-windows
dotnet run --project tests/FakeClient -- pair --host 127.0.0.1 --code <TYPED-CODE> --script sticks --seconds 30
```
Then open a gamepad tester in a browser (for example hardwaretester.com/gamepad) or run `joy.cpl`.
**Pass:** a new "Xbox 360 Controller" appears and its sticks sweep in circles. Try `--script buttons` and `--script triggers`.

Further checks:
- Run `fakeclient play --host 127.0.0.1 --script demo` again: it reconnects with no code.
- Pair a second fake phone with a new code and `--id fc2.json`: it must appear as a second, separate controller.
- Reuse an old code: it must fail with `PairingNotOpen`.
- With a real controller already plugged in, the virtual pad takes the next free XInput slot (Windows has only 4).

## 3. Network

- Phone and PC on the same network, ideally **5 GHz Wi-Fi**. Some routers block device-to-device traffic
  ("AP isolation", often on guest networks): turn it off.
- Find the PC's address with `ipconfig` (IPv4 of the Wi-Fi/Ethernet adapter). The QR/link lists every address.
- **USB tethering** (lowest, steadiest latency): enable it on the phone, then use the PC's address on that adapter
  (often the phone's gateway, such as 192.168.42.x or 192.168.137.1). Same UDP port, same firewall rule.
- Auto-discovery (mDNS) may be blocked by other software holding UDP 5353; the CLI logs this and carries on. Manual
  IP always works. From another machine, `fakeclient discover` lists PCs it can find.

## 4. Phone

I could not generate a Gradle project I could trust (versions I cannot check), so you create it with Android Studio
and drop our sources in. This adds no extra dependencies.

1. **New Project > Empty Activity** (Jetpack Compose). Name `Manette`, **save it at `manette/android/`**, minimum SDK
   **API 29**. Package `com.manette.app` is a placeholder; if you pick another, change the `package`/`import`
   lines (find-and-replace `com.manette.app`). Do this now, it is hard to change later.
2. Delete the wizard's `MainActivity.kt` and its `ui/theme` folder (ours uses plain `MaterialTheme`).
3. Copy everything from this repo's `android_src/app/src/main/java/com/manette/app/` and `android_src/app/src/test/java/com/manette/app/`
   into the matching folders of your project (our `MainActivity.kt` replaces the deleted one).
4. In `AndroidManifest.xml` add, above `<application>`:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
   <uses-permission android:name="android.permission.WAKE_LOCK" />
   ```
   and on the `<activity>` add `android:screenOrientation="sensorLandscape"` and
   `android:configChanges="orientation|screenSize|keyboardHidden"`.
   **QR scanning dependency:** add to the app module's `dependencies` (check Maven for the latest version):
   `implementation("com.google.android.gms:play-services-code-scanner:16.1.0")`. It uses Google Play services' scanner,
   so there is no camera permission and no CameraX. Optional but recommended, inside `<application>` in the manifest:
   `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="barcode_ui" />`
   (downloads the scanner at install time). On a phone without Play services scanning reports an error; paste the
   link or code instead.
5. Sync and build. Likely small fixes: a Compose function renamed in your version (for example `consume()` in
   `ControllerSurface.kt`), or a missing import. Paste any errors to me.
6. Run the unit tests first (`./gradlew :app:testDebugUnitTest`): they should all pass (84 on the JVM). If any fail
   in Android Studio but passed for me, that is a real finding.
7. Turn on **USB debugging** on the phone and run the app on it.

### Pairing the easy way
- **Scan:** on the connect screen tap **Scan QR code from the PC**, point at the QR in the PC terminal. It pairs and connects.
  A QR that lists several PC addresses (Wi-Fi and a USB tether) uses the one on the phone's own subnet.
- **Discovery:** PCs on the network appear under "PCs on this network" (with "ready to pair" while a code is open).
  Tap one to fill the address, then type the code. If your PC's IP address changes later, the app finds it again by its
  install id and updates the saved address by itself.
- If discovery shows nothing: allow UDP 5353 in the PC firewall (above), check AP isolation, and note that **discovery is
  the least-tested part** (the PC answers multicast mDNS; only the unicast path was tested). QR and manual IP always work.

### First phone run
1. Start the PC companion (step 1). Note the PC's IPv4 (`ipconfig`) and the typed code.
2. In the app enter the IP and the code, tap **Pair and connect**. You should land on the controller screen.
3. Open the gamepad tester on the PC. Press buttons, drag both sticks, use two thumbs at once.
4. **Look for:** wrong or flipped axes (up on the stick must be up), buttons that do not register, the back-gesture
   interfering at the screen edges, controls too small or badly placed, drops, and lag.
5. Tap **Exit**, then **Reconnect**: it must work with no code. Leave the app and return: it releases and reconnects.
6. Try a second phone with a new code: it must be a second controller.
7. Latency: the status line shows round-trip ms. For feel, record the phone and the tester with a 240 fps camera.

## Windows Firewall "Allow access" prompt
Windows shows this the first time a program starts listening. **Click "Allow access" and tick both Private and Public.**
That creates the rules (confirmed: two program-scoped Allow rules, and phones connect). Do not close the box with the X: Windows
then records Block rules for the program, and an enabled Block rule beats an Allow rule. To clear stale ones (admin PowerShell),
then run Manette again and click Allow:
```
Get-NetFirewallRule -DisplayName "Manette","manette" | Where-Object { $_.Action -eq 'Block' } | Remove-NetFirewallRule
```
Rules are per program path, so each of `dotnet.exe` (console build), a dev-run tray app and an installed `Manette.exe` prompts once.
Ticking only Public (or only Private) leaves the other network type blocked: if you change network type, you will be asked again.

**The installer adds the rule itself** instead of relying on the prompt, because the prompt needs an administrator to answer, can be
disabled by policy (then the app is blocked silently), and only covers the profiles you tick. It adds one UDP-only rule for
`Manette.exe` on every profile and removes it, plus any prompt-created rules for that program, on uninstall.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "ViGEmBus driver is not installed" | Install it; reboot; check Device Manager. |
| Client says "did not respond" | Firewall rule missing, wrong IP, or AP isolation. Try `--host 127.0.0.1` on the PC first. |
| `PairingNotOpen` | Code expired (5 min), already used, or press `p` for a new one. |
| `NotPaired` | Phone has a saved key the PC forgot (`trusted-devices.json` deleted). Pair again. |
| Connects, then drops every few seconds | Wi-Fi power saving or packet loss; try 5 GHz or USB tether. |
| Game ignores the pad | Some games read only the first controller; check Steam Input for duplicates. |

Trusted devices live in `%APPDATA%\Manette\trusted-devices.json`. **Device keys are stored in plain text for now.**

## Not verified yet (tell me what you see)

ViGEm on real Windows; the bundled native ViGEm DLL under `dotnet publish` single-file; mDNS from a real Android
phone; every Compose and Android API call; real-device latency and touch feel.
