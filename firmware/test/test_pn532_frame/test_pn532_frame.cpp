#include <string.h>
#include <unity.h>

#include "tip_pn532_frame.h"

using tip::pn532::FrameReader;

void setUp() {}
void tearDown() {}

namespace {

using Result = FrameReader::Result;

// Feeds bytes and returns the first result that is not kNeedMore, or kNeedMore after the last byte.
// `used` says how many bytes that took.
Result feed(FrameReader &reader, const uint8_t *bytes, size_t length, size_t &used) {
  for (used = 0; used < length;) {
    Result result = reader.feed(bytes[used++]);
    if (result != Result::kNeedMore) {
      return result;
    }
  }
  return Result::kNeedMore;
}

Result feed(FrameReader &reader, const uint8_t *bytes, size_t length) {
  size_t used = 0;
  return feed(reader, bytes, length, used);
}

// GetFirmwareVersion's answer from the 05c link test: IC 0x32, firmware 1.6, support 0x07.
const uint8_t kVersionResponse[] = {0x00, 0x00, 0xFF, 0x06, 0xFA, 0xD5, 0x03, 0x32, 0x01, 0x06, 0x07, 0xE8, 0x00};

}  // namespace

void test_build_a_command_frame() {
  const uint8_t command[] = {0x02};
  uint8_t frame[16];
  size_t length = tip::pn532::buildFrame(command, sizeof(command), nullptr, 0, frame, sizeof(frame));
  const uint8_t expected[] = {0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00};
  TEST_ASSERT_EQUAL_size_t(sizeof(expected), length);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, frame, sizeof(expected));
}

void test_build_a_frame_from_header_and_body() {
  const uint8_t header[] = {0x8E};
  const uint8_t body[] = {0x90, 0x00};
  uint8_t frame[16];
  size_t length = tip::pn532::buildFrame(header, sizeof(header), body, sizeof(body), frame, sizeof(frame));
  // LEN 4 (TFI and three data bytes), DCS makes D4 + 8E + 90 + 00 + DCS zero.
  const uint8_t expected[] = {0x00, 0x00, 0xFF, 0x04, 0xFC, 0xD4, 0x8E, 0x90, 0x00, 0x0E, 0x00};
  TEST_ASSERT_EQUAL_size_t(sizeof(expected), length);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, frame, sizeof(expected));
}

void test_build_refuses_too_much_data_or_too_little_room() {
  uint8_t data[255] = {0};
  uint8_t frame[300];
  TEST_ASSERT_EQUAL_size_t(0, tip::pn532::buildFrame(data, 1, data + 1, 254, frame, sizeof(frame)));
  TEST_ASSERT_EQUAL_size_t(262, tip::pn532::buildFrame(data, 1, data + 1, 253, frame, sizeof(frame)));
  TEST_ASSERT_EQUAL_size_t(0, tip::pn532::buildFrame(data, 1, nullptr, 0, frame, 8));
}

void test_a_built_frame_reads_back() {
  const uint8_t header[] = {0x8E};
  uint8_t body[130];
  for (size_t i = 0; i < sizeof(body); i++) {
    body[i] = static_cast<uint8_t>(i * 7);
  }
  uint8_t frame[160];
  size_t length = tip::pn532::buildFrame(header, sizeof(header), body, sizeof(body), frame, sizeof(frame));
  FrameReader reader;
  size_t used = 0;
  TEST_ASSERT_EQUAL(Result::kFrame, feed(reader, frame, length, used));
  TEST_ASSERT_EQUAL_size_t(length - 1, used);
  TEST_ASSERT_EQUAL_HEX8(0xD4, reader.tfi());
  TEST_ASSERT_EQUAL_size_t(1 + sizeof(body), reader.length());
  TEST_ASSERT_EQUAL_HEX8(0x8E, reader.data()[0]);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(body, reader.data() + 1, sizeof(body));
}

void test_ack_and_nack() {
  FrameReader reader;
  TEST_ASSERT_EQUAL(Result::kAck, feed(reader, tip::pn532::kAckFrame, sizeof(tip::pn532::kAckFrame)));
  const uint8_t nack[] = {0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00};
  TEST_ASSERT_EQUAL(Result::kNack, feed(reader, nack, sizeof(nack)));
}

