#include "tip_protocol.h"

#include <ArduinoJson.h>
#include <string.h>

namespace tip {

namespace {

// Room for the four kept fields of the largest valid reaction, with margin. A longer string in a
// kept field does not fit and makes the message malformed.
constexpr size_t kDocumentBytes = 256;
constexpr uint8_t kNestingLimit = 4;

struct KindName {
  const char *name;
  Kind kind;
};

struct ReasonName {
  const char *name;
  ClosingReason reason;
};

constexpr ReasonName kReasons[] = {
    {"replaced", ClosingReason::Replaced},
    {"timeout", ClosingReason::Timeout},
    {"shutdown", ClosingReason::Shutdown},
};

constexpr KindName kKinds[] = {
    {"WARM", Kind::Warm},
    {"PLAYFUL", Kind::Playful},
    {"REQUEST", Kind::Request},
    {"PLAIN", Kind::Plain},
};

ParsedMessage result(MessageType type, const char *problem) {
  ParsedMessage parsed{};
  parsed.type = type;
  parsed.problem = problem;
  return parsed;
}

bool isLowerHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); }

}  // namespace

const char *closingReasonName(ClosingReason reason) {
  for (const ReasonName &known : kReasons) {
    if (known.reason == reason) {
      return known.name;
    }
  }
  return "unknown";
}

bool isCanonicalUuid(const char *text, size_t length) {
  if (text == nullptr || length != kIdLength) {
    return false;
  }
  for (size_t i = 0; i < kIdLength; i++) {
    bool dash = i == 8 || i == 13 || i == 18 || i == 23;
    if (dash ? text[i] != '-' : !isLowerHex(text[i])) {
      return false;
    }
  }
  return true;
}

ParsedMessage parseServerMessage(const char *data, size_t length, size_t maxBytes) {
  if (data == nullptr || length == 0) {
    return result(MessageType::Malformed, "empty frame");
  }
  if (length > maxBytes) {
    return result(MessageType::Malformed, "frame too large");
  }

  // Room for the five kept keys on 64 bit hosts too; a key that does not fit is silently not kept.
  StaticJsonDocument<256> filter;
  filter["type"] = true;
  filter["id"] = true;
  filter["reaction"] = true;
  filter["repeats"] = true;
  filter["reason"] = true;

  StaticJsonDocument<kDocumentBytes> doc;
  DeserializationError error =
      deserializeJson(doc, data, length, DeserializationOption::Filter(filter),
                      DeserializationOption::NestingLimit(kNestingLimit));
  if (error == DeserializationError::NoMemory) {
    return result(MessageType::Malformed, "a field is too long");
  }
  if (error) {
    return result(MessageType::Malformed, "not valid JSON");
  }
  if (!doc.is<JsonObject>()) {
    return result(MessageType::Malformed, "not a JSON object");
  }

  JsonVariantConst type = doc["type"];
  if (type.isNull()) {
    return result(MessageType::Malformed, "no type");
  }
  if (!type.is<const char *>()) {
    return result(MessageType::Malformed, "type is not a string");
  }
  if (strcmp(type.as<const char *>(), "hello") == 0) {
    return result(MessageType::Hello, nullptr);
  }
  if (strcmp(type.as<const char *>(), "closing") == 0) {
    JsonVariantConst reason = doc["reason"];
    if (!reason.is<const char *>()) {
      return result(MessageType::Malformed, reason.isNull() ? "closing without reason" : "reason is not a string");
    }
    ParsedMessage parsed = result(MessageType::Closing, nullptr);
    parsed.closingReason = ClosingReason::Unknown;
    for (const ReasonName &known : kReasons) {
      if (strcmp(reason.as<const char *>(), known.name) == 0) {
        parsed.closingReason = known.reason;
      }
    }
    return parsed;
  }
  if (strcmp(type.as<const char *>(), "reaction") != 0) {
    return result(MessageType::Ignored, "unknown type");
  }

  JsonVariantConst id = doc["id"];
  if (!id.is<const char *>()) {
    return result(MessageType::Malformed, id.isNull() ? "reaction without id" : "id is not a string");
  }
  const char *idText = id.as<const char *>();
  if (!isCanonicalUuid(idText, strlen(idText))) {
    return result(MessageType::Malformed, "id is not a lowercase uuid");
  }

  JsonVariantConst reaction = doc["reaction"];
  if (!reaction.is<const char *>()) {
    return result(MessageType::Malformed,
                  reaction.isNull() ? "reaction without kind" : "kind is not a string");
  }

  ParsedMessage parsed = result(MessageType::Reaction, nullptr);
  memcpy(parsed.reaction.id, idText, kIdLength);
  parsed.reaction.id[kIdLength] = '\0';
  parsed.unknownKind = true;
  for (const KindName &known : kKinds) {
    if (strcmp(reaction.as<const char *>(), known.name) == 0) {
      parsed.reaction.kind = known.kind;
      parsed.unknownKind = false;
    }
  }

  JsonVariantConst repeats = doc["repeats"];
  if (parsed.unknownKind) {
    parsed.reaction.kind = Kind::Plain;
    parsed.reaction.repeats = kMinRepeats;
  } else if (repeats.is<long long>()) {
    parsed.reaction.repeats = static_cast<uint8_t>(clampRepeats(repeats.as<long long>()));
  } else {
    parsed.reaction.repeats = kMinRepeats;
    parsed.repeatsDefaulted = true;
  }
  return parsed;
}

size_t buildAck(const char *id, char *out, size_t outSize) {
  static const char kPrefix[] = "{\"type\":\"ack\",\"id\":\"";
  static const char kSuffix[] = "\"}";
  size_t prefix = sizeof(kPrefix) - 1;
  size_t suffix = sizeof(kSuffix) - 1;
  size_t total = prefix + kIdLength + suffix;
  if (id == nullptr || out == nullptr || outSize < total + 1 || !isCanonicalUuid(id, strnlen(id, kIdLength + 1))) {
    return 0;
  }
  memcpy(out, kPrefix, prefix);
  memcpy(out + prefix, id, kIdLength);
  memcpy(out + prefix + kIdLength, kSuffix, suffix);
  out[total] = '\0';
  return total;
}

}  // namespace tip
