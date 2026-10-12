#include <string.h>
#include <unity.h>

#include <string>

#include "tip_config.h"
#include "tip_protocol.h"

using tip::Kind;
using tip::MessageType;
using tip::ParsedMessage;

void setUp() {}
void tearDown() {}

namespace {

const char *const kId = "3f2c1b6e-8a4d-4c2b-9e1f-0a1b2c3d4e5f";

ParsedMessage parse(const std::string &text) {
  return tip::parseServerMessage(text.data(), text.size(), tip::kMaxFrameBytes);
}

std::string reaction(const std::string &fields) {
  return "{\"type\":\"reaction\",\"id\":\"" + std::string(kId) + "\"" + fields +
         ",\"at\":\"2026-10-13T14:02:11Z\"}";
}

void assertMalformed(const std::string &text) {
  ParsedMessage parsed = parse(text);
  TEST_ASSERT_EQUAL_INT_MESSAGE(static_cast<int>(MessageType::Malformed),
                                static_cast<int>(parsed.type), text.c_str());
  TEST_ASSERT_NOT_NULL(parsed.problem);
}

}  // namespace

void test_valid_reaction() {
  ParsedMessage parsed = parse(reaction(",\"reaction\":\"WARM\",\"repeats\":3"));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Reaction), static_cast<int>(parsed.type));
  TEST_ASSERT_EQUAL_STRING(kId, parsed.reaction.id);
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Kind::Warm), static_cast<int>(parsed.reaction.kind));
  TEST_ASSERT_EQUAL_UINT8(3, parsed.reaction.repeats);
  TEST_ASSERT_FALSE(parsed.unknownKind);
  TEST_ASSERT_FALSE(parsed.repeatsDefaulted);
}

void test_each_known_kind() {
  const char *names[] = {"WARM", "PLAYFUL", "REQUEST", "PLAIN"};
  const Kind kinds[] = {Kind::Warm, Kind::Playful, Kind::Request, Kind::Plain};
  for (int i = 0; i < 4; i++) {
    ParsedMessage parsed =
        parse(reaction(",\"reaction\":\"" + std::string(names[i]) + "\",\"repeats\":2"));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(kinds[i]), static_cast<int>(parsed.reaction.kind));
  }
}

void test_hello() {
  ParsedMessage parsed =
      parse("{\"type\":\"hello\",\"deviceName\":\"Busker Panda\",\"serverTime\":\"2026-10-13T14:00:00Z\"}");
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Hello), static_cast<int>(parsed.type));
}

void test_closing_with_each_reason() {
  const char *names[] = {"replaced", "timeout", "shutdown"};
  const tip::ClosingReason reasons[] = {tip::ClosingReason::Replaced, tip::ClosingReason::Timeout,
                                        tip::ClosingReason::Shutdown};
  for (int i = 0; i < 3; i++) {
    ParsedMessage parsed = parse("{\"type\":\"closing\",\"reason\":\"" + std::string(names[i]) + "\"}");
    TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Closing), static_cast<int>(parsed.type));
    TEST_ASSERT_EQUAL_INT(static_cast<int>(reasons[i]), static_cast<int>(parsed.closingReason));
    TEST_ASSERT_EQUAL_STRING(names[i], tip::closingReasonName(parsed.closingReason));
  }
}

void test_closing_with_an_unknown_reason_is_still_closing() {
  ParsedMessage parsed = parse("{\"type\":\"closing\",\"reason\":\"maintenance\"}");
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Closing), static_cast<int>(parsed.type));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(tip::ClosingReason::Unknown), static_cast<int>(parsed.closingReason));
  TEST_ASSERT_EQUAL_STRING("unknown", tip::closingReasonName(parsed.closingReason));
}

void test_closing_without_a_string_reason_is_malformed() {
  assertMalformed("{\"type\":\"closing\"}");
  assertMalformed("{\"type\":\"closing\",\"reason\":4000}");
  assertMalformed("{\"type\":\"closing\",\"reason\":null}");
  assertMalformed("{\"type\":\"closing\",\"reason\":\"" + std::string(400, 'r') + "\"}");
}

void test_unknown_type_is_ignored() {
  ParsedMessage parsed = parse("{\"type\":\"news\",\"id\":\"x\"}");
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Ignored), static_cast<int>(parsed.type));
}

void test_unknown_kind_plays_plain_once() {
  ParsedMessage parsed = parse(reaction(",\"reaction\":\"SPARKLY\",\"repeats\":5"));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Reaction), static_cast<int>(parsed.type));
  TEST_ASSERT_TRUE(parsed.unknownKind);
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Kind::Plain), static_cast<int>(parsed.reaction.kind));
  TEST_ASSERT_EQUAL_UINT8(1, parsed.reaction.repeats);
  // Kind names are case sensitive, like the server's enum.
  TEST_ASSERT_TRUE(parse(reaction(",\"reaction\":\"warm\",\"repeats\":2")).unknownKind);
}

void test_repeats_missing_or_wrong_type_means_one() {
  const char *variants[] = {"", ",\"repeats\":\"3\"", ",\"repeats\":3.5", ",\"repeats\":null",
                            ",\"repeats\":true", ",\"repeats\":[3]"};
  for (const char *variant : variants) {
    ParsedMessage parsed = parse(reaction(",\"reaction\":\"REQUEST\"" + std::string(variant)));
    TEST_ASSERT_EQUAL_INT_MESSAGE(static_cast<int>(MessageType::Reaction),
                                  static_cast<int>(parsed.type), variant);
    TEST_ASSERT_EQUAL_UINT8_MESSAGE(1, parsed.reaction.repeats, variant);
    TEST_ASSERT_TRUE_MESSAGE(parsed.repeatsDefaulted, variant);
  }
}

