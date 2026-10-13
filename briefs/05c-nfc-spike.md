# Brief 05c: NFC spike (PN532 over HSU on the panda ESP32)

## Goal
Find out how a phone tap on the coin should open the tip page. Either the
PN532 emulates an NFC tag holding the tip URL, or we fall back to a passive
NTAG215 sticker. This is a spike: answer the questions below with
evidence, keep the code small, and do not touch the main firmware.

## Hardware (already wired by the owner)
- Board: classic ESP32 DevKit, ESP-WROOM-32, 30 pin, CP2102.
- PN532 red board, DIP switch at 0 0 (HSU, i.e. UART).
- PN532 GND to GND, VCC to 3V3, SDA (PN532 TX) to GPIO16 (RX2),
  SCL (PN532 RX) to GPIO17 (TX2). UART2, 115200 baud.
- If the link test fails, the first thing to try is swapping the two data
  wires (ask the owner; it is harmless).

## Where the code goes
- New standalone PlatformIO project in firmware/spikes/pn532/ with its own
  platformio.ini, using the same platform and Arduino core as the main
  firmware (pioarduino, core 3.3.12).
- The main firmware project, its 44 Unity tests and the CI "firmware" job
  must be unaffected. Check what the CI job builds and confirm the spike
  folder is not picked up by it, or that it builds cleanly if it is.
- Check the main firmware's pin usage and say whether GPIO16 and GPIO17
  are free there. Do not change it.
- Library: prefer one that supports HSU and target mode (the
  elechouse/Seeed PN532 family has PN532_HSU and an NDEF tag emulation
  example). Pin an exact version. Record its licence in the report for the
  third party notices.
- No secrets anywhere. Work on develop, commit locally, never push. Every
  commit must build on its own. Update CODEBASE.md to mention the spike
  folder.

## Steps and what to measure
1. Link test. Read and print the PN532 firmware version (expect IC 0x32,
   version 1.6). Report the raw response.
2. Tag emulation. Emulate an NFC Forum Type 4 tag with one NDEF URI record
   pointing at the real tip page of the demo panda (find the exact path in
   the codebase, https only). Run the PN532 work in its own FreeRTOS task.
   Meanwhile loop() prints a heartbeat every 100 ms. Report any gap over
   200 ms, because the real firmware's WebSocket path must never stall.
   Ask the owner to test with his iPhone and an Android phone, 10 taps
   each, screen on and unlocked:
   - did the system NFC banner or popup appear, and did it open the
     right URL
   - how many of 10 taps worked
   - rough distance that works
   - roughly how long from tap to the banner
   Then repeat the iPhone test through a 3 to 4 mm cardboard layer, which
   matches the panda build.
3. Fallback, only if the owner has an NTAG215 sticker or card. Use the
   PN532 in reader mode to write the same NDEF URI to it, then have the
   owner tap both phones on the sticker alone (PN532 unpowered), 10 taps
   each, and report the same numbers.
4. Power. If the owner has a USB power meter, record current at idle and
   while waiting for a tap. If not, say so and skip it.

## Report (format in CLAUDE.md), plus
- A results table: phone, method (emulation or sticker), taps worked out
  of 10, banner shown, URL correct, notes.
- Heartbeat result: max gap seen.
- Library name, version and licence.
- A recommendation: emulation, sticker, or both (sticker as the primary
  and emulation as an extra), with the reasons.
- Anything that would matter when moving this into the main firmware
  (pins, task priority, core, memory).