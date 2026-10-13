// Brief 05c spike: PN532 over HSU on UART2. The PN532 runs in its own FreeRTOS task; loop() prints
// a heartbeat every 100 ms and flags any gap over 200 ms. Build env picks the mode:
// emulate (Type 4 tag holding the tip URL) or write_tag (write the same URI to an NTAG215).
#include <Arduino.h>
#include <PN532.h>
#include <PN532_HSU.h>
#include <emulatetag.h>
#include <stdarg.h>

#include "ndef.h"

#ifndef SPIKE_NFC_CORE
#define SPIKE_NFC_CORE 1
#endif
#ifndef SPIKE_NFC_PRIORITY
#define SPIKE_NFC_PRIORITY 1
#endif

namespace {

constexpr int8_t kPn532RxPin = 16;  // PN532 TX (labelled SDA)
constexpr int8_t kPn532TxPin = 17;  // PN532 RX (labelled SCL)
constexpr uint32_t kNfcStackBytes = 6144;
constexpr uint32_t kHeartbeatMs = 100;
constexpr uint32_t kGapAlarmMs = 200;
constexpr uint32_t kStatsEveryMs = 10000;
constexpr uint32_t kLinkRetryMs = 2000;
constexpr uint16_t kSamConfigReplyMs = 1000;
constexpr size_t kMessageCapacity = 120;

SemaphoreHandle_t logMutex;
TaskHandle_t nfcTask;
uint8_t message[kMessageCapacity];
size_t messageLength;

PN532_HSU hsu(Serial2, kPn532RxPin, kPn532TxPin);

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

// PN532::SAMConfig() reports false on success: the reply has no data bytes and it checks
// readResponse() > 0. Same command (normal mode, 1 s timeout, IRQ on), checked for >= 0.
bool samConfig() {
  const uint8_t command[] = {PN532_COMMAND_SAMCONFIGURATION, 0x01, 0x14, 0x01};
  uint8_t reply[8];
  return hsu.writeCommand(command, sizeof(command)) == 0 &&
         hsu.readResponse(reply, sizeof(reply), kSamConfigReplyMs) >= 0;
}

// Step 1. Expect IC 0x32, version 1.6. Retries until the PN532 answers, then runs SAMConfig.
// The only begin() (opens UART2, wakes the PN532); EmulateTag::init() would run a second one.
void linkTest(PN532 &nfc) {
  nfc.begin();
  for (;;) {
    uint32_t version = nfc.getFirmwareVersion();
    if (version != 0) {
      uint8_t length = 0;
      logHex("Link: GetFirmwareVersion raw response data", nfc.getBuffer(&length), 4);
      logLine("Link: IC 0x%02X, firmware %u.%u, support 0x%02X",
              static_cast<unsigned>(version >> 24), static_cast<unsigned>((version >> 16) & 0xFF),
              static_cast<unsigned>((version >> 8) & 0xFF), static_cast<unsigned>(version & 0xFF));
      if (!samConfig()) {
        logLine("Link: SAMConfig failed");
      }
      return;
    }
    logLine("Link: no answer from the PN532 on GPIO16/17; check power, DIP 0 0, or swap the data wires");
    vTaskDelay(pdMS_TO_TICKS(kLinkRetryMs));
  }
}

#if defined(SPIKE_MODE_EMULATE)

void runNfc() {
  PN532 nfc(hsu);
  linkTest(nfc);
  EmulateTag tag(hsu);
  tag.setNdefFile(message, static_cast<int16_t>(messageLength));
  tag.setTagWriteable(false);
  logLine("Emulate: Type 4 tag ready, %s", spike::kTipUrl);
  uint32_t sessions = 0;
  for (;;) {
    uint32_t armedAt = millis();
    // Blocks until a reader activates the tag and the session ends (the phone leaves).
    if (tag.emulate(0)) {
      sessions++;
      logLine("Emulate: session %lu ended, armed for %lu ms", static_cast<unsigned long>(sessions),
              static_cast<unsigned long>(millis() - armedAt));
    } else {
      logLine("Emulate: TgInitAsTarget failed, re-arming");
      vTaskDelay(pdMS_TO_TICKS(100));
    }
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