void test_the_application_error_frame() {
  FrameReader reader;
  const uint8_t error[] = {0x00, 0x00, 0xFF, 0x01, 0xFF, 0x7F, 0x81, 0x00};
  TEST_ASSERT_EQUAL(Result::kError, feed(reader, error, sizeof(error)));
}

void test_ack_then_response_in_one_stream_after_noise() {
  uint8_t stream[64];
  size_t at = 0;
  const uint8_t noise[] = {0x55, 0x00, 0x12, 0xFF, 0x00};
  memcpy(stream + at, noise, sizeof(noise));
  at += sizeof(noise);
  memcpy(stream + at, tip::pn532::kAckFrame, sizeof(tip::pn532::kAckFrame));
  at += sizeof(tip::pn532::kAckFrame);
  memcpy(stream + at, kVersionResponse, sizeof(kVersionResponse));
  at += sizeof(kVersionResponse);

  FrameReader reader;
  size_t used = 0;
  TEST_ASSERT_EQUAL(Result::kAck, feed(reader, stream, at, used));
  TEST_ASSERT_EQUAL(Result::kFrame, feed(reader, stream + used, at - used));
  uint8_t version[4];
  TEST_ASSERT_EQUAL_INT16(4, tip::pn532::unpackResponse(reader, 0x02, version, sizeof(version)));
  const uint8_t expected[] = {0x32, 0x01, 0x06, 0x07};
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, version, sizeof(expected));
}

void test_bad_checksums_and_extended_frames() {
  FrameReader reader;
  const uint8_t badLength[] = {0x00, 0x00, 0xFF, 0x06, 0xFB};
  TEST_ASSERT_EQUAL(Result::kBad, feed(reader, badLength, sizeof(badLength)));
  uint8_t badData[sizeof(kVersionResponse)];
  memcpy(badData, kVersionResponse, sizeof(badData));
  badData[8] ^= 0x01;
  TEST_ASSERT_EQUAL(Result::kBad, feed(reader, badData, sizeof(badData)));
  const uint8_t extended[] = {0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x01, 0x00, 0xFF};
  TEST_ASSERT_EQUAL(Result::kBad, feed(reader, extended, sizeof(extended)));
  // The reader starts over and still finds the next frame.
  TEST_ASSERT_EQUAL(Result::kFrame, feed(reader, kVersionResponse, sizeof(kVersionResponse)));
}

void test_a_partial_frame_waits_for_more() {
  FrameReader reader;
  TEST_ASSERT_EQUAL(Result::kNeedMore, feed(reader, kVersionResponse, 9));
  TEST_ASSERT_EQUAL(Result::kFrame, feed(reader, kVersionResponse + 9, sizeof(kVersionResponse) - 9));
}

void test_unpack_checks_direction_command_and_room() {
  FrameReader reader;
  feed(reader, kVersionResponse, sizeof(kVersionResponse));
  uint8_t out[4];
  TEST_ASSERT_EQUAL_INT16(tip::pn532::kInvalidFrame, tip::pn532::unpackResponse(reader, 0x14, out, sizeof(out)));
  TEST_ASSERT_EQUAL_INT16(tip::pn532::kNoSpace, tip::pn532::unpackResponse(reader, 0x02, out, 3));

  const uint8_t command[] = {0x02};
  uint8_t frame[16];
  size_t length = tip::pn532::buildFrame(command, sizeof(command), nullptr, 0, frame, sizeof(frame));
  feed(reader, frame, length);
  TEST_ASSERT_EQUAL_INT16(tip::pn532::kInvalidFrame, tip::pn532::unpackResponse(reader, 0x01, out, sizeof(out)));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_build_a_command_frame);
  RUN_TEST(test_build_a_frame_from_header_and_body);
  RUN_TEST(test_build_refuses_too_much_data_or_too_little_room);
  RUN_TEST(test_a_built_frame_reads_back);
  RUN_TEST(test_ack_and_nack);
  RUN_TEST(test_the_application_error_frame);
  RUN_TEST(test_ack_then_response_in_one_stream_after_noise);
  RUN_TEST(test_bad_checksums_and_extended_frames);
  RUN_TEST(test_a_partial_frame_waits_for_more);
  RUN_TEST(test_unpack_checks_direction_command_and_room);
  return UNITY_END();
}
