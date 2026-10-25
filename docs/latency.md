# Latency: what we measure, what we changed, what to try

## What the controllertest.io number means
Its page says it measures "the time between your controller sending data packets", not total system latency. So:
- **Sticks ~20 ms** is mostly the *cadence of touch events*: a moving finger produces a new position about every frame
  (60 Hz is 16.7 ms). A 120 Hz phone should read about half that. It is not network delay.
- **Buttons ~80 ms and d-pad ~35 ms** are almost certainly the gaps between *your taps* (a human tapping is 5-12 per
  second), not delay. A tap is one event, so there is no stream to measure. I could not confirm how the page treats
  buttons, so treat these two numbers as unreliable rather than as a fault.
None of the three is touch-to-game latency, so none says whether the app feels slow.

## What actually adds delay (rough budget)
1. Touch screen + Android input pipeline: often 8-25 ms. Largest piece, mostly outside our control.
2. Our code (queue, send thread, UDP): under 1 ms.
3. Wi-Fi: about 2-8 ms on a good 5 GHz link, with occasional spikes; USB tethering is steadier.
4. PC receive + ViGEm submit: under 1 ms.
5. The game polls the pad (often once per frame) and then renders: not ours.

## Measure it properly
- **In-app RTT** (status line on the play screen) is phone to PC and back, about twice the one-way network delay.
- **Camera test:** film the phone and the PC screen together at 120/240 fps. Count frames from the finger landing on a
  button to the pad lighting up in a gamepad tester (hardwaretester.com/gamepad is event-driven). Repeat 10 times and
  average. This captures everything except the game and display.

## Experiments tried and removed (re-add only if needed)
It already felt responsive on a real phone, so these were removed rather than shipped unproven:
- **Unbuffered touch dispatch:** use `pointerInteropFilter` to get the raw `MotionEvent` and call
  `view.requestUnbufferedDispatch(event)` on `ACTION_DOWN`, so touches arrive as reported instead of batched to the frame.
- **DSCP EF (0xB8) on the UDP socket:** `DatagramSocket.trafficClass = 0xB8`, so WMM routers use the low-latency queue.
Already in place: Wi-Fi low-latency lock, send on a dedicated thread, a packet on every change, 20 Hz keepalive,
no driver call when nothing changed.

## Things to try (no code)
- PC on Ethernet, phone on 5 GHz Wi-Fi close to the router, or USB tethering.
- PC Wi-Fi adapter: disable "allow the computer to turn off this device to save power" and any power-saving mode.
- Phone: disable battery saver; use the highest refresh rate (90/120 Hz) setting.
- Turn off VPNs and heavy background network use on both devices.
