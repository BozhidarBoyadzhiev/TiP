#include "log.h"
#include "pn532.h"
#include "tasks.h"
#include "tip_alive_check.h"
#include "tip_config.h"
#include "tip_ndef.h"
#include "tip_type4_tag.h"

namespace tip {

namespace {

// Core 1, below the network (2) and playback (3) tasks, so it only runs when both wait. Every
// wait for the PN532 yields, and nothing here is shared with the device channel.
constexpr BaseType_t kCore = 1;
constexpr UBaseType_t kPriority = 1;
constexpr uint32_t kStackBytes = 6144;

constexpr int8_t kRxPin = 16;  // PN532 TX (labelled SDA)
constexpr int8_t kTxPin = 17;  // PN532 RX (labelled SCL)
constexpr uint32_t kPowerOnBaud = 115200;
constexpr uint32_t kFastBaud = 921600;
constexpr uint32_t kRunBaud = TIP_NFC_BAUD;
static_assert(kRunBaud == kPowerOnBaud || kRunBaud == kFastBaud, "TIP_NFC_BAUD is 115200 or 921600");
constexpr uint8_t kRunBaudCode = kRunBaud == kFastBaud ? 0x07 : 0x04;
constexpr uint32_t kAbortSettleMs = 10;

constexpr uint32_t kActivationWaitMs = 1000;
constexpr uint32_t kApduWaitMs = 2000;
constexpr uint32_t kReplyMs = 1000;
constexpr uint32_t kFieldPollMs = 20;

constexpr size_t kMaxTipUrl = maxUriLength(Type4Tag::kMaxMessage);
static_assert(sizeof(TIP_PUBLIC_BASE_URL) - 1 + sizeof("/t/") - 1 + sizeof(TIP_DEVICE_SLUG) - 1 <= kMaxTipUrl,
              "the tip URL does not fit the NFC tag's NDEF file");

Pn532 pn532(Serial2, kRxPin, kTxPin);
Type4Tag tag;
uint8_t command[pn532::kMaxFrameData];
uint8_t reply[Type4Tag::kMaxReply];
// When a phone's field was last seen: at a tap and while waiting for it to leave. The tag cannot
// see a field while armed, so the alive check's timer runs across taps and re-arms: one that falls
// due during a tap meets the field seen just before the tag was armed again.
bool fieldSeen = false;
uint32_t lastFieldAt = 0;
uint32_t aliveTimerStartedAt = 0;

void sawField() {
  fieldSeen = true;
  lastFieldAt = millis();
}

// Wake-up and SAMConfig, backing off from kNfcResyncFirstMs. False when the PN532 still does not
// answer, which sends the task back to looking for it.
bool resync(const char *after, int16_t error) {
  logLine("NFC: %s failed (%d), resyncing", after, error);
  uint32_t backoff = kNfcResyncFirstMs;
  for (uint32_t attempt = 1; attempt <= kNfcResyncAttempts; attempt++) {
    vTaskDelay(pdMS_TO_TICKS(backoff));
    int16_t result = pn532.resync();
    if (result == 0) {
      logLine("NFC: resync ok on attempt %lu", static_cast<unsigned long>(attempt));
      return true;
    }
    error = result;
    backoff *= 2;
  }
  logLine("NFC: resync failed %lu times (%d)", static_cast<unsigned long>(kNfcResyncAttempts), error);
  return false;
}

// Finds the PN532 at the build's speed or the other one (it keeps its speed when only the ESP32
// resets, and may still be inside TgInitAsTarget, hence the abort), moves it to the build's speed
// and configures it. 0, or the last failure.
int16_t link() {
  const uint32_t speeds[] = {kRunBaud, kRunBaud == kPowerOnBaud ? kFastBaud : kPowerOnBaud};
  int16_t result = 0;
  for (uint32_t baud : speeds) {
    pn532.setBaud(baud);
    pn532.abort();
    vTaskDelay(pdMS_TO_TICKS(kAbortSettleMs));
    pn532.wakeup();
    uint8_t version[4];
    result = pn532.firmwareVersion(version);
    if (result != 0) {
      continue;
    }
    logLine("NFC: PN532 IC 0x%02X, firmware %u.%u, support 0x%02X at %lu baud", version[0], version[1],
            version[2], version[3], static_cast<unsigned long>(baud));
    if (baud != kRunBaud) {
      result = pn532.setSerialBaudRate(kRunBaudCode, kRunBaud);
      if (result != 0) {
        logLine("NFC: SetSerialBaudRate %lu failed (%d)", static_cast<unsigned long>(kRunBaud), result);
        return result;
      }
      logLine("NFC: PN532 moved to %lu baud", static_cast<unsigned long>(kRunBaud));
    }
    result = pn532.samConfig();
    if (result != 0) {
      logLine("NFC: SAMConfig failed (%d)", result);
    }
    return result;
  }
  return result;
}

enum class Armed : uint8_t { kPhone, kArmAgain, kLost };

// Waits for a phone to activate the armed tag. When aliveCheck says so it stops the tag and asks
// the PN532 for its firmware version, so one that was unplugged or reset is noticed; the tag is
// then armed again.
Armed waitForPhone() {
  for (;;) {
    int16_t result = pn532.waitActivation(kActivationWaitMs);
    if (result >= 0) {
      sawField();
      return Armed::kPhone;
    }
    if (result != pn532::kTimeout) {
      return resync("TgInitAsTarget response", result) ? Armed::kArmAgain : Armed::kLost;
    }
    AliveCheck due = aliveCheck(millis(), aliveTimerStartedAt, fieldSeen, lastFieldAt, kNfcAliveCheckMs,
                                kNfcAliveSkipAfterFieldMs);
    if (due == AliveCheck::kRestartTimer) {
      aliveTimerStartedAt = millis();
    } else if (due == AliveCheck::kProbe) {
      aliveTimerStartedAt = millis();
      pn532.abort();
      vTaskDelay(pdMS_TO_TICKS(kAbortSettleMs));
      uint8_t version[4];
      result = pn532.firmwareVersion(version);
      if (result == 0) {
        return Armed::kArmAgain;
      }
      return resync("alive check", result) ? Armed::kArmAgain : Armed::kLost;
    }
  }
}

// Answers APDUs until the phone releases the tag. Returns how it ended for the log; code is the
// PN532 status byte (0x29: released by the phone) or a negative transport result.
const char *runSession(uint32_t &apdus, int16_t &code) {
  tag.startSession();
  for (;;) {
    uint8_t status = 0;
    int16_t length = pn532.getData(command, sizeof(command), kApduWaitMs, status);
    if (length < 0) {
      code = length;
      return "TgGetData transport";
    }
    if (status != 0) {
      code = status;
      return "TgGetData status";
    }
    size_t replyLength = tag.respond(command, static_cast<size_t>(length), reply);
    int16_t result = pn532.setData(reply, replyLength, kReplyMs, status);
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

// After a session: waits until the phone has left, no field for kNfcFieldQuietMs, at most
// kNfcFieldWaitCapMs. False when the PN532 stopped answering.
bool waitForFieldToLeave() {
  uint32_t start = millis();
  uint32_t quietSince = 0;
  for (;;) {
    int16_t field = pn532.fieldPresent();
    uint32_t now = millis();
    if (field < 0) {
      return resync("field check", field);
    }
    if (field == 1) {
      sawField();
    }
    if (field == 0) {
      quietSince = quietSince == 0 ? now : quietSince;
      if (now - quietSince >= kNfcFieldQuietMs) {
        return true;
      }
    } else {
      quietSince = 0;
    }
    if (now - start >= kNfcFieldWaitCapMs) {
      logLine("NFC: field still present after %lu ms, arming anyway", static_cast<unsigned long>(now - start));
      return true;
    }
    vTaskDelay(pdMS_TO_TICKS(kFieldPollMs));
  }
}

// Serves taps while the PN532 answers. Returns once it stopped answering and a resync did not
// bring it back.
void serveTaps() {
  uint32_t taps = 0;
  for (;;) {
    int16_t error = pn532.arm();
    if (error != 0) {
      if (!resync("TgInitAsTarget", error)) {
        return;
      }
      continue;
    }
    Armed armed = waitForPhone();
    if (armed == Armed::kLost) {
      return;
    }
    if (armed == Armed::kArmAgain) {
      continue;
    }
    uint32_t activatedAt = millis();
    uint32_t apdus = 0;
    int16_t code = 0;
    const char *ending = runSession(apdus, code);
    sawField();
    taps++;
    logLine("NFC: tap %lu, %lu APDUs in %lu ms, ended by %s %d (0x%02X)", static_cast<unsigned long>(taps),
            static_cast<unsigned long>(apdus), static_cast<unsigned long>(millis() - activatedAt), ending, code,
            static_cast<unsigned>(code & 0xFF));
    if (code < 0) {
      pn532.abort();
      if (!resync(ending, code)) {
        return;
      }
    }
    vTaskDelay(pdMS_TO_TICKS(kNfcRearmHoldOffMs));
    if (!waitForFieldToLeave()) {
      return;
    }
  }
}

void nfcLoop(void *) {
  char url[kMaxTipUrl + 1];
  uint8_t message[Type4Tag::kMaxMessage];
  size_t messageLength = 0;
  if (buildTipUrl(TIP_PUBLIC_BASE_URL, TIP_DEVICE_SLUG, kMaxTipUrl, url, sizeof(url)) > 0) {
    messageLength = buildUriMessage(url, message, sizeof(message));
  }
  if (messageLength == 0 || !tag.setNdef(message, messageLength)) {
    logLine("NFC: off, %s with slug %s is not a valid tip URL for the tag", TIP_PUBLIC_BASE_URL, TIP_DEVICE_SLUG);
    vTaskDelete(nullptr);
    return;
  }
  logLine("NFC: tag holds %s (%u byte message), PN532 on UART2 GPIO16/17", url, static_cast<unsigned>(messageLength));
  pn532.begin(kRunBaud);
  uint32_t retryMs = kNfcRetryFirstMs;
  for (;;) {
    int16_t error = link();
    if (error != 0) {
      logLine("NFC: no PN532 at 115200 or 921600 baud (%d), retrying in %lu s", error,
              static_cast<unsigned long>(retryMs / 1000));
      vTaskDelay(pdMS_TO_TICKS(retryMs));
      retryMs = retryMs * 2 < kNfcRetryCapMs ? retryMs * 2 : kNfcRetryCapMs;
      continue;
    }
    retryMs = kNfcRetryFirstMs;
    aliveTimerStartedAt = millis();
    logLine("NFC: tag ready");
    serveTaps();
    logLine("NFC: PN532 stopped answering, looking for it again");
  }
}

}  // namespace

void startNfcTask() { xTaskCreatePinnedToCore(nfcLoop, "nfc", kStackBytes, nullptr, kPriority, nullptr, kCore); }

}  // namespace tip
