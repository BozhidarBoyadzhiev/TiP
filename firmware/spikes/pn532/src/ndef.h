// Builds the one NDEF message the spike uses: a single short URI record (NFC Forum RTD URI).
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace spike {

// The demo panda's tip page; TipPageController serves /t/{slug}.
constexpr const char *kTipUrl = "https://tip-integrated-panda.onrender.com/t/demo-panda";

// Writes the NDEF message (one URI record, "https://" abbreviated to code 0x04) into out.
// Returns its length, or 0 when the URL does not start with https:// or does not fit.
size_t buildUriMessage(const char *url, uint8_t *out, size_t capacity);

}  // namespace spike
