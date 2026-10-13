// The tag's answers to the APDU sequences the phones sent in brief 05c (the iPhone's below;
// Android's is the same without the repeated length read) and to reads past the selected file.
#include <string.h>
#include <unity.h>

#include "tip_ndef.h"
#include "tip_type4_tag.h"

using tip::Type4Tag;

namespace {

constexpr const char *kUrl = "https://tip-integrated-panda.onrender.com/t/demo-panda";
constexpr uint8_t kGuard = 0xEE;
constexpr size_t kGuardBytes = 16;

Type4Tag tag;
uint8_t message[Type4Tag::kMaxMessage];
size_t messageLength;
uint8_t reply[Type4Tag::kMaxReply + kGuardBytes];
size_t replyLength;

// Sends one APDU and checks the reply never runs past kMaxReply.
void send(const uint8_t *apdu, size_t length) {
  memset(reply, kGuard, sizeof(reply));
  replyLength = tag.respond(apdu, length, reply);
  TEST_ASSERT_LESS_OR_EQUAL_size_t(Type4Tag::kMaxReply, replyLength);
  for (size_t i = Type4Tag::kMaxReply; i < sizeof(reply); i++) {
    TEST_ASSERT_EQUAL_HEX8(kGuard, reply[i]);
  }
}

void expectStatus(uint16_t sw, size_t dataLength) {
  TEST_ASSERT_EQUAL_size_t(dataLength + 2, replyLength);
  TEST_ASSERT_EQUAL_HEX16(sw, static_cast<uint16_t>((reply[replyLength - 2] << 8) | reply[replyLength - 1]));
}

void selectCapabilityContainer() {
  const uint8_t apdu[] = {0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x03};
  send(apdu, sizeof(apdu));
  expectStatus(0x9000, 0);
}

void selectNdefFile() {
  const uint8_t apdu[] = {0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04};
  send(apdu, sizeof(apdu));
  expectStatus(0x9000, 0);
}

void readBinary(uint16_t offset, uint8_t le) {
  const uint8_t apdu[] = {0x00, 0xB0, static_cast<uint8_t>(offset >> 8), static_cast<uint8_t>(offset), le};
  send(apdu, sizeof(apdu));
}

}  // namespace

void setUp() {
  messageLength = tip::buildUriMessage(kUrl, message, sizeof(message));
  TEST_ASSERT_TRUE(tag.setNdef(message, messageLength));
  tag.startSession();
}

void tearDown() {}

void test_select_ndef_application() {
  const uint8_t apdu[] = {0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00};
  send(apdu, sizeof(apdu));
  expectStatus(0x9000, 0);
}

void test_select_capability_container() { selectCapabilityContainer(); }

void test_read_capability_container() {
  selectCapabilityContainer();
  readBinary(0, 15);
  expectStatus(0x9000, 15);
  const uint8_t expected[] = {0x00, 0x0F, 0x20, 0x00, 0x54, 0x00, 0xFF, 0x04,
                              0x06, 0xE1, 0x04, 0x00, 0x80, 0x00, 0xFF};
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected, reply, sizeof(expected));
}

void test_read_past_the_capability_container() {
  selectCapabilityContainer();
  readBinary(0, 16);
  expectStatus(0x6B00, 0);
}

void test_read_at_the_end_of_the_capability_container() {
  selectCapabilityContainer();
  readBinary(15, 1);
  expectStatus(0x6B00, 0);
}

void test_select_ndef_file() { selectNdefFile(); }

void test_read_the_message_length() {
  selectNdefFile();
  readBinary(0, 2);
  expectStatus(0x9000, 2);
  TEST_ASSERT_EQUAL_HEX8(0, reply[0]);
  TEST_ASSERT_EQUAL_HEX8(messageLength, reply[1]);
}

void test_read_the_message_length_again() {
  selectNdefFile();
  readBinary(0, 2);
  readBinary(0, 2);
  expectStatus(0x9000, 2);
  TEST_ASSERT_EQUAL_HEX8(messageLength, reply[1]);
}

void test_read_the_message() {
  selectNdefFile();
  readBinary(2, static_cast<uint8_t>(messageLength));
  expectStatus(0x9000, messageLength);
  TEST_ASSERT_EQUAL_HEX8_ARRAY(message, reply, messageLength);
}

void test_read_the_whole_ndef_file() {
  selectNdefFile();
  readBinary(0, 128);
  expectStatus(0x9000, 128);
}

void test_read_one_byte_past_the_ndef_file() {
  selectNdefFile();
  readBinary(1, 128);
  expectStatus(0x6B00, 0);
}

void test_read_the_largest_le_past_the_end() {
  selectNdefFile();
  readBinary(100, 0x54);
  expectStatus(0x6B00, 0);
}

void test_read_at_the_end_of_the_ndef_file() {
  selectNdefFile();
  readBinary(128, 1);
  expectStatus(0x6B00, 0);
}

void test_read_255_bytes() {
  selectNdefFile();
  readBinary(0, 255);
  expectStatus(0x6B00, 0);
}

void test_read_with_le_0_means_256_bytes() {
  selectNdefFile();
  readBinary(0, 0);
  expectStatus(0x6B00, 0);
}

void test_read_at_offset_ffff() {
  selectNdefFile();
  readBinary(0xFFFF, 16);
  expectStatus(0x6B00, 0);
}

void test_update_binary_is_refused() {
  selectNdefFile();
  const uint8_t apdu[] = {0x00, 0xD6, 0x00, 0x00, 0x01, 0x00};
  send(apdu, sizeof(apdu));
  expectStatus(0x6A81, 0);
}

void test_short_apdu() {
  const uint8_t apdu[] = {0x00, 0xB0};
  send(apdu, sizeof(apdu));
  expectStatus(0x6700, 0);
}

void test_a_new_session_selects_no_file() {
  selectNdefFile();
  tag.startSession();
  readBinary(0, 2);
  expectStatus(0x6A82, 0);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_select_ndef_application);
  RUN_TEST(test_select_capability_container);
  RUN_TEST(test_read_capability_container);
  RUN_TEST(test_read_past_the_capability_container);
  RUN_TEST(test_read_at_the_end_of_the_capability_container);
  RUN_TEST(test_select_ndef_file);
  RUN_TEST(test_read_the_message_length);
  RUN_TEST(test_read_the_message_length_again);
  RUN_TEST(test_read_the_message);
  RUN_TEST(test_read_the_whole_ndef_file);
  RUN_TEST(test_read_one_byte_past_the_ndef_file);
  RUN_TEST(test_read_the_largest_le_past_the_end);
  RUN_TEST(test_read_at_the_end_of_the_ndef_file);
  RUN_TEST(test_read_255_bytes);
  RUN_TEST(test_read_with_le_0_means_256_bytes);
  RUN_TEST(test_read_at_offset_ffff);
  RUN_TEST(test_update_binary_is_refused);
  RUN_TEST(test_short_apdu);
  RUN_TEST(test_a_new_session_selects_no_file);
  return UNITY_END();
}
