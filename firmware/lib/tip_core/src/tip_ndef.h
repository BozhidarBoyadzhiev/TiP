// The tip URL the panda's NFC tag holds and the NDEF message that carries it: one short URI record
// (NFC Forum RTD URI) with "https://" abbreviated to code 0x04.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {

// The record header (MB ME SR TNF 1, type length, payload length, type 'U') and the prefix code.
constexpr size_t kUriRecordOverhead = 5;
constexpr size_t kHttpsPrefixLength = 8;

// The longest URL a message of maxMessageBytes holds.
constexpr size_t maxUriLength(size_t maxMessageBytes) {
  return maxMessageBytes - kUriRecordOverhead + kHttpsPrefixLength;
}

// Writes baseUrl + "/t/" + slug, the path TipPageController serves, and a terminating zero into
// out. baseUrl is "https://" and a host, one trailing slash allowed; slug follows DeviceSlug
// ([a-z0-9-]{3,40}). Returns the URL's length, or 0 when either is invalid or the URL is longer
// than maxLength or does not fit in capacity.
size_t buildTipUrl(const char *baseUrl, const char *slug, size_t maxLength, char *out, size_t capacity);

// Writes the NDEF message (one URI record) for an https URL into out. Returns its length, or 0
// when the URL does not start with https:// or the message does not fit in capacity.
size_t buildUriMessage(const char *url, uint8_t *out, size_t capacity);

}  // namespace tip
