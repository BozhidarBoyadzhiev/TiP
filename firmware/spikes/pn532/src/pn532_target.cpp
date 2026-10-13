#include "pn532_target.h"

#include <PN532.h>

namespace spike {

namespace {

constexpr uint16_t kReplyMs = 1000;

// Mode 0x05: PICC only, passive only. SENS_RES 0x0004, NFCID1 left to the PN532 (it fixes the
// first byte at 0x08 and draws the rest at random), SEL_RES 0x20 (ISO 14443-4). No FeliCa,
// NFCID3, general or historical bytes.
constexpr uint8_t kTgInitAsTarget[] = {
    PN532_COMMAND_TGINITASTARGET,
    0x05,
    0x04, 0x00,
    0x00, 0x00, 0x00,
    0x20,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0,
    0};

constexpr uint8_t kGetGeneralStatus = 0x04;
constexpr uint8_t kAckFrame[] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};

}  // namespace

int16_t Pn532Target::call(const uint8_t *command, uint8_t length, uint8_t *reply,
                          uint8_t capacity, uint16_t timeoutMs) {
  int8_t sent = link_.writeCommand(command, length);
  if (sent != 0) {
    return sent;
  }
  return link_.readResponse(reply, capacity, timeoutMs);
}

int16_t Pn532Target::firmwareVersion(uint8_t version[4]) {
  const uint8_t command[] = {PN532_COMMAND_GETFIRMWAREVERSION};
  int16_t length = call(command, sizeof(command), version, 4, kReplyMs);
  return length == 4 ? 0 : (length < 0 ? length : PN532_INVALID_FRAME);
}

int16_t Pn532Target::samConfig() {
  // Normal mode, 1 s virtual card timeout, IRQ pin on. The reply has no data bytes.
  const uint8_t command[] = {PN532_COMMAND_SAMCONFIGURATION, 0x01, 0x14, 0x01};
  uint8_t reply[4];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  return length < 0 ? length : 0;
}

int16_t Pn532Target::resync() {
  link_.wakeup();
  return samConfig();
}

int16_t Pn532Target::arm() { return link_.writeCommand(kTgInitAsTarget, sizeof(kTgInitAsTarget)); }

int16_t Pn532Target::waitActivation(uint16_t timeoutMs) {
  uint8_t reply[64];
  return link_.readResponse(reply, sizeof(reply), timeoutMs);
}

int16_t Pn532Target::getData(uint8_t *apdu, uint8_t capacity, uint16_t timeoutMs,
                             uint8_t &status) {
  const uint8_t command[] = {PN532_COMMAND_TGGETDATA};
  uint8_t reply[256];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply) - 1, timeoutMs);
  if (length < 1) {
    status = 0xFF;
    return length < 0 ? length : PN532_INVALID_FRAME;
  }
  status = reply[0];
  int16_t apduLength = min<int16_t>(length - 1, capacity);
  memcpy(apdu, reply + 1, apduLength);
  return apduLength;
}

int16_t Pn532Target::setData(const uint8_t *apdu, uint8_t length, uint16_t timeoutMs,
                             uint8_t &status) {
  const uint8_t header[] = {PN532_COMMAND_TGSETDATA};
  int8_t sent = link_.writeCommand(header, sizeof(header), apdu, length);
  if (sent != 0) {
    status = 0xFF;
    return sent;
  }
  uint8_t reply[4];
  int16_t replyLength = link_.readResponse(reply, sizeof(reply), timeoutMs);
  if (replyLength < 1) {
    status = 0xFF;
    return replyLength < 0 ? replyLength : PN532_INVALID_FRAME;
  }
  status = reply[0];
  return 0;
}

int16_t Pn532Target::fieldPresent() {
  const uint8_t command[] = {kGetGeneralStatus};
  uint8_t reply[16];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  if (length < 2) {
    return length < 0 ? length : PN532_INVALID_FRAME;
  }
  return reply[1] != 0 ? 1 : 0;
}

int16_t Pn532Target::readRegister(uint16_t address) {
  const uint8_t command[] = {PN532_COMMAND_READREGISTER, static_cast<uint8_t>(address >> 8),
                             static_cast<uint8_t>(address)};
  uint8_t reply[4];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  if (length < 1) {
    return length < 0 ? length : PN532_INVALID_FRAME;
  }
  return reply[0];
}

void Pn532Target::abort() { serial_.write(kAckFrame, sizeof(kAckFrame)); }

}  // namespace spike