void test_repeats_out_of_range_are_clamped() {
  TEST_ASSERT_EQUAL_UINT8(5, parse(reaction(",\"reaction\":\"WARM\",\"repeats\":9")).reaction.repeats);
  TEST_ASSERT_EQUAL_UINT8(1, parse(reaction(",\"reaction\":\"WARM\",\"repeats\":0")).reaction.repeats);
  TEST_ASSERT_EQUAL_UINT8(1, parse(reaction(",\"reaction\":\"WARM\",\"repeats\":-4")).reaction.repeats);
  TEST_ASSERT_EQUAL_UINT8(
      5, parse(reaction(",\"reaction\":\"WARM\",\"repeats\":9000000000")).reaction.repeats);
}

void test_oversized_frame_is_malformed() {
  std::string padded = reaction(",\"reaction\":\"WARM\",\"repeats\":3,\"pad\":\"" +
                                std::string(tip::kMaxFrameBytes, 'x') + "\"");
  assertMalformed(padded);
  // One byte over the limit is rejected before parsing, exactly at the limit it is parsed.
  std::string exact = reaction(",\"reaction\":\"WARM\",\"repeats\":3");
  exact.insert(exact.size() - 1, std::string(tip::kMaxFrameBytes - exact.size(), ' '));
  TEST_ASSERT_EQUAL_size_t(tip::kMaxFrameBytes, exact.size());
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Reaction), static_cast<int>(parse(exact).type));
  assertMalformed(exact.insert(exact.size() - 1, " "));
}

void test_long_kept_field_is_malformed() {
  assertMalformed(reaction(",\"reaction\":\"" + std::string(400, 'W') + "\",\"repeats\":3"));
}

void test_extra_fields_are_dropped_unread() {
  ParsedMessage parsed = parse(reaction(
      ",\"reaction\":\"PLAYFUL\",\"repeats\":2,\"message\":\"" + std::string(600, 'm') +
      "\",\"nested\":{\"a\":[1,2,{\"b\":3}]}"));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(MessageType::Reaction), static_cast<int>(parsed.type));
  TEST_ASSERT_EQUAL_UINT8(2, parsed.reaction.repeats);
}

void test_missing_fields_are_malformed() {
  assertMalformed("{\"id\":\"" + std::string(kId) + "\",\"reaction\":\"WARM\"}");
  assertMalformed("{\"type\":\"reaction\",\"reaction\":\"WARM\",\"repeats\":1}");
  assertMalformed(reaction(",\"repeats\":1"));
}

void test_wrong_types_are_malformed() {
  assertMalformed("{\"type\":7}");
  assertMalformed("{\"type\":\"reaction\",\"id\":42,\"reaction\":\"WARM\",\"repeats\":1}");
  assertMalformed(reaction(",\"reaction\":3,\"repeats\":1"));
  assertMalformed(reaction(",\"reaction\":null,\"repeats\":1"));
  assertMalformed("[\"reaction\"]");
  assertMalformed("\"reaction\"");
}

void test_bad_ids_are_malformed() {
  const char *ids[] = {"", "3F2C1B6E-8A4D-4C2B-9E1F-0A1B2C3D4E5F", "3f2c1b6e8a4d4c2b9e1f0a1b2c3d4e5f",
                       "3f2c1b6e-8a4d-4c2b-9e1f-0a1b2c3d4e5", "3f2c1b6e-8a4d-4c2b-9e1f-0a1b2c3d4e5f0",
                       "1-1-1-1-1", "3f2c1b6e-8a4d-4c2b-9e1f-0a1b2c3d4e5g", "\\\"},{\\\"x"};
  for (const char *id : ids) {
    assertMalformed("{\"type\":\"reaction\",\"id\":\"" + std::string(id) +
                    "\",\"reaction\":\"WARM\",\"repeats\":1}");
  }
}

void test_broken_json_is_malformed() {
  assertMalformed("");
  assertMalformed("{");
  assertMalformed("not json");
  assertMalformed(reaction(",\"reaction\":\"WARM\"").substr(0, 40));
  assertMalformed("{\"type\":\"reaction\",\"x\":[[[[[[[[1]]]]]]]]}");
}

void test_ack() {
  char out[64];
  size_t length = tip::buildAck(kId, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("{\"type\":\"ack\",\"id\":\"3f2c1b6e-8a4d-4c2b-9e1f-0a1b2c3d4e5f\"}", out);
  TEST_ASSERT_EQUAL_size_t(strlen(out), length);
  TEST_ASSERT_EQUAL_size_t(0, tip::buildAck("not-a-uuid", out, sizeof(out)));
  TEST_ASSERT_EQUAL_size_t(0, tip::buildAck(kId, out, 20));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_valid_reaction);
  RUN_TEST(test_each_known_kind);
  RUN_TEST(test_hello);
  RUN_TEST(test_closing_with_each_reason);
  RUN_TEST(test_closing_with_an_unknown_reason_is_still_closing);
  RUN_TEST(test_closing_without_a_string_reason_is_malformed);
  RUN_TEST(test_unknown_type_is_ignored);
  RUN_TEST(test_unknown_kind_plays_plain_once);
  RUN_TEST(test_repeats_missing_or_wrong_type_means_one);
  RUN_TEST(test_repeats_out_of_range_are_clamped);
  RUN_TEST(test_oversized_frame_is_malformed);
  RUN_TEST(test_long_kept_field_is_malformed);
  RUN_TEST(test_extra_fields_are_dropped_unread);
  RUN_TEST(test_missing_fields_are_malformed);
  RUN_TEST(test_wrong_types_are_malformed);
  RUN_TEST(test_bad_ids_are_malformed);
  RUN_TEST(test_broken_json_is_malformed);
  RUN_TEST(test_ack);
  return UNITY_END();
}
