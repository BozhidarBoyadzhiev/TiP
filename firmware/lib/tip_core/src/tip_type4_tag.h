// NFC Forum Type 4 tag, read only: answers the ISO 7816-4 command APDUs a phone sends to read
// the NDEF file. Ported from Seeed-Studio/PN532 v1.1.0 emulatetag.cpp (BSD-3-Clause, Armin Wieser).
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {

class Type4Tag {
 public:
  static constexpr size_t kNdefFileSize = 128;
  // The NDEF file starts with the message's two byte length.
  static constexpr size_t kMaxMessage = kNdefFileSize - 2;
  // Longest response APDU: a READ BINARY of the whole NDEF file plus the two status bytes. Reads
  // past the selected file are refused, so nothing longer is ever sent.
  static constexpr size_t kMaxReply = kNdefFileSize + 2;

  // Stores the message with its two byte length in front. False when it does not fit.
  bool setNdef(const uint8_t *message, size_t length);

  // Call when a phone activates the tag: no file is selected.
  void startSession() { current_ = File::kNone; }

  // Answers one command APDU. reply holds at least kMaxReply bytes; returns its length.
  size_t respond(const uint8_t *command, size_t length, uint8_t *reply);

 private:
  enum class File { kNone, kCapability, kNdef };

  File current_ = File::kNone;
  uint8_t ndefFile_[kNdefFileSize] = {0};
};

}  // namespace tip
