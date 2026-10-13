#include "pn532.h"

namespace tip {

namespace {

using pn532::FrameReader;

constexpr uint8_t kGetFirmwareVersion = 0x02;
constexpr uint8_t kGetGeneralStatus = 0x04;
constexpr uint8_t kSetSerialBaudRate = 0x10;
constexpr uint8_t kSamConfiguration = 0x14;
constexpr uint8_t kTgGetData = 0x86;
constexpr uint8_t kTgInitAsTarget = 0x8C;
constexpr uint8_t kTgSetData = 0x8E;

constexpr uint32_t kReplyMs = 1000;
// From the end of our frame on the wire to the PN532's ACK.
constexpr uint32_t kAckWaitMs = 10;
// The PN532 switches within 200 us of the host ACK (user manual, SetSerialBaudRate).
constexpr uint32_t kBaudSwitchMs = 1;
constexpr uint32_t kBitsPerByte = 10;

// Mode 0x05: PICC only, passive only. SENS_RES 0x0004, NFCID1 left to the PN532 (it fixes the
// first byte at 0x08 and draws the rest at random), SEL_RES 0x20 (ISO 14443-4). No FeliCa,
// NFCID3, general or historical bytes.
constexpr uint8_t kTgInitAsTargetCommand[] = {
    kTgInitAsTarget,
    0x05,
    0x04, 0x00,
    0x00, 0x00, 0x00,
    0x20,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0,
    0};

// HSU wake-up: 0x55 0x55, then a long preamble (user manual, section 7.2.11).
constexpr uint8_t kWakeup[] = {0x55, 0x55, 0x00, 0x00, 0x00};

}  // namespace

void Pn532::begin(uint32_t baud) {
  baud_ = baud;
  serial_.begin(baud, SERIAL_8N1, rxPin_, txPin_);
}

void Pn532::setBaud(uint32_t baud) {
  baud_ = baud;
  serial_.updateBaudRate(baud);
}

uint32_t Pn532::sendMs(size_t bytes) const {
  return static_cast<uint32_t>((bytes * kBitsPerByte * 1000 + baud_ - 1) / baud_);
}

FrameReader::Result Pn532::drain() {
  while (serial_.available() > 0) {
    FrameReader::Result result = reader_.feed(static_cast<uint8_t>(serial_.read()));
    if (result != FrameReader::Result::kNeedMore) {
      return result;
    }
  }
  return FrameReader::Result::kNeedMore;
}

int16_t Pn532::writeCommand(const uint8_t *header, size_t headerLength, const uint8_t *body, size_t bodyLength) {
  size_t length = pn532::buildFrame(header, headerLength, body, bodyLength, frame_, sizeof(frame_));
  if (length == 0) {
    return pn532::kNoSpace;
  }
  while (serial_.available() > 0) {
    serial_.read();
  }
  reader_.reset();
  command_ = header[0];
  // write returns once the frame is in the UART's FIFO, which can still hold most of it.
  serial_.write(frame_, length);
  uint32_t start = millis();
  uint32_t limit = sendMs(length) + kAckWaitMs;
  for (;;) {
    FrameReader::Result result = drain();
    if (result == FrameReader::Result::kAck) {
      return 0;
    }
    if (result != FrameReader::Result::kNeedMore) {
      return pn532::kBadAck;
    }
    if (millis() - start >= limit) {
      return pn532::kTimeout;
    }
    vTaskDelay(1);
  }
}

int16_t Pn532::readResponse(uint8_t *out, size_t capacity, uint32_t timeoutMs) {
  uint32_t start = millis();
  for (;;) {
    FrameReader::Result result = drain();
    if (result == FrameReader::Result::kFrame) {
      return pn532::unpackResponse(reader_, command_, out, capacity);
    }
    if (result != FrameReader::Result::kNeedMore && result != FrameReader::Result::kAck) {
      return pn532::kInvalidFrame;
    }
    if (millis() - start >= timeoutMs) {
      return pn532::kTimeout;
    }
    vTaskDelay(1);
  }
}

int16_t Pn532::call(const uint8_t *command, size_t length, uint8_t *reply, size_t capacity, uint32_t timeoutMs) {
  int16_t sent = writeCommand(command, length);
  if (sent != 0) {
    return sent;
  }
  return readResponse(reply, capacity, timeoutMs);
}

int16_t Pn532::firmwareVersion(uint8_t version[4]) {
  const uint8_t command[] = {kGetFirmwareVersion};
  int16_t length = call(command, sizeof(command), version, 4, kReplyMs);
  return length == 4 ? 0 : (length < 0 ? length : pn532::kInvalidFrame);
}

int16_t Pn532::samConfig() {
  // Normal mode, 1 s virtual card timeout, IRQ pin on. The reply has no data bytes.
  const uint8_t command[] = {kSamConfiguration, 0x01, 0x14, 0x01};
  uint8_t reply[4];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  return length < 0 ? length : 0;
}

void Pn532::wakeup() {
  serial_.write(kWakeup, sizeof(kWakeup));
  vTaskDelay(pdMS_TO_TICKS(sendMs(sizeof(kWakeup)) + 1));
}

int16_t Pn532::resync() {
  wakeup();
  return samConfig();
}

int16_t Pn532::arm() { return writeCommand(kTgInitAsTargetCommand, sizeof(kTgInitAsTargetCommand)); }

int16_t Pn532::waitActivation(uint32_t timeoutMs) {
  uint8_t reply[64];
  return readResponse(reply, sizeof(reply), timeoutMs);
}

int16_t Pn532::getData(uint8_t *apdu, size_t capacity, uint32_t timeoutMs, uint8_t &status) {
  const uint8_t command[] = {kTgGetData};
  uint8_t reply[pn532::kMaxFrameData];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), timeoutMs);
  if (length < 1) {
    status = 0xFF;
    return length < 0 ? length : pn532::kInvalidFrame;
  }
  status = reply[0];
  size_t apduLength = static_cast<size_t>(length - 1);
  if (apduLength > capacity) {
    return pn532::kNoSpace;
  }
  memcpy(apdu, reply + 1, apduLength);
  return static_cast<int16_t>(apduLength);
}

