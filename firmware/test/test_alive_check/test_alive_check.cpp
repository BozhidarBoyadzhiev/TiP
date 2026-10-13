#include <unity.h>

#include "tip_alive_check.h"
#include "tip_config.h"

using tip::AliveCheck;

void setUp() {}
void tearDown() {}

namespace {

AliveCheck check(uint32_t now, uint32_t timerStartedAt, bool fieldSeen, uint32_t lastFieldAt) {
  return tip::aliveCheck(now, timerStartedAt, fieldSeen, lastFieldAt, tip::kNfcAliveCheckMs,
                         tip::kNfcAliveSkipAfterFieldMs);
}

}  // namespace

void test_the_configured_interval_and_skip_window() {
  TEST_ASSERT_EQUAL_UINT32(30000, tip::kNfcAliveCheckMs);
  TEST_ASSERT_EQUAL_UINT32(5000, tip::kNfcAliveSkipAfterFieldMs);
}

void test_waits_until_the_interval_has_passed() {
  TEST_ASSERT_EQUAL(AliveCheck::kWait, check(1000, 1000, false, 0));
  TEST_ASSERT_EQUAL(AliveCheck::kWait, check(30999, 1000, false, 0));
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(31000, 1000, false, 0));
}

void test_probes_when_no_field_was_ever_seen() {
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(100000, 0, false, 99999));
}

void test_restarts_the_timer_after_a_field_in_the_last_5_seconds() {
  TEST_ASSERT_EQUAL(AliveCheck::kRestartTimer, check(31000, 1000, true, 31000));
  TEST_ASSERT_EQUAL(AliveCheck::kRestartTimer, check(31000, 1000, true, 26001));
}

void test_probes_once_the_field_is_5_seconds_old() {
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(31000, 1000, true, 26000));
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(31000, 1000, true, 0));
}

void test_a_recent_field_does_not_bring_the_check_forward() {
  TEST_ASSERT_EQUAL(AliveCheck::kWait, check(20000, 1000, true, 19000));
}

void test_across_the_wrap_of_millis() {
  uint32_t armed = 0xFFFFF000u;
  TEST_ASSERT_EQUAL(AliveCheck::kWait, check(armed + 29999, armed, false, 0));
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(armed + 30000, armed, false, 0));
  TEST_ASSERT_EQUAL(AliveCheck::kRestartTimer, check(armed + 30000, armed, true, armed + 27000));
  TEST_ASSERT_EQUAL(AliveCheck::kProbe, check(armed + 30000, armed, true, armed + 25000));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_the_configured_interval_and_skip_window);
  RUN_TEST(test_waits_until_the_interval_has_passed);
  RUN_TEST(test_probes_when_no_field_was_ever_seen);
  RUN_TEST(test_restarts_the_timer_after_a_field_in_the_last_5_seconds);
  RUN_TEST(test_probes_once_the_field_is_5_seconds_old);
  RUN_TEST(test_a_recent_field_does_not_bring_the_check_forward);
  RUN_TEST(test_across_the_wrap_of_millis);
  return UNITY_END();
}
