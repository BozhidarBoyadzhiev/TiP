# TiP firmware

PlatformIO project for the physical panda: an ESP32 DevKit (esp32dev, CP2102 USB to UART) with the Arduino framework. Arduino ESP32 core 3.3.12 (ESP-IDF 5.5.5) through the pioarduino platform, pinned to release `55.03.312-1` in `platformio.ini`.

Today it is the bring up sketch from Brief 05a: it prints the chip details, fades and blinks the onboard LED (GPIO2, LEDC PWM), joins WiFi with power saving off, syncs the clock over SNTP and makes three HTTPS requests with the certificate checked against the CA bundle: production health (must answer 200) and two badssl.com hosts (must fail).

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

The first build downloads the platform and toolchain (about 1 GB) and takes several minutes.

## WiFi secrets

```powershell
Copy-Item include\secrets.example.h include\secrets.h
```

Edit `include/secrets.h` with the network name and password. It is git ignored (`git check-ignore -v firmware/include/secrets.h` shows the rule). Never commit it or paste it anywhere. The ESP32 joins 2.4 GHz networks only. The build stops with a message when the file is missing.

## Build, flash, monitor

From `firmware/`:

```powershell
pio run                                  # build
pio run -t upload --upload-port COM8     # flash (921600 baud)
pio device monitor --port COM8           # serial at 115200, Ctrl+C to quit
```

Uploading needs no button presses: esptool resets the board into the bootloader through RTS and DTR. Close the serial monitor before uploading, since only one program can hold the port.

## TLS and the CA bundle

`NetworkClientSecure::useBuiltinCACertBundle()` attaches ESP-IDF's certificate bundle (`esp_crt_bundle`). The precompiled ESP-IDF libraries that ship with the core are built with `CONFIG_MBEDTLS_CERTIFICATE_BUNDLE_DEFAULT_FULL`, so the full Mozilla root store, as packaged in that ESP-IDF release, is linked into the firmware from `libmbedtls.a`. The bundle has no file in this repo. Verification is required (`MBEDTLS_SSL_VERIFY_REQUIRED`) and the hostname is always checked. `setInsecure()` is never used.

The bundle changes only with the core: to pick up new roots, move `platform` in `platformio.ini` to a newer pioarduino release and rebuild.

Certificate dates are not checked today: the precompiled libraries are built without `CONFIG_MBEDTLS_HAVE_TIME_DATE`, so mbedtls skips the validity period check. See the Brief 05a report.
