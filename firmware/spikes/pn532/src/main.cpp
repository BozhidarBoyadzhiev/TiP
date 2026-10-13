// Brief 05c spike: PN532 over HSU on UART2. The PN532 runs in its own FreeRTOS task; loop() prints
// a heartbeat every 100 ms and flags any gap over 200 ms. Build env picks the mode:
// emulate (Type 4 tag holding the tip URL) or write_tag (write the same URI to an NTAG215).
#include <Arduino.h>
#include <PN532.h>
#include <PN532_HSU.h>
#include <stdarg.h>

#include "ndef.h"
#include "pn532_target.h"
#include "type4_tag.h"

#ifndef SPIKE_NFC_CORE
#define SPIKE_NFC_CORE 1
#endif
#ifndef SPIKE_NFC_PRIORITY
#define SPIKE_NFC_PRIORITY 1
#endif
// UART2 speed to run the PN532 at: 115200 (its power-on speed, the one phones were tested with)
// or 921600 (links and arms; not tap tested). pio run -e emulate with -DSPIKE_BAUD=921600.
#ifndef SPIKE_BAUD
#define SPIKE_BAUD 115200
#endif

namespace {

constexpr int8_t kPn532RxPin = 16;  // PN532 TX (labelled SDA)
constexpr int8_t kPn532TxPin = 17;  // PN532 RX (labelled SCL)
constexpr uint32_t kNfcStackBytes = 6144;
constexpr size_t kLogTxBufferBytes = 4096;
constexpr uint32_t kHeartbeatMs = 100;
constexpr uint32_t kGapAlarmMs = 200;
constexpr uint32_t kStatsEveryMs = 10000;
constexpr uint32_t kLinkRetryMs = 2000;
constexpr uint32_t kAbortSettleMs = 10;
constexpr uint32_t kPowerOnBaud = 115200;
constexpr uint32_t kFastBaud = 921600;
constexpr uint32_t kRunBaud = SPIKE_BAUD;
static_assert(kRunBaud == kPowerOnBaud || kRunBaud == kFastBaud, "SPIKE_BAUD is 115200 or 921600");
constexpr uint8_t kRunBaudCode = kRunBaud == kFastBaud ? 0x07 : 0x04;
constexpr size_t kMessageCapacity = 120;

SemaphoreHandle_t logMutex;
TaskHandle_t nfcTask;
uint8_t message[kMessageCapacity];
size_t messageLength;

PN532_HSU hsu(Serial2, kPn532RxPin, kPn532TxPin);
spike::Pn532Target target(hsu, Serial2);

void logLine(const char *format, ...) __attribute__((format(printf, 1, 2)));

void logLine(const char *format, ...) {
  char line[256];
  va_list args;
  va_start(args, format);
  vsnprintf(line, sizeof(line), format, args);
  va_end(args);
  xSemaphoreTake(logMutex, portMAX_DELAY);
  Serial.printf("%8lu %s\n", static_cast<unsigned long>(millis()), line);
  xSemaphoreGive(logMutex);
}

void logHex(const char *label, const uint8_t *bytes, size_t length) {
  char hex[3 * 64 + 1] = {0};
  for (size_t i = 0; i < length && i < 64; i++) {
    snprintf(hex + 3 * i, 4, "%02X ", bytes[i]);
  }
  logLine("%s (%u bytes): %s", label, static_cast<unsigned>(length), hex);
}

// Step 1. Expect IC 0x32, version 1.6. Retries until the PN532 answers, then runs SAMConfig.
// PN532::SAMConfig() reports false on success (its reply has no data bytes and it checks
// readResponse() > 0), so the target helper sends it.
// The PN532 keeps running when only the ESP32 resets, possibly still inside TgInitAsTarget and
// still at the speed an earlier firmware set, so each attempt starts with an abort and attempts
// alternate between 115200 and 921600. Once linked, it moves the PN532 to kRunBaud.
void linkTest(PN532 &nfc) {
  nfc.begin();
  uint32_t baud = kPowerOnBaud;
  for (;;) {
    target.abort();
    vTaskDelay(pdMS_TO_TICKS(kAbortSettleMs));
    uint8_t version[4];
    int16_t result = target.firmwareVersion(version);
    if (result == 0) {
      logHex("Link: GetFirmwareVersion raw response data", version, sizeof(version));
      logLine("Link: IC 0x%02X, firmware %u.%u, support 0x%02X at %lu baud", version[0], version[1],
              version[2], version[3], static_cast<unsigned long>(baud));
      if (baud != kRunBaud) {
        result = target.setSerialBaudRate(kRunBaudCode, kRunBaud);
        logLine("Link: SetSerialBaudRate %lu %s (%d)", static_cast<unsigned long>(kRunBaud),
                result == 0 ? "ok" : "failed", result);
      }
      result = target.samConfig();
      if (result != 0) {
        logLine("Link: SAMConfig failed (%d)", result);
      }
      return;
    }
    logLine("Link: no answer from the PN532 on GPIO16/17 at %lu baud (%d); check power, DIP 0 0, or swap the data wires",
            static_cast<unsigned long>(baud), result);
    baud = baud == kPowerOnBaud ? kFastBaud : kPowerOnBaud;
    Serial2.updateBaudRate(baud);
    vTaskDelay(pdMS_TO_TICKS(kLinkRetryMs));
  }
}

#if defined(SPIKE_MODE_EMULATE)

constexpr uint16_t kActivationWaitMs = 1000;
constexpr uint16_t kApduWaitMs = 2000;
constexpr uint16_t kReplyMs = 1000;
constexpr uint32_t kFieldPollMs = 20;
constexpr uint32_t kFieldQuietMs = 100;
// An iPhone left on the coil switches its field off for longer than kFieldQuietMs between polls
// and read the tag again about 500 ms after the first read, so it showed a second prompt. Real
// taps are 20 s or more apart, so the tag stays off this long after every session.
constexpr uint32_t kRearmHoldOffMs = 3000;
constexpr uint32_t kFieldWaitCapMs = 3000;
constexpr uint32_t kFirstBackoffMs = 20;
constexpr uint32_t kMaxBackoffMs = 640;
constexpr uint16_t kCiuStatus1 = 0x6337;
constexpr uint8_t kCiuRfOn = 0x04;

// Wake-up and SAMConfig until the PN532 answers, backing off from 20 ms doubling to 640 ms.
void resync(const char *after, int16_t error) {
  logLine("Fail: %s (%d), resyncing", after, error);
  uint32_t backoff = kFirstBackoffMs;
  for (uint32_t attempt = 1;; attempt++) {
    vTaskDelay(pdMS_TO_TICKS(backoff));
    int16_t result = target.resync();
    if (result == 0) {
      logLine("Resync: ok on attempt %lu", static_cast<unsigned long>(attempt));
      return;
    }
    logLine("Resync: attempt %lu failed (%d)", static_cast<unsigned long>(attempt), result);
    backoff = min(backoff * 2, kMaxBackoffMs);
  }
}

// After a session, waits until the phone has left: no field for kFieldQuietMs, at most
// kFieldWaitCapMs. Logs both field signals so the tap tests show which one is trustworthy.
void waitForFieldToLeave() {
  uint32_t start = millis();
  uint32_t quietSince = 0;
  int16_t lastField = -1;
  int16_t lastRfOn = -1;
  uint32_t fieldChanges = 0;
  uint32_t rfOnChanges = 0;
  for (;;) {
    int16_t field = target.fieldPresent();
    int16_t ciu = target.readRegister(kCiuStatus1);
    uint32_t now = millis();
    if (field < 0 || ciu < 0) {
      resync("field check", field < 0 ? field : ciu);
      return;
    }
    int16_t rfOn = (ciu & kCiuRfOn) != 0 ? 1 : 0;
    fieldChanges += lastField >= 0 && field != lastField;
    rfOnChanges += lastRfOn >= 0 && rfOn != lastRfOn;
    lastField = field;
    lastRfOn = rfOn;
    if (field == 0) {
      quietSince = quietSince == 0 ? now : quietSince;
      if (now - quietSince >= kFieldQuietMs) {
        logLine("Field: gone after %lu ms (status field changes %lu, CIU RFOn changes %lu, last CIU 0x%02X)",
                static_cast<unsigned long>(now - start), static_cast<unsigned long>(fieldChanges),
                static_cast<unsigned long>(rfOnChanges), ciu);
        return;
      }
    } else {
      quietSince = 0;
    }
    if (now - start >= kFieldWaitCapMs) {
      logLine("Field: still present after %lu ms, arming anyway (changes %lu, CIU RFOn changes %lu)",
              static_cast<unsigned long>(now - start), static_cast<unsigned long>(fieldChanges),
              static_cast<unsigned long>(rfOnChanges));
      return;
    }
    vTaskDelay(pdMS_TO_TICKS(kFieldPollMs));
  }
}

void armUntilAcknowledged() {
  for (;;) {
    int16_t result = target.arm();
    if (result == 0) {
      return;
    }
    resync("TgInitAsTarget", result);
  }
}

// One APDU's timings in microseconds: getUs from asking TgGetData to having the command (the
// phone's own time plus both UART frames), setUs from there to TgSetData's answer (our reply
// and both frames).
struct ApduTiming {
  uint32_t startUs;
  uint32_t getUs;
  uint32_t setUs;
  uint8_t ins;
  uint16_t p1p2;
  uint8_t le;
  uint8_t replyLength;
  uint16_t sw;
};

constexpr size_t kTimedApdus = 16;
ApduTiming timings[kTimedApdus];

void logTimings(uint32_t apdus, uint32_t activatedUs) {
  for (uint32_t i = 0; i < apdus && i < kTimedApdus; i++) {
    const ApduTiming &t = timings[i];
    logLine("  APDU %lu at +%lu us: INS %02X P1P2 %04X Le/Lc %u -> %u bytes SW %04X, get %lu us, set %lu us",
            static_cast<unsigned long>(i + 1), static_cast<unsigned long>(t.startUs - activatedUs), t.ins,
            t.p1p2, t.le, t.replyLength, t.sw, static_cast<unsigned long>(t.getUs),
            static_cast<unsigned long>(t.setUs));
  }
}

// Answers APDUs until the phone releases the tag. Returns how it ended for the log.
const char *runSession(spike::Type4Tag &tag, uint32_t &apdus, int16_t &code) {
  uint8_t command[255];
  uint8_t reply[spike::Type4Tag::kMaxReply];
  for (;;) {
    uint8_t status = 0;
    uint32_t startUs = micros();
    int16_t length = target.getData(command, sizeof(command), kApduWaitMs, status);
    uint32_t gotUs = micros();
    if (length < 0) {
      code = length;
      return "TgGetData transport";
    }
    if (status != 0) {
      code = status;
      return "TgGetData status";
    }
    size_t replyLength = tag.respond(command, length, reply);
    int16_t result = target.setData(reply, static_cast<uint8_t>(replyLength), kReplyMs, status);
    if (apdus < kTimedApdus) {
      timings[apdus] = {startUs,
                        gotUs - startUs,
                        micros() - gotUs,
                        length > 1 ? command[1] : uint8_t{0},
                        length > 3 ? static_cast<uint16_t>((command[2] << 8) | command[3]) : uint16_t{0},
                        length > 4 ? command[4] : uint8_t{0},
                        static_cast<uint8_t>(replyLength),
                        static_cast<uint16_t>((reply[replyLength - 2] << 8) | reply[replyLength - 1])};
    }
    apdus++;
    if (result < 0) {
      code = result;
      return "TgSetData transport";
    }
    if (status != 0) {
      code = status;
      return "TgSetData status";
    }
  }
}

void runNfc() {
  PN532 nfc(hsu);
  linkTest(nfc);
  spike::Type4Tag tag;
  tag.setNdef(message, messageLength);
  logLine("Emulate: Type 4 tag, %s", spike::kTipUrl);
  uint32_t sessions = 0;
  uint32_t sessionEndedAt = 0;
  for (;;) {
    armUntilAcknowledged();
    if (sessionEndedAt == 0) {
      logLine("Arm: armed");
    } else {
      logLine("Arm: armed %lu ms after session %lu ended",
              static_cast<unsigned long>(millis() - sessionEndedAt), static_cast<unsigned long>(sessions));
    }
    int16_t activation;
    while ((activation = target.waitActivation(kActivationWaitMs)) == PN532_TIMEOUT) {
    }
    if (activation < 0) {
      resync("TgInitAsTarget response", activation);
      continue;
    }
    uint32_t activatedAt = millis();
    uint32_t activatedUs = micros();
    sessions++;
    tag.startSession();
    uint32_t apdus = 0;
    int16_t code = 0;
    const char *ending = runSession(tag, apdus, code);
    sessionEndedAt = millis();
    // A status is the PN532's byte (0x29: released by the phone); a transport error is negative.
    logLine("Session %lu: %lu APDUs in %lu ms, ended by %s %d (0x%02X)",
            static_cast<unsigned long>(sessions), static_cast<unsigned long>(apdus),
            static_cast<unsigned long>(sessionEndedAt - activatedAt), ending, code,
            static_cast<unsigned>(code & 0xFF));
    logTimings(apdus, activatedUs);
    if (code < 0) {
      target.abort();
      resync(ending, code);
    }
    vTaskDelay(pdMS_TO_TICKS(kRearmHoldOffMs));
    waitForFieldToLeave();
  }
}

#elif defined(SPIKE_MODE_WRITE_TAG)

constexpr uint8_t kFirstDataPage = 4;
constexpr uint8_t kCapabilityPage = 3;
constexpr uint8_t kNdefMagic = 0xE1;
constexpr uint8_t kNtag215DataSize = 0x3E;  // 0x3E * 8 = 496 bytes
constexpr uint8_t kNdefTlv = 0x03;
constexpr uint8_t kTerminatorTlv = 0xFE;
constexpr uint16_t kPollMs = 1000;

bool writeAndVerify(PN532 &nfc, const uint8_t *tlv, size_t tlvLength) {
  for (size_t offset = 0; offset < tlvLength; offset += 4) {
    uint8_t page[4] = {0};
    memcpy(page, tlv + offset, min(static_cast<size_t>(4), tlvLength - offset));
    uint8_t pageNumber = kFirstDataPage + offset / 4;
    if (!nfc.mifareultralight_WritePage(pageNumber, page)) {
      logLine("Write: page %u write failed", pageNumber);
      return false;
    }
    uint8_t readBack[4];
    if (!nfc.mifareultralight_ReadPage(pageNumber, readBack) || memcmp(page, readBack, 4) != 0) {
      logLine("Write: page %u read back differs", pageNumber);
      return false;
    }
  }
  return true;
}

void runNfc() {
  PN532 nfc(hsu);
  linkTest(nfc);
  uint8_t tlv[kMessageCapacity + 3];
  tlv[0] = kNdefTlv;
  tlv[1] = static_cast<uint8_t>(messageLength);
  memcpy(tlv + 2, message, messageLength);
  tlv[2 + messageLength] = kTerminatorTlv;
  size_t tlvLength = messageLength + 3;
  logLine("Write: hold an NTAG215 on the PN532 to write %s", spike::kTipUrl);
  for (;;) {
    uint8_t uid[7];
    uint8_t uidLength = 0;
    if (!nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, uid, &uidLength, kPollMs)) {
      continue;
    }
    logHex("Write: tag UID", uid, uidLength);
    uint8_t capability[4];
    if (!nfc.mifareultralight_ReadPage(kCapabilityPage, capability)) {
      logLine("Write: cannot read page 3; not a Type 2 tag?");
    } else if (capability[0] != kNdefMagic || capability[2] != kNtag215DataSize) {
      logHex("Write: page 3 is not an NTAG215 NDEF capability container", capability, 4);
    } else if (writeAndVerify(nfc, tlv, tlvLength)) {
      logLine("Write: done and verified, %u bytes; remove the tag", static_cast<unsigned>(tlvLength));
    }
    while (nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, uid, &uidLength, kPollMs)) {
      vTaskDelay(pdMS_TO_TICKS(200));
    }
    logLine("Write: tag removed, ready for the next one");
  }
}

