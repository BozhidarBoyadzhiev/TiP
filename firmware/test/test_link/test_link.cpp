#include <string.h>
#include <unity.h>

#include "tip_config.h"
#include "tip_link.h"

using tip::Backoff;
using tip::CertTime;
using tip::Validity;

void setUp() {}
void tearDown() {}

namespace {

const tip::BackoffConfig kConfig{tip::kBackoffFirstMs, tip::kBackoffCapMs, tip::kBackoffJitterPercent,
                                 tip::kStableAfterMs, tip::kReplacedWaitMs};

uint32_t zero(void *) { return 0; }

uint32_t maximum(void *) { return 0xFFFFFFFFu; }

// A fixed linear congruential sequence, so the jittered delays are the same on every run.
uint32_t seeded(void *context) {
  uint32_t *state = static_cast<uint32_t *>(context);
  *state = *state * 1664525u + 1013904223u;
  return *state;
}

}  // namespace

void test_backoff_doubles_from_one_second_to_the_60_second_cap() {
  Backoff backoff(kConfig, zero, nullptr);
  const uint32_t expected[] = {1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000, 60000};
  for (uint32_t delay : expected) {
    TEST_ASSERT_EQUAL_UINT32(delay, backoff.next(0));
  }
  for (int i = 0; i < 100; i++) {
    TEST_ASSERT_EQUAL_UINT32(60000, backoff.next(0));
  }
}

void test_backoff_jitter_shortens_by_at_most_20_percent() {
  Backoff backoff(kConfig, maximum, nullptr);
  const uint32_t expected[] = {801, 1601, 3201, 6401, 12801, 25601, 48001};
  for (uint32_t delay : expected) {
    TEST_ASSERT_EQUAL_UINT32(delay, backoff.next(0));
  }
}

void test_backoff_with_a_seeded_jitter_source() {
  uint32_t state = 42;
  Backoff backoff(kConfig, seeded, &state);
  const uint32_t steps[] = {1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000};
  uint32_t first[8];
  for (int i = 0; i < 8; i++) {
    first[i] = backoff.next(0);
    TEST_ASSERT_LESS_OR_EQUAL_UINT32(steps[i], first[i]);
    TEST_ASSERT_GREATER_OR_EQUAL_UINT32(steps[i] - steps[i] / 5, first[i]);
  }
  // The same seed gives the same delays.
  uint32_t again = 42;
  Backoff replay(kConfig, seeded, &again);
  for (int i = 0; i < 8; i++) {
    TEST_ASSERT_EQUAL_UINT32(first[i], replay.next(0));
  }
  // Not all equal to the undelayed steps: the jitter is applied.
  bool jittered = false;
  for (int i = 0; i < 8; i++) {
    jittered = jittered || first[i] != steps[i];
  }
  TEST_ASSERT_TRUE(jittered);
}

void test_backoff_resets_only_after_a_connection_stayed_up_60_seconds() {
  Backoff backoff(kConfig, zero, nullptr);
  TEST_ASSERT_EQUAL_UINT32(1000, backoff.next(0));
  TEST_ASSERT_EQUAL_UINT32(2000, backoff.next(0));
  TEST_ASSERT_EQUAL_UINT32(4000, backoff.next(0));
  // Accepted, then dropped after 59.999 s: no reset, the backoff keeps growing.
  TEST_ASSERT_EQUAL_UINT32(8000, backoff.next(59999));
  TEST_ASSERT_EQUAL_UINT32(16000, backoff.next(1000));
  // Up for a full minute: the next delay starts over.
  TEST_ASSERT_EQUAL_UINT32(1000, backoff.next(60000));
  TEST_ASSERT_EQUAL_UINT32(2000, backoff.next(0));
  TEST_ASSERT_EQUAL_UINT32(1000, backoff.next(3600000));
}

void test_replaced_waits_at_least_60_seconds() {
  TEST_ASSERT_EQUAL_UINT32(60000, tip::kReplacedWaitMs);
  Backoff backoff(kConfig, zero, nullptr);
  // Replaced after a long connection: the backoff starts over at 1 s, but the wait is a minute.
  TEST_ASSERT_EQUAL_UINT32(60000, backoff.nextAfterReplaced(3600000));
  TEST_ASSERT_EQUAL_UINT32(1, backoff.attempt());
  // Replaced again at once: still a minute, and the backoff keeps counting underneath.
  TEST_ASSERT_EQUAL_UINT32(60000, backoff.nextAfterReplaced(1000));
  TEST_ASSERT_EQUAL_UINT32(60000, backoff.nextAfterReplaced(1000));
  TEST_ASSERT_EQUAL_UINT32(8000, backoff.next(0));
}

void test_replaced_never_shortens_a_longer_backoff() {
  const tip::BackoffConfig longer{1000, 120000, 0, 60000, 60000};
  Backoff backoff(longer, zero, nullptr);
  for (int i = 0; i < 7; i++) {
    backoff.next(0);
  }
  TEST_ASSERT_EQUAL_UINT32(120000, backoff.nextAfterReplaced(0));
}