int16_t Pn532::setData(const uint8_t *apdu, size_t length, uint32_t timeoutMs, uint8_t &status) {
  const uint8_t header[] = {kTgSetData};
  int16_t sent = writeCommand(header, sizeof(header), apdu, length);
  if (sent != 0) {
    status = 0xFF;
    return sent;
  }
  uint8_t reply[4];
  int16_t replyLength = readResponse(reply, sizeof(reply), timeoutMs);
  if (replyLength < 1) {
    status = 0xFF;
    return replyLength < 0 ? replyLength : pn532::kInvalidFrame;
  }
  status = reply[0];
  return 0;
}

int16_t Pn532::fieldPresent() {
  const uint8_t command[] = {kGetGeneralStatus};
  uint8_t reply[16];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  if (length < 2) {
    return length < 0 ? length : pn532::kInvalidFrame;
  }
  return reply[1] != 0 ? 1 : 0;
}

int16_t Pn532::setSerialBaudRate(uint8_t code, uint32_t baud) {
  const uint8_t command[] = {kSetSerialBaudRate, code};
  uint8_t reply[4];
  int16_t length = call(command, sizeof(command), reply, sizeof(reply), kReplyMs);
  if (length < 0) {
    return length;
  }
  abort();
  vTaskDelay(pdMS_TO_TICKS(sendMs(sizeof(pn532::kAckFrame)) + kBaudSwitchMs));
  setBaud(baud);
  return 0;
}

void Pn532::abort() { serial_.write(pn532::kAckFrame, sizeof(pn532::kAckFrame)); }

}  // namespace tip
