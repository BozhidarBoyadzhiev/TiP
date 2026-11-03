# TiP firmware

The firmware for the physical panda. It joins WiFi, connects to the TiP server and plays a light pattern on the LED for every tip. A PN532 behind the coin acts as an NFC tag, so a phone tap opens the panda's tip page.

How it works inside is in [DESIGN.md](DESIGN.md).

## Parts

- ESP32 DevKit: ESP-WROOM-32, 30 pin, with the CP2102 USB to UART chip.
- PN532 NFC board, the red one.
- A USB power bank and a USB to 2 pin lead into the DevKit's VIN and GND.

The LED is the board's own LED on GPIO2. Nothing extra is needed for it.

## Wiring

Set the PN532's DIP switch to 0 0. That selects HSU, its serial mode.

| PN532 | ESP32 |
|---|---|
| GND | GND |
| VCC | 3V3 |
| SDA (PN532 TX) | GPIO16 (RX2) |
| SCL (PN532 RX) | GPIO17 (TX2) |

Solder the PN532's header well. In brief 05c a cold SDA joint left the board silent while its power LED stayed on.

## Building it into the case

The full build is in [panda/README.md](../panda/README.md). The rules that matter for the electronics:

- Put the PN532 in a window in the case, antenna side out and level with the outside, so only paper covers it. In brief 05c a phone through 3 to 4 mm of cardboard never reached it.
- Keep metal away from the antenna. In the panda the power bank lies inside the body, away from it.
- The light is the DevKit's onboard blue LED, so the DevKit sits behind a window too. In the panda it lies on its side behind the nose.
- Do not put an NFC sticker on the case. A sticker answers the phone instead of the panda.

The panda works the same without the PN532. It just has no tag.

## Setup (Windows)

1. Install Python 3 if it is missing: `winget install Python.Python.3.13`.
2. Install PlatformIO Core:
   ```powershell
   py -m venv $env:USERPROFILE\.platformio\penv
   & $env:USERPROFILE\.platformio\penv\Scripts\python -m pip install platformio
   ```
   Add `%USERPROFILE%\.platformio\penv\Scripts` to `PATH`, or call `pio.exe` by its full path.
3. Turn on Windows long paths once, in an administrator PowerShell. The framework archive has paths longer than 260 characters and will not unpack without it:
   ```powershell
   New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -PropertyType DWORD -Force
   ```
4. Run `pio` from PowerShell or cmd, not Git Bash. ESP-IDF's tool installer refuses MSYS shells.
5. Install the CP2102 driver from Silicon Labs. The board then shows up as a COM port (`pio device list`).
6. For the native tests, install a C++ compiler: `winget install BrechtSanders.WinLibs.POSIX.UCRT` (GCC 16, MinGW-w64). Open a new terminal afterwards, so `g++` is on `PATH`.

The first build downloads the platform and toolchain, about 1 GB, and takes several minutes.

On macOS or Linux, install PlatformIO Core with pip and use the board's serial port (`/dev/tty…`) in place of `COM8`. Steps 3 to 6 are Windows only, apart from needing `g++` for the native tests.

## Secrets

`include/secrets.h` holds what differs per panda. It is git ignored. Make it from the template:

```powershell
Copy-Item include\secrets.example.h include\secrets.h
```

| Value | Where it comes from |
|---|---|
| `TIP_WIFI_NETWORKS` | 1 to 4 WiFi networks the panda may join, each `{"ssid", "password"}`. They must be 2.4 GHz. |
| `TIP_DEVICE_ID` | The device's uuid. The server logs the demo device's id at every start: `Demo device demo-panda has id ...`. |
| `TIP_DEVICE_SECRET` | `TIP_DEMO_DEVICE_SECRET` of that server. The production value differs from the local one. |

### WiFi networks

`TIP_WIFI_NETWORKS` is a comma separated list of networks, for example your WiFi at home and a phone hotspot for a market stall:

```c
#define TIP_WIFI_NETWORKS {"home-wifi", "home-password"}, {"phone-hotspot", "hotspot-password"}
```

To add a network, append `, {"ssid", "password"}` to the line and flash again. An open network has the password `""`. SSIDs are matched exactly, capitals included. The build stops when the list has no network, more than 4, or an empty SSID. The template's `your-wifi-name` stops the firmware at boot with `Config: replace the template network ...`.

