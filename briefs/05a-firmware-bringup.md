# Brief 05a: firmware bring up (attended, tonight)

Goal: a PlatformIO firmware project in the repo that builds, flashes to the
ESP32, and proves the four things the real firmware depends on: serial
logging, the LED, WiFi, and a verified TLS connection to production. The
WebSocket protocol and reaction patterns are Brief 05b, not this one.

The owner is here and will do physical steps. Whenever you need him (plug in,
look at the LED, fill in a file, wire something), stop, say exactly what to do,
and wait for him to say done. Save this brief as briefs/05a-firmware-bringup.md.

## Hardware
DollaTek ESP32 DevKit (CP2102 USB to UART, driver installed, shows as COM8),
board on laptop USB. Onboard LED probably on GPIO2. PN532 NFC module, maybe
wired over I2C (SDA GPIO21, SCL GPIO22, 3.3V).

## Steps
1. Toolchain: install PlatformIO Core (install Python with winget first if
   missing). Confirm `pio --version`.
2. Project: create it in firmware/ at the repo root, board esp32dev, Arduino
   framework, serial at 115200. Choose the Arduino ESP32 core version and say
   why in the report (if you use core 3.x, that means the pioarduino
   platform). Nothing in firmware/ may affect the Gradle build.
3. Hello: flash, open the serial monitor, print chip model, revision, cores,
   flash size and MAC. Tell me if the COM port or upload needs anything special
   (for example holding the BOOT button).
4. LED: drive GPIO2 with LEDC PWM, a slow fade then three blinks, and ask the
   owner what he sees. If nothing lights, stop and say so; don't guess pins.
5. WiFi: credentials go in firmware/include/secrets.h, which is git ignored.
   Commit firmware/include/secrets.example.h with placeholders. The owner fills
   in secrets.h himself in his editor, never in chat. Prove the ignore works
   with `git check-ignore`. Connect, print IP and RSSI, and turn WiFi power
   saving off (WiFi.setSleep(false)) since the device must stay responsive.
   Never print the password.
6. TLS: HTTPS GET https://tip-integrated-panda.onrender.com/actuator/health
   with full certificate verification against a CA bundle (the ESP32 Mozilla
   CA bundle, or the bundle mechanism your chosen core supports). Never
   setInsecure, never skip hostname checks. Print status and body. Then prove
   verification is real: the same code must fail against
   https://expired.badssl.com and https://wrong.host.badssl.com. Explain in
   the report how the bundle is built into the firmware and how it gets
   updated.
7. NFC, only if the owner says the PN532 is wired: I2C scan (expect 0x24),
   read the PN532 firmware version, then print a tag's UID when he taps the
   coin. Read only. Don't write anything to the tag tonight.

## Done when
- firmware/ builds with `pio run` and flashes with `pio run -t upload`.
- Steps 3 to 6 pass, and 7 if wired.
- firmware/README.md says how to build, flash, monitor and set up secrets.h.
- CODEBASE.md mentions firmware/.
- Committed locally on develop, not pushed. Gradle build only if you touched
  anything outside firmware/, briefs/ and docs.

## Report
The usual format, plus: core version and why, LED pin result, upload quirks,
the TLS result and the two badssl failures, how the CA bundle works, NFC
result or "not wired", and anything about the hardware that Brief 05b should
know.
