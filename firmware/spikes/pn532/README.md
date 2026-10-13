# PN532 spike (brief 05c)

Standalone PlatformIO project that answers one question: should a phone tap on the coin open the tip page through a PN532 emulating a tag, or through a passive NTAG215 sticker? It is not part of the main firmware build and has no secrets.

## Hardware

Classic ESP32 DevKit (ESP-WROOM-32, CP2102) and a PN532 red board with its DIP switch at 0 0 (HSU). PN532 GND to GND, VCC to 3V3, SDA (PN532 TX) to GPIO16 (RX2), SCL (PN532 RX) to GPIO17 (TX2). UART2 at 115200 baud.

## Run

From this folder:

```bash
pio run -e emulate -t upload -t monitor
```

```bash
pio run -e write_tag -t upload -t monitor
```

Both start with the link test (`GetFirmwareVersion`, expect `32 01 06 07`: IC 0x32, firmware 1.6). Then:

- `emulate`: the PN532 emulates an NFC Forum Type 4 tag (read only) holding one NDEF URI record, `https://tip-integrated-panda.onrender.com/t/demo-panda`. Each finished phone session logs `Emulate: session N ended`.
- `write_tag`: hold an NTAG215 on the PN532; it checks the capability container on page 3, writes the same record as an NDEF TLV from page 4, reads every page back and logs `done and verified`.

The PN532 runs in its own task (`nfc`, core 1, priority 1 by default; override with `PLATFORMIO_BUILD_FLAGS="-DSPIKE_NFC_PRIORITY=2"` or `-DSPIKE_NFC_CORE=0`). `loop()` logs `hb <ms since last>` every 100 ms, `GAP <ms>` for anything over 200 ms, and every 10 s the max gap, free heap and the task's free stack.

## Library

[Seeed-Studio/PN532](https://github.com/Seeed-Studio/PN532) v1.1.0 (BSD-3-Clause), pinned to commit `e44820e`. Its `library.json` pulls [don/NDEF](https://github.com/don/NDEF) (BSD) without a version, so `platformio.ini` pins that too; the spike does not use it (`src/ndef.cpp` builds the record).

Two things in the library matter for the main firmware:

- `PN532_HSU::receive` busy waits on `Serial2.read()` without yielding, for the whole of `TgInitAsTarget` (forever with `emulate(0)`). The task never blocks, so it must run below every task it shares a core with.
- `PN532::SAMConfig()` returns false on success over HSU (it checks `readResponse() > 0`, and the reply has no data bytes). The spike sends SAMConfig itself.

## Results (2026-10-13)

Link test: `32 01 06 07` first time with the wiring above.

| Phone | Method | Worked of 10 | Banner shown | URL correct | Notes |
|---|---|---|---|---|---|
| iPhone | emulation | 2 (taps 8 and 9) | yes | yes | phone almost touching the PN532 |
| Android | emulation | 2 (taps 4 and 5) | yes | yes | phone almost touching the PN532 |
| iPhone | emulation, 3 to 4 mm cardboard | 0 | no | n/a | the PN532 was never activated |
| both | sticker | not tested | | | no NTAG215 on hand |

The serial log matched: 4 sessions, and the failed taps never activated the tag. `TgInitAsTarget` failed 50 times when re-armed, in bursts at 55 to 57 s and 69 to 75 s (right after session 4); the tag cannot be tapped while that happens. Heartbeat max gap 101 ms with the task at priority 1 on core 1; at priority 2 `loop()` got no time at all for 15 s. Free stack in the task stayed at 3728 of 6144 bytes. No power meter, so no current figures.
