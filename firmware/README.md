# TiP firmware

PlatformIO project for the physical panda: an ESP32 DevKit (esp32dev, CP2102 USB to UART) with the Arduino framework. Arduino ESP32 core 3.3.12 (ESP-IDF 5.5.5) through the pioarduino platform, pinned to release `55.03.312-1` in `platformio.ini`.

The panda joins WiFi, sets its clock over SNTP, opens the device channel (`docs/DEVICE_PROTOCOL.md`) to the server over verified wss, acks every reaction as soon as it arrives and plays its light pattern on the onboard LED (GPIO2, LEDC PWM) with the virtual panda's timings. It reconnects on its own after WiFi drops, server restarts and dead connections. A PN532 on UART2 emulates an NFC tag holding the panda's tip page, so a phone tap on the coin opens it; the panda works the same without it.

## One time setup (Windows)

1. Python 3 (`winget install Python.Python.3.13` if missing).
2. PlatformIO Core in its usual place:
   ```powershell
   py -m venv $env:USERPROFILE\.platformio\penv
   & $env:USERPROFILE\.platformio\penv\Scripts\python -m pip install platformio
   ```
   Add `%USERPROFILE%\.platformio\penv\Scripts` to `PATH`, or call `pio.exe` by its full path.
3. Turn on Windows long paths once, in an administrator PowerShell. The framework archive holds paths longer than 260 characters and fails to unpack without it:
   ```powershell
   New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
   ```
4. Run `pio` from PowerShell or cmd, not Git Bash. ESP-IDF's tool installer refuses MSYS shells.
5. The CP2102 driver from Silicon Labs. The board shows up as a COM port (`pio device list`).
6. A host C++ compiler for the native tests: `winget install BrechtSanders.WinLibs.POSIX.UCRT` (GCC 16, MinGW-w64). Open a new terminal afterwards so `g++` is on `PATH`.

The first build downloads the platform and toolchain (about 1 GB) and takes several minutes.

## Configuration and secrets

