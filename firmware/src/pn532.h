// The PN532 over HSU and the commands the tag emulation needs. Each returns the PN532's own result
// so every failure can be logged with its code: 0 or a transport result (pn532::kBadAck,
// kTimeout, kInvalidFrame, kNoSpace), and target commands also hand back the PN532 status byte
// (0x00 is success, see the PN532 user manual, section 7.1). Every wait for the PN532 yields with
// vTaskDelay(1), so the task below everything else never spins.
#pragma once

#include <Arduino.h>

#include "tip_pn532_frame.h"

namespace tip {

class Pn532 {
 public:
  Pn532(HardwareSerial &serial, int8_t rxPin, int8_t txPin) : serial_(serial), rxPin_(rxPin), txPin_(txPin) {}

  // Opens the UART at baud.
  void begin(uint32_t baud);

  // Reopens the UART at baud, for a PN532 that kept another speed.
  void setBaud(uint32_t baud);

  // GetFirmwareVersion: writes IC, Ver, Rev, Support into version.
  int16_t firmwareVersion(uint8_t version[4]);

  // SAMConfiguration, normal mode.
  int16_t samConfig();

  // HSU wake-up: needed before the first command after the PN532 powers up.
  void wakeup();

  // HSU wake-up, then SAMConfig. Brings the PN532 back after any failure.
  int16_t resync();

  // Sends TgInitAsTarget (PICC only, passive only) and waits for its ACK. Once this returns 0
  // the PN532 answers a phone by itself.
  int16_t arm();

  // Waits for the TgInitAsTarget response, which comes when a phone activates the tag.
  // Returns its length, or pn532::kTimeout when no phone came within timeoutMs (still armed).
  int16_t waitActivation(uint32_t timeoutMs);

  // TgGetData: the next command APDU. Returns its length, or a transport result; status is the
  // PN532 status byte (nonzero when the phone released the tag or left).
  int16_t getData(uint8_t *apdu, size_t capacity, uint32_t timeoutMs, uint8_t &status);

  // TgSetData: the response APDU. 0 or a transport result; status as for getData.
  int16_t setData(const uint8_t *apdu, size_t length, uint32_t timeoutMs, uint8_t &status);

  // GetGeneralStatus: 1 when the PN532 detects an external RF field, 0 when not, or an error.
  int16_t fieldPresent();

  // SetSerialBaudRate (code 0x04 is 115200, 0x07 is 921600). After the reply the host sends an
  // ACK, then both sides switch; the UART is reopened at baud.
  int16_t setSerialBaudRate(uint8_t code, uint32_t baud);

  // An ACK frame from the host aborts the command the PN532 is running.
  void abort();

 private:
  // Drops unread bytes, sends header and body as one frame and waits for the PN532's ACK.
  int16_t writeCommand(const uint8_t *header, size_t headerLength, const uint8_t *body = nullptr,
                       size_t bodyLength = 0);
  // Reads the answer to the last command into out: its length or a transport result.
  int16_t readResponse(uint8_t *out, size_t capacity, uint32_t timeoutMs);
  int16_t call(const uint8_t *command, size_t length, uint8_t *reply, size_t capacity, uint32_t timeoutMs);
  // Feeds every byte the UART holds to the reader; returns the first result other than kNeedMore.
  pn532::FrameReader::Result drain();
  // How long bytes take on the wire at the current speed, rounded up.
  uint32_t sendMs(size_t bytes) const;

  HardwareSerial &serial_;
  int8_t rxPin_;
  int8_t txPin_;
  uint32_t baud_ = 115200;
  uint8_t command_ = 0;
  pn532::FrameReader reader_;
  uint8_t frame_[pn532::kMaxFrameData + pn532::kFrameOverhead];
};

}  // namespace tip
