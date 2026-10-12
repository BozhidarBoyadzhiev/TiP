# TiP firmware

PlatformIO project for the physical panda: an ESP32 DevKit (esp32dev, CP2102 USB to UART) with the Arduino framework. Arduino ESP32 core 3.3.12 (ESP-IDF 5.5.5) through the pioarduino platform, pinned to release `55.03.312-1` in `platformio.ini`.

The panda joins WiFi, sets its clock over SNTP, opens the device channel (`docs/DEVICE_PROTOCOL.md`) to the server over verified wss, acks every reaction as soon as it arrives and plays its light pattern on the onboard LED (GPIO2, LEDC PWM) with the virtual panda's timings. It reconnects on its own after WiFi drops, server restarts and dead connections.

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

`include/tip_config.h` (committed) holds the server host (`tip-integrated-panda.onrender.com`), port, device slug (`demo-panda`, used in logs) and every timing: SNTP servers, TLS and handshake timeouts, the 45 s silence limit, the backoff, the 60 s wait after being replaced, the 1024 byte frame cap, the queue of 8 and the ring of 16 ids.

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

- `lib/tip_core/` is plain C++ with no Arduino includes, tested on the host: `tip_protocol` (parsing server messages, building acks), `tip_intake.h` (the ring of the last 16 ids: ack always, play once), `tip_player.h` (the queue of 8, dropping the oldest waiting reaction, never the one playing, and the LED level at any time), `tip_patterns` (the four patterns and the status flicks), `tip_link` (backoff, silence check, HTTP status line, certificate dates).
- `src/network_task.cpp`: WiFi, SNTP, the WebSocket and its state machine (idle, connecting, connected, rejected), acks.
- `src/playback_task.cpp`: the only code that touches the LED.
- `src/secure_transport.cpp`: the TLS client under the WebSocket library.

### Tasks and cores

| Task | Core | Priority | Does |
|---|---|---|---|
| WiFi driver, lwIP | 0 | ESP-IDF's | radio and TCP/IP |
| `net` (12 KB stack) | 1 | 2 | WiFi and time checks, TLS, WebSocket loop, parse, ack, hand over |
| `led` (4 KB stack) | 1 | 3 | every 10 ms: take reactions from the FreeRTOS queue, advance playback, write the LED |

The playback task runs above the network task on the same core, so its 10 ms tick preempts a TLS handshake (about 2 s of mbedtls work) and a pattern never stutters. The network task never waits for playback: it acks first, then hands the reaction over with a non blocking `xQueueSend`, so a 9 s pattern cannot delay acks, pings or reconnects. Application code stays off core 0, where the radio has timing sensitive work. The Arduino loop task deletes itself.

### Behaviour

- Ack on receipt: a reaction that parses is acked at once, before it is queued. A repeat of one of the last 16 ids is acked again but not played again.
- Queue: 8 reactions wait behind the one playing; when full, the oldest waiting one is dropped and logged.
- Untrusted input: frames over 1024 bytes are dropped unparsed. The JSON goes into a 256 byte `StaticJsonDocument` through a filter that keeps only `type`, `id`, `reaction`, `repeats` and `reason`, so anything else the server might send is never stored. The id must be a lowercase uuid. A missing or non integer `repeats` is 1, others are clamped to 1 to 5. An unknown kind is acked and played as `PLAIN` with repeats 1. A malformed message is logged and not acked.
- Dead connection: nothing received for 45 s (the server pings every 20 s) closes and reconnects.
- Reconnect: 1 s doubling to 60 s, each delay shortened by up to 20 % at random. The backoff starts over only after a connection stayed up 60 s, after WiFi drops too. After `closing` with reason `replaced` the wait is at least 60 s.
- A 401 on the handshake stops reconnecting until a reboot and shows three quick flicks every 5 s. While connecting the LED flicks for 30 ms every second.

## WebSocket library

links2004/WebSockets 2.7.1 in its `NETWORK_CUSTOM` mode (`-DWEBSOCKETS_NETWORK_TYPE=10`): the firmware supplies the TLS client (`src/secure_transport.cpp`, a wrapper around `NetworkClientSecure`), so certificate checks are ours, not the library's. The library sees server pings as `WStype_PING` events and answers them, and every event resets the silence timer. 2.7.2 and 2.7.3 do not compile in this mode (they call `setCertificate` and `setPrivateKey`, which their own custom client class does not declare). 2.7.1 does not report the HTTP status of a failed handshake, so the transport reads the status line itself to recognise a 401. The library's default `Origin: file://` header is removed, because the server's same origin check would refuse it.

Alternatives looked at: gilmaimon/ArduinoWebsockets (unmaintained since 2020, no CA bundle, does not build on core 3), ESP-IDF's `esp_websocket_client` (not in the precompiled Arduino core, and no access to the peer certificate chain), ArduinoHttpClient's `WebSocketClient` (blocking, answers pings internally without telling the caller).

## TLS

The device verifies the full chain against ESP-IDF's built in Mozilla CA bundle (`useBuiltinCACertBundle()`, `MBEDTLS_SSL_VERIFY_REQUIRED`) and checks the hostname. After the handshake and before the library sends anything, it checks notBefore and notAfter of every certificate the server presented (leaf and intermediates) against SNTP time; any failure closes the connection and counts as a failed attempt. It never connects while the clock is unset (before 2025). There is no revocation checking (no OCSP, no CRL). `setInsecure()` is never called; the custom client ignores every trust setting the library tries to make, so nothing can turn verification off.

The precompiled ESP-IDF libraries that ship with the core are built with `CONFIG_MBEDTLS_CERTIFICATE_BUNDLE_DEFAULT_FULL`, so the full Mozilla root store, as packaged in that ESP-IDF release, is linked into the firmware. The bundle has no file in this repo and changes only with the core: to pick up new roots, move `platform` in `platformio.ini` to a newer pioarduino release and rebuild. mbedtls itself is built without `CONFIG_MBEDTLS_HAVE_TIME_DATE` and skips validity dates, which is why the firmware checks them.

SNTP uses `pool.ntp.org`, `time.google.com` and `time.cloudflare.com`; while waiting it logs a line every 5 s, so a network that blocks NTP is obvious on serial.