`include/tip_config.h` (committed) holds the server host (`tip-integrated-panda.onrender.com`), port, public base URL (`https://` and the host), device slug (`demo-panda`, used in logs and the tag's URL), the PN532's UART speed and every timing: SNTP servers, TLS and handshake timeouts, the 45 s silence limit, the backoff, the 60 s wait after being replaced, the 1024 byte frame cap, the queue of 8, the ring of 16 ids and the NFC retries and re-arm times.

`include/secrets.h` (git ignored, template `include/secrets.example.h`) holds what differs per deployment:

```powershell
Copy-Item include\secrets.example.h include\secrets.h
```

- `TIP_WIFI_SSID`, `TIP_WIFI_PASSWORD`: a 2.4 GHz network.
- `TIP_DEVICE_ID`: the device's uuid. The server logs the demo device's id on every startup: `Demo device demo-panda has id ...`.
- `TIP_DEVICE_SECRET`: the device secret, `TIP_DEMO_DEVICE_SECRET` of that server. The production value differs from the local one.

`git check-ignore -v firmware/include/secrets.h` shows the ignore rule. Never commit the file or paste it anywhere. The build stops when it is missing, and the firmware refuses to start with the placeholder id or secret. Neither the secret nor the `Authorization` header is ever printed.

## Build, flash, monitor, test

From `firmware/`:

```powershell
pio run                                  # build (esp32dev, the default environment)
pio run -t upload --upload-port COM8     # flash (921600 baud)
pio device monitor --port COM8           # serial at 115200, Ctrl+C to quit
pio test -e native                       # Unity tests of lib/tip_core on the host
```

Uploading needs no button presses: esptool resets the board into the bootloader through RTS and DTR. Close the serial monitor before uploading, since only one program can hold the port. The partition table is `huge_app.csv` (3 MB app, no OTA); the image is about 1.15 MB.

The native tests are linked statically (`scripts/static_link.py`): a dynamically linked test on Windows loads the first `libstdc++-6.dll` on `PATH`, which may belong to another program and fails with `0xC0000139`. CI runs `pio run` and `pio test -e native` in the `firmware` job with `secrets.example.h` copied to `secrets.h`.

## Structure

- `lib/tip_core/` is plain C++ with no Arduino includes, tested on the host: `tip_protocol` (parsing server messages, building acks), `tip_intake.h` (the ring of the last 16 ids: ack always, play once), `tip_player.h` (the queue of 8, dropping the oldest waiting reaction, never the one playing, and the LED level at any time), `tip_patterns` (the four patterns and the status flicks), `tip_link` (backoff, silence check, HTTP status line, certificate dates), `tip_ndef` (the tip URL and its NDEF URI record), `tip_type4_tag` (the read only Type 4 tag's APDU answers), `tip_pn532_frame` (PN532 frames: build, read back, check).
- `src/network_task.cpp`: WiFi, SNTP, the WebSocket and its state machine (idle, connecting, connected, rejected), acks.
- `src/playback_task.cpp`: the only code that touches the LED.
- `src/secure_transport.cpp`: the TLS client under the WebSocket library.
- `src/nfc_task.cpp`: finds the PN532, arms the tag, answers taps, recovers; `src/pn532.cpp`: the PN532 over HSU and the commands the tag needs.

### Tasks and cores

| Task | Core | Priority | Does |
|---|---|---|---|
| WiFi driver, lwIP | 0 | ESP-IDF's | radio and TCP/IP |
| `net` (12 KB stack) | 1 | 2 | WiFi and time checks, TLS, WebSocket loop, parse, ack, hand over |
| `led` (4 KB stack) | 1 | 3 | every 10 ms: take reactions from the FreeRTOS queue, advance playback, write the LED |
| `nfc` (6 KB stack) | 1 | 1 | the PN532: find it, arm the tag, answer a phone's APDUs, re-arm, recover |

The playback task runs above the network task on the same core, so its 10 ms tick preempts a TLS handshake (about 2 s of mbedtls work) and a pattern never stutters. The network task never waits for playback: it acks first, then hands the reaction over with a non blocking `xQueueSend`, so a 9 s pattern cannot delay acks, pings or reconnects. The NFC task runs below both, so it only gets the core when they wait; every wait for the PN532 is a `vTaskDelay(1)` poll of the UART, never a spin. It shares nothing with the network task: a tap never touches hello, acks, replays or sends. The network task logs the longest pause between two of its steps once a minute (`Net: longest pause between steps 11 ms`); 10 ms of it is its own delay. Application code stays off core 0, where the radio has timing sensitive work. The Arduino loop task deletes itself.

### Behaviour

- Ack on receipt: a reaction that parses is acked at once, before it is queued. A repeat of one of the last 16 ids is acked again but not played again.
- Queue: 8 reactions wait behind the one playing; when full, the oldest waiting one is dropped and logged.
- Untrusted input: frames over 1024 bytes are dropped unparsed. The JSON goes into a 256 byte `StaticJsonDocument` through a filter that keeps only `type`, `id`, `reaction`, `repeats` and `reason`, so anything else the server might send is never stored. The id must be a lowercase uuid. A missing or non integer `repeats` is 1, others are clamped to 1 to 5. An unknown kind is acked and played as `PLAIN` with repeats 1. A malformed message is logged and not acked.
- Dead connection: nothing received for 45 s (the server pings every 20 s) closes and reconnects.
- Reconnect: 1 s doubling to 60 s, each delay shortened by up to 20 % at random. The backoff starts over only after a connection stayed up 60 s, after WiFi drops too. After `closing` with reason `replaced` the wait is at least 60 s.
- A 401 on the handshake logs "auth rejected", shows three quick flicks every 5 s and tries again every 10 minutes, so a corrected secret on the server is picked up without touching the panda. While connecting the LED flicks for 30 ms every second.
- These are the client policy of `docs/DEVICE_PROTOCOL.md`; the fake device and the virtual panda follow it too.

### Logging

Everything that reaches the serial port goes through `tip::logLine` (`src/log.cpp`): it formats the whole line into a 256 byte local buffer and writes it under a FreeRTOS mutex, so lines from the tasks never interleave. ESP-IDF's own log output (WiFi, TLS) is routed through it with `esp_log_set_vprintf`. The Arduino HAL's `log_e` writes straight to the UART with `ets_printf`, past any lock, so it is off (`CORE_DEBUG_LEVEL=0`); the firmware logs TLS failures itself. Only the ROM's boot messages and a panic dump still bypass it.

## NFC tag

Wiring: a PN532 red board with its DIP switch at 0 0 (HSU). PN532 GND to GND, VCC to 3V3, SDA (PN532 TX) to GPIO16 (RX2), SCL (PN532 RX) to GPIO17 (TX2). The LED stays on GPIO2. Solder the PN532's header well: in brief 05c a cold SDA joint left it silent while its power LED stayed on.

The PN532 emulates an NFC Forum Type 4 tag, read only, holding one NDEF URI record: `TIP_PUBLIC_BASE_URL` `/t/` `TIP_DEVICE_SLUG`, the tip page (`https://tip-integrated-panda.onrender.com/t/demo-panda`, a 51 byte message). The URL must be https, with a lowercase host and a slug matching the server's (`[a-z0-9-]{3,40}`); the build fails when it cannot fit the 128 byte NDEF file (at most 129 characters), and at boot an invalid one turns NFC off with a log line.

- Link: at boot it sends an abort (the PN532 does not reset with the ESP32 and may still be inside `TgInitAsTarget`) and a wake-up, asks for the firmware version (IC 0x32, 1.6) at the build's speed and then the other one (115200, 921600), moves the PN532 to the build's speed and sends SAMConfig. The speed is 115200, the one phones were tested with; `PLATFORMIO_BUILD_FLAGS="-DTIP_NFC_BAUD=921600"` picks the fast link.
- A tap: `TgInitAsTarget` (PICC only, passive only), then each APDU through `TgGetData` and `TgSetData`. READ BINARY is bounded by the selected file (15 byte capability container, 128 byte NDEF file): an offset plus Le past its end, or Le 0 (256), answers 6B00. Each tap logs one line: `NFC: tap 3, 7 APDUs in 152 ms, ended by TgGetData status 41 (0x29)` (0x29 is the phone releasing the tag).
- Re-arm: after a read the tag stays off for 3 s, so an iPhone left on the coin does not show a second prompt, then until there has been no field for 100 ms (at most 3 s more).
- Missing or silent PN532: a failure while it answered gets a wake-up and SAMConfig, up to 6 tries from 20 ms doubling. If it still does not answer, or is missing at boot, the task looks for it again after 1 s, doubling to every 30 s, with a log line each time. While armed and untouched, every 10 s it stops the tag (for about 20 ms) and asks for the firmware version, so a PN532 that was unplugged or lost power is found again. None of this affects the WebSocket, acks or the LED.

The HSU link is our own code (`tip_pn532_frame`, `src/pn532.cpp`), written from the PN532 user manual; the Seeed-Studio/PN532 library the 05c spike used is not a dependency, because its HSU receive spins without yielding while the tag is armed. `tip_type4_tag` is ported from that library's `emulatetag.cpp` (Seeed-Studio/PN532 v1.1.0, BSD-3-Clause, Copyright (c) 2012 Adafruit Industries, (c) 2013 Seeed Technology Inc.); the file carries the licence text, and a firmware binary given to anyone else must carry it too. `spikes/pn532/` is the 05c spike, kept as evidence and not built by CI.

## WebSocket library

links2004/WebSockets 2.7.1 in its `NETWORK_CUSTOM` mode (`-DWEBSOCKETS_NETWORK_TYPE=10`): the firmware supplies the TLS client (`src/secure_transport.cpp`, a wrapper around `NetworkClientSecure`), so certificate checks are ours, not the library's. The library sees server pings as `WStype_PING` events and answers them, and every event resets the silence timer. 2.7.2 and 2.7.3 do not compile in this mode (they call `setCertificate` and `setPrivateKey`, which their own custom client class does not declare). 2.7.1 does not report the HTTP status of a failed handshake, so the transport reads the status line itself to recognise a 401. The library's default `Origin: file://` header is removed, because the server's same origin check would refuse it.

Alternatives looked at: gilmaimon/ArduinoWebsockets (unmaintained since 2020, no CA bundle, does not build on core 3), ESP-IDF's `esp_websocket_client` (not in the precompiled Arduino core, and no access to the peer certificate chain), ArduinoHttpClient's `WebSocketClient` (blocking, answers pings internally without telling the caller).

## TLS

The device verifies the full chain against ESP-IDF's built in Mozilla CA bundle (`useBuiltinCACertBundle()`, `MBEDTLS_SSL_VERIFY_REQUIRED`) and checks the hostname. After the handshake and before the library sends anything, it checks notBefore and notAfter of every certificate the server presented (leaf and intermediates) against SNTP time; any failure closes the connection and counts as a failed attempt. It never connects while the clock is unset (before 2025). There is no revocation checking (no OCSP, no CRL). `setInsecure()` is never called; the custom client ignores every trust setting the library tries to make, so nothing can turn verification off.

The precompiled ESP-IDF libraries that ship with the core are built with `CONFIG_MBEDTLS_CERTIFICATE_BUNDLE_DEFAULT_FULL`, so the full Mozilla root store, as packaged in that ESP-IDF release, is linked into the firmware. The bundle has no file in this repo and changes only with the core: to pick up new roots, move `platform` in `platformio.ini` to a newer pioarduino release and rebuild. mbedtls itself is built without `CONFIG_MBEDTLS_HAVE_TIME_DATE` and skips validity dates, which is why the firmware checks them.

SNTP uses `pool.ntp.org`, `time.google.com` and `time.cloudflare.com`; while waiting it logs a line every 5 s, so a network that blocks NTP is obvious on serial.