At start, and whenever WiFi drops, the panda scans and tries the listed networks it can see, strongest first, each for up to 15 seconds. When it sees none of them (a hidden SSID, or a scan that missed it), it tries each once in list order. When every try fails, it waits (1 second, doubling to a minute) and scans again. The order in the list only breaks ties and sets the order of blind tries.

`git check-ignore -v firmware/include/secrets.h` shows the ignore rule. Never commit the file or paste it anywhere. The build stops when it is missing. With the template's placeholder id or secret, the firmware does not connect: it logs `Config: set TIP_DEVICE_ID ...` and shows the rejected pattern. The firmware never prints the secret, a WiFi password or the `Authorization` header.

Everything else, such as the server host and the device slug, is in `include/tip_config.h`, which is committed.

## Build, flash, monitor, test

From `firmware/`:

```powershell
pio run                                                  # build (esp32dev, the default environment)
pio run -t upload --upload-port COM8                     # flash, at 921600 baud
pio device monitor --port COM8                           # serial at 115200, Ctrl+C to quit
pio device monitor -p COM8 -b 115200 -f time -f log2file # the same, with times, also saved to logs/
pio test -e native                                       # Unity tests of lib/tip_core on the host
```

Close the serial monitor before flashing. Only one program can hold the port. Flashing needs no button presses: esptool resets the board into the bootloader through RTS and DTR.

The log file goes into `firmware/logs/`, which is git ignored.

## What a healthy start looks like

The serial monitor shows lines like these. Lines from the three tasks can come in a slightly different order.

```
TiP panda firmware, device demo-panda on tip-integrated-panda.onrender.com
Chip: <model> rev v<major>.<minor>, 2 cores, flash <size> bytes, MAC <mac>
Boot: reset reason power on (1)
Watchdog: 60 s for the net, led and nfc tasks
WiFi: 2 known networks
NFC: tag holds https://tip-integrated-panda.onrender.com/t/demo-panda (51 byte message), PN532 on UART2 GPIO16/17
NFC: PN532 IC 0x32, firmware 1.6, support 0x07 at 115200 baud
NFC: tag ready
WiFi: scan found 9 networks, 1 known
WiFi: joining <your network> (-58 dBm)
WiFi: joined <your network>, IP <address>, RSSI <signal> dBm, sleep off
Time: <UTC time>
WS: connecting to wss://tip-integrated-panda.onrender.com as demo-panda
WS: connected as demo-panda
WS: hello from the server
```

A reset reason other than `power on`, `external pin` or `software restart` points to a crash. Examples are `panic` or `task watchdog`.

The LED shows the state when no reaction is playing:

| State | LED |
|---|---|
| Connecting to WiFi or the server | a short 30 ms flick every second |
| Connected and idle | off |
| Rejected by the server (401) | three quick flicks every 5 seconds |

## Troubleshooting

| Problem | Fix |
|---|---|
| Flashing fails because the port is busy | Close the serial monitor, or any other program that holds the COM port. |
| The first build fails to unpack the framework | Turn on Windows long paths (setup step 3). |
| `pio` fails in Git Bash | Use PowerShell or cmd. |
| Native tests fail with `0xC0000139` | Windows loaded another program's `libstdc++-6.dll`. The tests are linked statically to avoid this (`scripts/static_link.py`). Check that file is in your checkout and run the tests again. |
| Three quick flicks every 5 seconds | The server refused the device (401). Check `TIP_DEVICE_ID` and `TIP_DEVICE_SECRET` in `secrets.h` against the server. The panda tries again every 10 minutes, so a fix on the server needs no reflash. |
| `WiFi: scan found ... none known` | None of the listed networks is in range, or its SSID is hidden or 5 GHz only. Check the SSIDs in `TIP_WIFI_NETWORKS`, capitals included. |
| `WiFi: <ssid> failed after 15 s` | The password is wrong or the network refused the panda. The panda moves on to the next network in the list. |
| `secrets.h has the old TIP_WIFI_SSID ...` when building | Move `secrets.h` to the `TIP_WIFI_NETWORKS` format above. |
| `Time: waiting for SNTP ...` every 5 seconds | The network blocks NTP. Use another network, such as a phone hotspot. |
| `NFC: no PN532 at 115200 or 921600 baud` | Check the DIP switch (0 0), the wiring (SDA to GPIO16, SCL to GPIO17) and the solder joints. |

## How it works

See [DESIGN.md](DESIGN.md): structure, tasks and cores, behaviour, logging, the watchdog, the NFC tag, the 05c spike results, the WebSocket library and TLS.
