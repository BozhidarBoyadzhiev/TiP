#include <string.h>
#include <unity.h>

#include "tip_config.h"
#include "tip_ndef.h"
#include "tip_type4_tag.h"

using tip::Type4Tag;

void setUp() {}
void tearDown() {}

namespace {

constexpr size_t kMaxUrl = tip::maxUriLength(Type4Tag::kMaxMessage);
char url[kMaxUrl + 16];

size_t build(const char *baseUrl, const char *slug) {
  memset(url, 0, sizeof(url));
  return tip::buildTipUrl(baseUrl, slug, kMaxUrl, url, sizeof(url));
}

}  // namespace

void test_the_configured_url_is_the_tip_page_and_fits_the_tag() {
  size_t length = build(TIP_PUBLIC_BASE_URL, TIP_DEVICE_SLUG);
  TEST_ASSERT_EQUAL_STRING("https://tip-integrated-panda.onrender.com/t/demo-panda", url);
  TEST_ASSERT_EQUAL_size_t(strlen(url), length);
  uint8_t message[Type4Tag::kMaxMessage];
  size_t messageLength = tip::buildUriMessage(url, message, sizeof(message));
  TEST_ASSERT_EQUAL_size_t(51, messageLength);
  Type4Tag tag;
  TEST_ASSERT_TRUE(tag.setNdef(message, messageLength));
}

void test_only_https_base_urls() {
  TEST_ASSERT_EQUAL_size_t(0, build("http://tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("HTTPS://tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("ftp://tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https:/tip.example", "demo-panda"));
}

void test_one_trailing_slash_is_dropped() {
  build("https://tip.example/", "demo-panda");
  TEST_ASSERT_EQUAL_STRING("https://tip.example/t/demo-panda", url);
  build("https://localhost:8443", "demo-panda");
  TEST_ASSERT_EQUAL_STRING("https://localhost:8443/t/demo-panda", url);
}

void test_base_urls_with_more_than_a_host_are_refused() {
  TEST_ASSERT_EQUAL_size_t(0, build("https://", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https:///", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example//", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example/x", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example?x=1", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example#x", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://user@tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://Tip.example", "demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip example", "demo-panda"));
}

void test_slugs_follow_the_device_slug_pattern() {
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "ab"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "Demo-panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "demo/../x"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "demo panda"));
  TEST_ASSERT_EQUAL_size_t(0, build("https://tip.example", "demo?k=1"));
  TEST_ASSERT_NOT_EQUAL(0, build("https://tip.example", "a-1"));
  TEST_ASSERT_NOT_EQUAL(0, build("https://tip.example", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
}

void test_a_url_longer_than_the_tag_holds_is_refused() {
  const char *slug = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  // "https://" + host + "/t/" + 40 character slug is exactly the longest URL when the host has 78.
  char base[8 + 79 + 1] = "https://";
  memset(base + 8, 'h', 78);
  base[8 + 78] = '\0';
  TEST_ASSERT_EQUAL_size_t(kMaxUrl, build(base, slug));
  uint8_t message[Type4Tag::kMaxMessage];
  size_t messageLength = tip::buildUriMessage(url, message, sizeof(message));
  TEST_ASSERT_EQUAL_size_t(Type4Tag::kMaxMessage, messageLength);
  Type4Tag tag;
  TEST_ASSERT_TRUE(tag.setNdef(message, messageLength));

  base[8 + 78] = 'h';
  base[8 + 79] = '\0';
  TEST_ASSERT_EQUAL_size_t(0, build(base, slug));
}

void test_a_url_longer_than_the_buffer_is_refused() {
  char small[20];
  TEST_ASSERT_EQUAL_size_t(0, tip::buildTipUrl("https://tip.example", "demo-panda", kMaxUrl, small, sizeof(small)));
  char exact[33];
  TEST_ASSERT_EQUAL_size_t(32, tip::buildTipUrl("https://tip.example", "demo-panda", kMaxUrl, exact, sizeof(exact)));
  TEST_ASSERT_EQUAL_STRING("https://tip.example/t/demo-panda", exact);
}

void test_uri_message_layout() {
  uint8_t message[64];
  size_t length = tip::buildUriMessage("https://tip.example/t/demo-panda", message, sizeof(message));
  const char *rest = "tip.example/t/demo-panda";
  TEST_ASSERT_EQUAL_size_t(5 + strlen(rest), length);
  const uint8_t header[] = {0xD1, 0x01, static_cast<uint8_t>(1 + strlen(rest)), 'U', 0x04};
  TEST_ASSERT_EQUAL_HEX8_ARRAY(header, message, sizeof(header));
  TEST_ASSERT_EQUAL_MEMORY(rest, message + 5, strlen(rest));
}

void test_uri_message_needs_https_and_room() {
  uint8_t message[64];
  TEST_ASSERT_EQUAL_size_t(0, tip::buildUriMessage("http://tip.example/t/demo-panda", message, sizeof(message)));
  TEST_ASSERT_EQUAL_size_t(0, tip::buildUriMessage("https://tip.example/t/demo-panda", message, 28));
  TEST_ASSERT_EQUAL_size_t(29, tip::buildUriMessage("https://tip.example/t/demo-panda", message, 29));
}

// A short record's payload length is one byte: a longer URL is refused, never wrapped.
void test_uri_message_refuses_a_payload_over_255_bytes() {
  char longUrl[301];
  memcpy(longUrl, "https://", 8);
  memset(longUrl + 8, 'a', sizeof(longUrl) - 9);
  longUrl[sizeof(longUrl) - 1] = 0;
  uint8_t message[512];
  TEST_ASSERT_EQUAL_size_t(0, tip::buildUriMessage(longUrl, message, sizeof(message)));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_the_configured_url_is_the_tip_page_and_fits_the_tag);
  RUN_TEST(test_only_https_base_urls);
  RUN_TEST(test_one_trailing_slash_is_dropped);
  RUN_TEST(test_base_urls_with_more_than_a_host_are_refused);
  RUN_TEST(test_slugs_follow_the_device_slug_pattern);
  RUN_TEST(test_a_url_longer_than_the_tag_holds_is_refused);
  RUN_TEST(test_a_url_longer_than_the_buffer_is_refused);
  RUN_TEST(test_uri_message_layout);
  RUN_TEST(test_uri_message_needs_https_and_room);
  RUN_TEST(test_uri_message_refuses_a_payload_over_255_bytes);
  return UNITY_END();
}
