#include "tip_ndef.h"

#include <string.h>

namespace tip {

namespace {

constexpr const char *kHttpsPrefix = "https://";
constexpr const char *kTipPath = "/t/";
constexpr uint8_t kHttpsPrefixCode = 0x04;
// MB, ME and SR set, TNF 1 (well known).
constexpr uint8_t kShortWellKnownRecord = 0xD1;
constexpr uint8_t kUriRecordType = 'U';
constexpr size_t kMaxShortPayload = 255;
constexpr size_t kMinSlug = 3;
constexpr size_t kMaxSlug = 40;

bool isLowerOrDigit(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'); }

// A lowercase host name or address, with an optional port.
bool validHost(const char *host, size_t length) {
  if (length == 0) {
    return false;
  }
  for (size_t i = 0; i < length; i++) {
    char c = host[i];
    if (!isLowerOrDigit(c) && c != '.' && c != '-' && c != ':') {
      return false;
    }
  }
  return true;
}

bool validSlug(const char *slug, size_t length) {
  if (length < kMinSlug || length > kMaxSlug) {
    return false;
  }
  for (size_t i = 0; i < length; i++) {
    if (!isLowerOrDigit(slug[i]) && slug[i] != '-') {
      return false;
    }
  }
  return true;
}

}  // namespace

size_t buildTipUrl(const char *baseUrl, const char *slug, size_t maxLength, char *out, size_t capacity) {
  if (strncmp(baseUrl, kHttpsPrefix, kHttpsPrefixLength) != 0) {
    return 0;
  }
  size_t baseLength = strlen(baseUrl);
  if (baseLength > kHttpsPrefixLength && baseUrl[baseLength - 1] == '/') {
    baseLength--;
  }
  size_t slugLength = strlen(slug);
  if (!validHost(baseUrl + kHttpsPrefixLength, baseLength - kHttpsPrefixLength) || !validSlug(slug, slugLength)) {
    return 0;
  }
  size_t pathLength = strlen(kTipPath);
  size_t total = baseLength + pathLength + slugLength;
  if (total > maxLength || total + 1 > capacity) {
    return 0;
  }
  memcpy(out, baseUrl, baseLength);
  memcpy(out + baseLength, kTipPath, pathLength);
  memcpy(out + baseLength + pathLength, slug, slugLength);
  out[total] = '\0';
  return total;
}

size_t buildUriMessage(const char *url, uint8_t *out, size_t capacity) {
  if (strncmp(url, kHttpsPrefix, kHttpsPrefixLength) != 0) {
    return 0;
  }
  const char *rest = url + kHttpsPrefixLength;
  size_t restLength = strlen(rest);
  size_t payloadLength = 1 + restLength;
  size_t total = kUriRecordOverhead + restLength;
  if (payloadLength > kMaxShortPayload || total > capacity) {
    return 0;
  }
  out[0] = kShortWellKnownRecord;
  out[1] = 1;
  out[2] = static_cast<uint8_t>(payloadLength);
  out[3] = kUriRecordType;
  out[4] = kHttpsPrefixCode;
  memcpy(out + kUriRecordOverhead, rest, restLength);
  return total;
}

}  // namespace tip
