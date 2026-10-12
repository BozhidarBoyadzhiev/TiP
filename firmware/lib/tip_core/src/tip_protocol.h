// The device side of the device channel in docs/DEVICE_PROTOCOL.md. Server messages are untrusted
// input even over TLS: the frame size is capped, the JSON goes into a fixed size document that
// keeps only the four fields the device needs, and every field is validated.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "tip_patterns.h"

namespace tip {

// A canonical lowercase uuid: 36 characters.
constexpr size_t kIdLength = 36;

struct Reaction {
  char id[kIdLength + 1];
  Kind kind;
  uint8_t repeats;
};

enum class MessageType : uint8_t {
  Hello,
  Reaction,
  // The server is about to close the connection, and says why.
  Closing,
  // Well formed, but not a message the device acts on.
  Ignored,
  // Not acked and not played.
  Malformed,
};

// The reason of a closing message. Close codes do not reach the device through Render's proxy, so
// this message is the only way to learn why the server closes.
enum class ClosingReason : uint8_t { Replaced, Timeout, Shutdown, Unknown };

const char *closingReasonName(ClosingReason reason);

struct ParsedMessage {
  MessageType type;
  Reaction reaction;
  ClosingReason closingReason;
  // Set for a reaction whose kind is not one of the four: it plays as PLAIN with repeats 1.
  bool unknownKind;
  // Set for a reaction whose repeats was missing or not an integer: it plays with repeats 1.
  bool repeatsDefaulted;
  // Why a message was ignored or malformed, for the log. Never contains server text.
  const char *problem;
};

ParsedMessage parseServerMessage(const char *data, size_t length, size_t maxBytes);

// Writes {"type":"ack","id":"<id>"} with a terminating NUL. Returns the length without the NUL,
// or 0 when `id` is not a canonical uuid or the buffer is too small.
size_t buildAck(const char *id, char *out, size_t outSize);

bool isCanonicalUuid(const char *text, size_t length);

}  // namespace tip