void test_silence_limit_is_45_seconds() {
  TEST_ASSERT_EQUAL_UINT32(45000, tip::kSilenceLimitMs);
  TEST_ASSERT_FALSE(tip::isSilent(144999, 100000, tip::kSilenceLimitMs));
  TEST_ASSERT_TRUE(tip::isSilent(145000, 100000, tip::kSilenceLimitMs));
  // Across the millis() wraparound.
  TEST_ASSERT_FALSE(tip::isSilent(1000, 0xFFFFF000u, tip::kSilenceLimitMs));
  TEST_ASSERT_TRUE(tip::isSilent(50000, 0xFFFFF000u, tip::kSilenceLimitMs));
}

void test_http_status_line() {
  const char *valid[] = {"HTTP/1.1 401 ", "HTTP/1.1 401\r\n", "HTTP/1.1 429 Too Many Requests", "HTTP/1.1 101",
                         "HTTP/1.0 403 Forbidden\r"};
  const int expected[] = {401, 401, 429, 101, 403};
  for (int i = 0; i < 5; i++) {
    TEST_ASSERT_EQUAL_INT_MESSAGE(expected[i], tip::httpStatus(valid[i], strlen(valid[i])), valid[i]);
  }
  const char *invalid[] = {"", "HTTP/1.1", "HTTP/1.1 40", "HTTP/1.1 4011", "HTTP/1.1 abc", "http/1.1 401",
                           "Header response timeout", "xHTTP/1.1 401"};
  for (const char *line : invalid) {
    TEST_ASSERT_EQUAL_INT_MESSAGE(0, tip::httpStatus(line, strlen(line)), line);
  }
  TEST_ASSERT_EQUAL_INT(0, tip::httpStatus(nullptr, 0));
}

void test_cert_time_from_unix() {
  CertTime epoch = tip::certTimeFromUnix(0);
  TEST_ASSERT_EQUAL_INT(1970, epoch.year);
  TEST_ASSERT_EQUAL_INT(1, epoch.mon);
  TEST_ASSERT_EQUAL_INT(1, epoch.day);
  CertTime clockSet = tip::certTimeFromUnix(tip::kClockSetAfter);
  TEST_ASSERT_EQUAL_INT(2025, clockSet.year);
  TEST_ASSERT_EQUAL_INT(1, clockSet.mon);
  TEST_ASSERT_EQUAL_INT(1, clockSet.day);
  TEST_ASSERT_EQUAL_INT(0, clockSet.hour);
  // 2028-02-29T23:59:59Z, a leap day.
  CertTime leap = tip::certTimeFromUnix(1835481599);
  TEST_ASSERT_EQUAL_INT(2028, leap.year);
  TEST_ASSERT_EQUAL_INT(2, leap.mon);
  TEST_ASSERT_EQUAL_INT(29, leap.day);
  TEST_ASSERT_EQUAL_INT(23, leap.hour);
  TEST_ASSERT_EQUAL_INT(59, leap.min);
  TEST_ASSERT_EQUAL_INT(59, leap.sec);
}

void test_certificate_validity_window() {
  CertTime notBefore{2026, 9, 1, 0, 0, 0};
  CertTime notAfter{2026, 11, 30, 23, 59, 59};
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Validity::Valid),
                        static_cast<int>(tip::checkValidity(notBefore, notAfter, {2026, 10, 12, 9, 0, 0})));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Validity::Valid),
                        static_cast<int>(tip::checkValidity(notBefore, notAfter, notBefore)));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Validity::Valid),
                        static_cast<int>(tip::checkValidity(notBefore, notAfter, notAfter)));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Validity::NotYetValid),
                        static_cast<int>(tip::checkValidity(notBefore, notAfter, {2026, 8, 31, 23, 59, 59})));
  TEST_ASSERT_EQUAL_INT(static_cast<int>(Validity::Expired),
                        static_cast<int>(tip::checkValidity(notBefore, notAfter, {2026, 12, 1, 0, 0, 0})));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_backoff_doubles_from_one_second_to_the_60_second_cap);
  RUN_TEST(test_backoff_jitter_shortens_by_at_most_20_percent);
  RUN_TEST(test_backoff_with_a_seeded_jitter_source);
  RUN_TEST(test_backoff_resets_only_after_a_connection_stayed_up_60_seconds);
  RUN_TEST(test_replaced_waits_at_least_60_seconds);
  RUN_TEST(test_replaced_never_shortens_a_longer_backoff);
  RUN_TEST(test_silence_limit_is_45_seconds);
  RUN_TEST(test_http_status_line);
  RUN_TEST(test_cert_time_from_unix);
  RUN_TEST(test_certificate_validity_window);
  return UNITY_END();
}
