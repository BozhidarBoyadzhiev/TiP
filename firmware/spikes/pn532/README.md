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
