// The PN532 commands the tag emulation needs, each returning the PN532's own result so every
// failure can be logged with its code. Transport results are the library's PN532_* codes
// (-1 bad ACK, -2 timeout, -3 bad frame, -4 no space); target commands also hand back the
// PN532 status byte (0x00 is success, see the PN532 user manual, section 7.1).
#pragma once

#include <Arduino.h>
#include <PN532Interface.h>

namespace spike {

class Pn532Target {
 public:
  Pn532Target(PN532Interface &link, HardwareSerial &serial) : link_(link), serial_(serial) {}

  // GetFirmwareVersion: writes IC, Ver, Rev, Support into version. 0 or a transport error.
  int16_t firmwareVersion(uint8_t version[4]);

  // SAMConfiguration, normal mode. 0 or a transport error.
  int16_t samConfig();

  // HSU wake-up, then SAMConfig. Brings the PN532 back after any failure.
  int16_t resync();

  // Sends TgInitAsTarget (PICC only, passive only) and waits for its ACK. Once this returns 0
  // the PN532 answers a phone by itself.
  int16_t arm();

  // Waits for the TgInitAsTarget response, which comes when a phone activates the tag.
  // Returns its length, or PN532_TIMEOUT when no phone came within timeoutMs (still armed).
  int16_t waitActivation(uint16_t timeoutMs);

  // TgGetData: the next command APDU. Returns its length, or a transport error; status is the
  // PN532 status byte (nonzero when the phone released the tag or left).
  int16_t getData(uint8_t *apdu, uint8_t capacity, uint16_t timeoutMs, uint8_t &status);

  // TgSetData: the response APDU. 0 or a transport error; status as for getData.
  int16_t setData(const uint8_t *apdu, uint8_t length, uint16_t timeoutMs, uint8_t &status);

  // GetGeneralStatus: 1 when the PN532 detects an external RF field, 0 when not, or an error.
  int16_t fieldPresent();

  // ReadRegister: the register's value (0 to 255), or a transport error.
  int16_t readRegister(uint16_t address);

  // An ACK frame from the host aborts the command the PN532 is running.
  void abort();

 private:
  int16_t call(const uint8_t *command, uint8_t length, uint8_t *reply, uint8_t capacity,
               uint16_t timeoutMs);

  PN532Interface &link_;
  HardwareSerial &serial_;
};

}  // namespace spike
