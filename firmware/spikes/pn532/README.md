# PN532 spike (brief 05c)

Standalone PlatformIO project that answers one question: should a phone tap on the coin open the tip page through a PN532 emulating a tag, or through a passive NTAG215 sticker? It is not part of the main firmware build and has no secrets.

## Hardware

Classic ESP32 DevKit (ESP-WROOM-32, CP2102) and a PN532 red board with its DIP switch at 0 0 (HSU). PN532 GND to GND, VCC to 3V3, SDA (PN532 TX) to GPIO16 (RX2), SCL (PN532 RX) to GPIO17 (TX2). UART2 at 115200 baud (`-DSPIKE_BAUD=921600` for the fast link). Solder the PN532's 4 pin header well: a cold SDA joint once left the PN532 unable to answer while its power LED stayed on.

## Run

From this folder:

```bash
pio run -e emulate -t upload -t monitor
```

```bash
pio run -e write_tag -t upload -t monitor
```

Both start with the link test. It sends an abort first (the PN532 does not reset with the ESP32 and may still be inside `TgInitAsTarget`), tries 115200 and 921600 in turn (it also keeps its speed), expects `GetFirmwareVersion` `32 01 06 07` (IC 0x32, firmware 1.6), moves the PN532 to the build's speed and sends SAMConfig. Then:

- `emulate`: the PN532 emulates an NFC Forum Type 4 tag (read only) holding one NDEF URI record, `https://tip-integrated-panda.onrender.com/t/demo-panda`. `src/type4_tag.cpp` answers the APDUs; `src/pn532_target.cpp` sends each PN532 command and returns its error code or status byte. After a session it waits 3 s, then until no field for 100 ms (at most 3 s), then arms again; it logs each session with every APDU's timings, the time from session end to armed, and any failure with its code, which is followed by a wake-up and SAMConfig with backoff.
- `write_tag`: hold an NTAG215 on the PN532; it checks the capability container on page 3, writes the same record as an NDEF TLV from page 4, reads every page back and logs `done and verified`.

The PN532 runs in its own task (`nfc`, core 1, priority 1 by default; override with `PLATFORMIO_BUILD_FLAGS="-DSPIKE_NFC_PRIORITY=2"` or `-DSPIKE_NFC_CORE=0`). `loop()` logs `hb <ms since last>` every 100 ms, `GAP <ms>` for anything over 200 ms, and every 10 s the max gap, free heap and the task's free stack.

## Library

[Seeed-Studio/PN532](https://github.com/Seeed-Studio/PN532) v1.1.0 (BSD-3-Clause), pinned to commit `e44820e`. Its `library.json` pulls [don/NDEF](https://github.com/don/NDEF) (BSD) without a version, so `platformio.ini` pins that too; the spike does not use it (`src/ndef.cpp` builds the record).

Two things in the library matter for the main firmware:

- `PN532_HSU::receive` busy waits on `Serial2.read()` without yielding, the whole time the tag is armed. The task never blocks, so it must run below every task it shares a core with (brief 05c step 4 would make it yield; not done).
- `PN532::SAMConfig()` returns false on success over HSU (it checks `readResponse() > 0`, and the reply has no data bytes). The spike sends SAMConfig itself.
- `EmulateTag` (not used any more) re-arms straight after a session and its READ BINARY bounds only the offset, so a reader can read past its buffers; `type4_tag.cpp` bounds offset plus length by the selected file.

## Results, first run with the library's EmulateTag (2026-10-13)

Link test: `32 01 06 07` first time with the wiring above.

| Phone | Method | Worked of 10 | Banner shown | URL correct | Notes |
|---|---|---|---|---|---|
| iPhone | emulation | 2 (taps 8 and 9) | yes | yes | phone almost touching the PN532 |
| Android | emulation | 2 (taps 4 and 5) | yes | yes | phone almost touching the PN532 |
| iPhone | emulation, 3 to 4 mm cardboard | 0 | no | n/a | the PN532 was never activated |
| both | sticker | not tested | | | no NTAG215 on hand |

The serial log matched: 4 sessions, and the failed taps never activated the tag. `TgInitAsTarget` failed 50 times when re-armed, in bursts at 55 to 57 s and 69 to 75 s (right after session 4); the tag cannot be tapped while that happens. Heartbeat max gap 101 ms with the task at priority 1 on core 1; at priority 2 `loop()` got no time at all for 15 s. Free stack in the task stayed at 3728 of 6144 bytes. No power meter, so no current figures.

## Results, brief 05c follow-up (2026-10-13)

Before step 1 was tested, a cold solder joint on the PN532's SDA pin cut the link (no answer at any speed, GPIO16 floating); after it was reworked every build linked first time. The first step 1 round ran before the joint was found and is not counted.

| Step | Change | iPhone | Android | Notes |
|---|---|---|---|---|
| 1 | re-arm after no field for 100 ms, resync on failure | owner: 7 taps, 5 opened (each with two prompts), 2 nothing; log: 6 taps, each read twice about 506 ms apart. An earlier mixed run: 10 reads, failed taps left no trace | owner: 10 of 10; log: 12 complete reads | 0 failures, armed 120 to 282 ms after a session |
| 1 | quiet window 1 s (tried, not kept) | log: 3 taps, one read each | log: 1 | one session ended with status 0x13 and recovered by itself |
| 1 | 3 s hold-off after each session, 100 ms quiet window (kept) | log: 4 taps, one read each; owner: everything works, not counted | log: 1 tap, one read | armed about 3.1 s after a session |
| 2 | mode byte 0x05 | no change needed, `TgInitAsTarget` already used PICC only, passive only | | |
| 3 | 921600 baud | not tap tested | not tap tested | links, configures and arms; default stays 115200 |
| 4 | yield in the UART read | not done | | needs a tap round |
| 5 | READ BINARY bounds | no phone read went past the file (largest: offset 2, 51 bytes) | | host test of the phones' APDU sequences and out of bounds reads, 19 cases pass |
| 6 | taps through 1 to 2 mm of plastic or card | not done | | |

Across every follow-up round: 0 re-arm failures, 0 resyncs, heartbeat max gap 101 ms. APDU times at 115200, from asking `TgSetData` to its answer: 6.3 ms for a status word, 10.3 ms for the 15 byte capability container, 19.6 ms for the 51 byte message. An iPhone read is 7 APDUs in about 140 ms; Android's is 6 APDUs and the session lasts until the phone is pulled away (540 to 870 ms). The iPhone keeps polling for about 2 s after a read.
