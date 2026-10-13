#include "type4_tag.h"

#include <string.h>

namespace spike {

namespace {

constexpr size_t kIns = 1;
constexpr size_t kP1 = 2;
constexpr size_t kP2 = 3;
constexpr size_t kLc = 4;  // Le for READ BINARY
constexpr size_t kData = 5;
constexpr size_t kHeaderBytes = 5;

constexpr uint8_t kSelect = 0xA4;
constexpr uint8_t kReadBinary = 0xB0;
constexpr uint8_t kSelectById = 0x00;
constexpr uint8_t kSelectByName = 0x04;
constexpr uint8_t kFirstOrOnly = 0x0C;

// P2, Lc and the NDEF Tag Application name (D2760000850101).
constexpr uint8_t kNdefApplication[] = {0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01};

// CCLEN 15, mapping version 2.0, MLe 0x54, MLc 0xFF, then the NDEF file control TLV: file
// E104, maximum size, read access granted, write access denied.
constexpr uint8_t kCapabilityContainer[] = {
    0x00, 0x0F, 0x20, 0x00, 0x54, 0x00, 0xFF, 0x04, 0x06, 0xE1, 0x04,
    0x00, Type4Tag::kNdefFileSize, 0x00, 0xFF};

size_t status(uint8_t *reply, size_t offset, uint8_t sw1, uint8_t sw2) {
  reply[offset] = sw1;
  reply[offset + 1] = sw2;
  return offset + 2;
}

size_t ok(uint8_t *reply, size_t dataLength = 0) { return status(reply, dataLength, 0x90, 0x00); }
size_t notFound(uint8_t *reply) { return status(reply, 0, 0x6A, 0x82); }
size_t notSupported(uint8_t *reply) { return status(reply, 0, 0x6A, 0x81); }
size_t endOfFile(uint8_t *reply) { return status(reply, 0, 0x62, 0x82); }
size_t wrongLength(uint8_t *reply) { return status(reply, 0, 0x67, 0x00); }

}  // namespace

bool Type4Tag::setNdef(const uint8_t *message, size_t length) {
  if (length > kNdefFileSize - 2) {
    return false;
  }
  ndefFile_[0] = static_cast<uint8_t>(length >> 8);
  ndefFile_[1] = static_cast<uint8_t>(length);
  memcpy(ndefFile_ + 2, message, length);
  return true;
}

size_t Type4Tag::respond(const uint8_t *command, size_t length, uint8_t *reply) {
  if (length < kHeaderBytes) {
    return wrongLength(reply);
  }
  uint8_t p1 = command[kP1];
  uint8_t p2 = command[kP2];
  uint8_t lc = command[kLc];
  uint16_t offset = static_cast<uint16_t>((p1 << 8) | p2);
  switch (command[kIns]) {
    case kSelect:
      if (p1 == kSelectByName) {
        if (length >= kP2 + sizeof(kNdefApplication) &&
            memcmp(command + kP2, kNdefApplication, sizeof(kNdefApplication)) == 0) {
          return ok(reply);
        }
        return notSupported(reply);
      }
      if (p1 != kSelectById) {
        return notSupported(reply);
      }
      if (p2 != kFirstOrOnly) {
        return ok(reply);
      }
      if (lc == 2 && length >= kData + 2 && command[kData] == 0xE1 &&
          (command[kData + 1] == 0x03 || command[kData + 1] == 0x04)) {
        current_ = command[kData + 1] == 0x03 ? File::kCapability : File::kNdef;
        return ok(reply);
      }
      return notFound(reply);
    case kReadBinary: {
      // Same check as the library: only the offset is bounded (brief 05c step 5 fixes it).
      if (current_ == File::kNone) {
        return notFound(reply);
      }
      if (offset > kNdefFileSize) {
        return endOfFile(reply);
      }
      const uint8_t *file = current_ == File::kCapability ? kCapabilityContainer : ndefFile_;
      memcpy(reply, file + offset, lc);
      return ok(reply, lc);
    }
    default:
      return notSupported(reply);
  }
}

}  // namespace spike
