#include "ndef.h"

#include <string.h>

namespace spike {

namespace {

constexpr const char *kHttpsPrefix = "https://";
constexpr uint8_t kHttpsPrefixCode = 0x04;
// MB, ME and SR set, TNF 1 (well known).
constexpr uint8_t kShortWellKnownRecord = 0xD1;
constexpr uint8_t kUriRecordType = 'U';
constexpr size_t kHeaderBytes = 4;
constexpr size_t kMaxShortPayload = 255;

}  // namespace

size_t buildUriMessage(const char *url, uint8_t *out, size_t capacity) {
  size_t prefixLength = strlen(kHttpsPrefix);
  if (strncmp(url, kHttpsPrefix, prefixLength) != 0) {
    return 0;
  }
  const char *rest = url + prefixLength;
  size_t restLength = strlen(rest);
  size_t payloadLength = 1 + restLength;
  size_t total = kHeaderBytes + payloadLength;
  if (payloadLength > kMaxShortPayload || total > capacity) {
    return 0;
  }
  out[0] = kShortWellKnownRecord;
  out[1] = 1;
  out[2] = static_cast<uint8_t>(payloadLength);
  out[3] = kUriRecordType;
  out[4] = kHttpsPrefixCode;
  memcpy(out + 5, rest, restLength);
  return total;
}

}  // namespace spike
