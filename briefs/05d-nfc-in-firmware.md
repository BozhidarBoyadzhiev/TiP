# Brief 05d: NFC tag emulation inside the main firmware

## Goal
The panda's ESP32 runs one firmware that does both jobs: the existing
WebSocket device client and light, plus PN532 Type 4 tag emulation of the
panda's tip URL. Base it on the 05c spike (firmware/spikes/pn532/), which
stays in the repo as evidence and is not built by CI.

## Design
- Module: move the spike's own Type 4 tag and PN532 command layer into the
  main firmware as its own module. Drop the Seeed PN532 library if the HSU
  transport is small enough to own; if you keep it, pin it and record its
  licence for the third party notices. Keep the baud detection (try 115200
  and 921600, then set the build's speed). Default 115200.
- Concurrency: NFC task on core 1, priority 1, below net (2) and led (3).
  Every UART wait must yield (vTaskDelay(1) or a blocking read with
  timeout). No busy waiting anywhere. NFC shares no state with the device
  protocol: a tap never touches hello, ack, replay or the send path.
- Failure isolation: if the PN532 is missing at boot or stops answering,
  log it and retry with backoff (up to every 30 s). The panda must keep
  connecting, acking and playing reactions as normal without NFC.
- URL: build it from the firmware's existing config (server base URL plus
  device slug, same path the tip page uses). https only, length checked
  against the NDEF buffer at build or boot, tag read only.
- Keep the spike's behaviour: re-arm after 3 s plus 100 ms of no field,
  bounded READ BINARY (15 and 128 byte files, 6B00 past the end or Le 0),
  wake-up plus SAMConfig resync with backoff on errors.
- Pins: UART2, GPIO16 RX, GPIO17 TX. LED stays on GPIO2.

## Tests
- Commit the 19 host cases from 05c as Unity native tests (tag APDU
  handling, bounds, URL record building), plus tests for the URL builder
  (https only, too long rejected). All existing 44 native tests still pass.
- CI "firmware" job green. ./gradlew build green.

## Hardware checks (ask the owner)
1. Boot with the PN532 unplugged: panda connects and reacts to a tip
   normally, log shows NFC retries.
2. Boot with it plugged in: counted 10 iPhone taps and 10 Android taps,
   pulling away 10 cm between taps.
3. 10 iPhone taps through 1 to 2 mm of plastic or card.
4. Send a tip and tap during the reaction; tap and then tip straight away.
   The reaction must play in full and the tap must open the page.
5. Heartbeat or timing evidence that WebSocket acks are not delayed.

## Docs
CODEBASE.md and the firmware README: NFC module, wiring, task layout,
failure behaviour. Report in the CLAUDE.md format with a results table.