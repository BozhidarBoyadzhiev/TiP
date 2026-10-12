#include <unity.h>

#include "tip_patterns.h"

using tip::Kind;

void setUp() {}
void tearDown() {}

namespace {

// The protocol's table of total lengths in ms, by kind and repeats 1 to 5.
struct Expected {
  Kind kind;
  int units[5];
  uint32_t ms[5];
};

const Expected kTable[] = {
    {Kind::Warm, {3, 4, 5, 6, 7}, {3600, 4800, 6000, 7200, 8400}},
    {Kind::Playful, {2, 3, 4, 5, 6}, {2890, 4460, 6030, 7600, 9170}},
    {Kind::Request, {2, 3, 4, 5, 6}, {2500, 3900, 5300, 6700, 8100}},
    {Kind::Plain, {1, 2, 3, 4, 5}, {700, 1700, 2700, 3700, 4700}},
};

}  // namespace

void test_unit_lengths_match_the_virtual_panda() {
  TEST_ASSERT_EQUAL_UINT32(1200, tip::unitMs(Kind::Warm));
  TEST_ASSERT_EQUAL_UINT32(1320, tip::unitMs(Kind::Playful));
  TEST_ASSERT_EQUAL_UINT32(1100, tip::unitMs(Kind::Request));
  TEST_ASSERT_EQUAL_UINT32(700, tip::unitMs(Kind::Plain));
}

void test_units_and_duration_for_every_kind_and_repeats() {
  for (const Expected &row : kTable) {
    for (int repeats = 1; repeats <= 5; repeats++) {
      TEST_ASSERT_EQUAL_INT_MESSAGE(row.units[repeats - 1], tip::units(row.kind, repeats),
                                    tip::kindName(row.kind));
      TEST_ASSERT_EQUAL_UINT32_MESSAGE(row.ms[repeats - 1], tip::durationMs(row.kind, repeats),
                                       tip::kindName(row.kind));
    }
  }
}

void test_longest_pattern_is_playful_at_9170_ms() {
  uint32_t longest = 0;
  for (const Expected &row : kTable) {
    for (int repeats = 1; repeats <= 5; repeats++) {
      uint32_t ms = tip::durationMs(row.kind, repeats);
      longest = ms > longest ? ms : longest;
    }
  }
  TEST_ASSERT_EQUAL_UINT32(9170, longest);
  TEST_ASSERT_EQUAL_UINT32(9170, tip::durationMs(Kind::Playful, 5));
}

void test_repeats_are_clamped() {
  TEST_ASSERT_EQUAL_INT(1, tip::clampRepeats(0));
  TEST_ASSERT_EQUAL_INT(1, tip::clampRepeats(-3));
  TEST_ASSERT_EQUAL_INT(1, tip::clampRepeats(-9223372036854775807LL));
  TEST_ASSERT_EQUAL_INT(3, tip::clampRepeats(3));
  TEST_ASSERT_EQUAL_INT(5, tip::clampRepeats(6));
  TEST_ASSERT_EQUAL_INT(5, tip::clampRepeats(9223372036854775807LL));
  TEST_ASSERT_EQUAL_UINT32(tip::durationMs(Kind::Warm, 5), tip::durationMs(Kind::Warm, 99));
  TEST_ASSERT_EQUAL_UINT32(tip::durationMs(Kind::Warm, 1), tip::durationMs(Kind::Warm, 0));
}

void test_heartbeat_levels() {
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Warm, 1, 0));
  TEST_ASSERT_EQUAL_UINT8(127, tip::levelAt(Kind::Warm, 1, 200));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Warm, 1, 400));
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Warm, 1, 1100));
  // Second unit starts right after the first one's pause.
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Warm, 1, 1600));
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Warm, 1, 3600));
}

void test_giggle_peaks_and_gap() {
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Playful, 1, 60));
  TEST_ASSERT_EQUAL_UINT8(140, tip::levelAt(Kind::Playful, 1, 220 + 60));
  TEST_ASSERT_EQUAL_UINT8(110, tip::levelAt(Kind::Playful, 1, 3 * 220 + 60));
  TEST_ASSERT_EQUAL_UINT8(170, tip::levelAt(Kind::Playful, 1, 5 * 220 + 60));
  // 250 ms of darkness between units, then the next giggle.
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Playful, 1, 1320 + 100));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Playful, 1, 1570 + 60));
}

void test_noted_holds_and_plain_blinks() {
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Request, 1, 300));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Request, 1, 799));
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Request, 1, 1100 + 150));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Plain, 1, 0));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Plain, 1, 699));
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Plain, 1, 700));
  // Two blinks with 300 ms between them.
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Plain, 2, 850));
  TEST_ASSERT_EQUAL_UINT8(255, tip::levelAt(Kind::Plain, 2, 1000));
  TEST_ASSERT_EQUAL_UINT8(0, tip::levelAt(Kind::Plain, 2, 1700));
}

void test_status_patterns() {
  TEST_ASSERT_EQUAL_UINT8(255, tip::statusLevel(tip::LinkStatus::Connecting, 10));
  TEST_ASSERT_EQUAL_UINT8(0, tip::statusLevel(tip::LinkStatus::Connecting, 500));
  TEST_ASSERT_EQUAL_UINT8(255, tip::statusLevel(tip::LinkStatus::Connecting, 1010));
  TEST_ASSERT_EQUAL_UINT8(0, tip::statusLevel(tip::LinkStatus::Connected, 10));
  int flicks = 0;
  uint8_t previous = 0;
  for (uint32_t ms = 0; ms < 5000; ms++) {
    uint8_t level = tip::statusLevel(tip::LinkStatus::AuthRejected, ms);
    flicks += level > 0 && previous == 0 ? 1 : 0;
    previous = level;
  }
  TEST_ASSERT_EQUAL_INT(3, flicks);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_unit_lengths_match_the_virtual_panda);
  RUN_TEST(test_units_and_duration_for_every_kind_and_repeats);
  RUN_TEST(test_longest_pattern_is_playful_at_9170_ms);
  RUN_TEST(test_repeats_are_clamped);
  RUN_TEST(test_heartbeat_levels);
  RUN_TEST(test_giggle_peaks_and_gap);
  RUN_TEST(test_noted_holds_and_plain_blinks);
  RUN_TEST(test_status_patterns);
  return UNITY_END();
}