#else
#error "Build with -DSPIKE_MODE_EMULATE or -DSPIKE_MODE_WRITE_TAG (pio run -e emulate|write_tag)"
#endif

void nfcLoop(void *) {
  runNfc();
}

}  // namespace

void setup() {
  // A large transmit buffer: a log line costs the NFC task microseconds, not milliseconds.
  Serial.setTxBufferSize(kLogTxBufferBytes);
  Serial.begin(115200);
  logMutex = xSemaphoreCreateMutex();
  delay(500);
  messageLength = spike::buildUriMessage(spike::kTipUrl, message, sizeof(message));
  logLine("PN532 spike, NFC task on core %d at priority %d, loop on core %d at priority %u",
          SPIKE_NFC_CORE, SPIKE_NFC_PRIORITY, xPortGetCoreID(), uxTaskPriorityGet(nullptr));
  logHex("NDEF message", message, messageLength);
  xTaskCreatePinnedToCore(nfcLoop, "nfc", kNfcStackBytes, nullptr, SPIKE_NFC_PRIORITY, &nfcTask,
                          SPIKE_NFC_CORE);
}

void loop() {
  static uint32_t last = millis();
  static uint32_t maxGap = 0;
  static uint32_t lastStats = millis();
  uint32_t now = millis();
  uint32_t gap = now - last;
  last = now;
  if (gap > maxGap) {
    maxGap = gap;
  }
  if (gap > kGapAlarmMs) {
    logLine("GAP %lu ms", static_cast<unsigned long>(gap));
  }
  logLine("hb %lu", static_cast<unsigned long>(gap));
  if (now - lastStats >= kStatsEveryMs) {
    lastStats = now;
    logLine("stats: max gap %lu ms, free heap %lu, nfc stack free %u bytes",
            static_cast<unsigned long>(maxGap), static_cast<unsigned long>(ESP.getFreeHeap()),
            static_cast<unsigned>(uxTaskGetStackHighWaterMark(nfcTask)));
  }
  delay(kHeartbeatMs);
}
