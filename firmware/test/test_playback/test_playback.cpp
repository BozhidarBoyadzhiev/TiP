#include <stdio.h>
#include <unity.h>

#include "tip_config.h"
#include "tip_intake.h"
#include "tip_player.h"

using tip::Kind;
using tip::LinkStatus;
using tip::Reaction;

void setUp() {}
void tearDown() {}

namespace {

Reaction reactionNumber(int n, Kind kind = Kind::Plain, uint8_t repeats = 1) {
  Reaction reaction{};
  snprintf(reaction.id, sizeof(reaction.id), "00000000-0000-4000-8000-%012d", n);
  reaction.kind = kind;
  reaction.repeats = repeats;
  return reaction;
}

}  // namespace

void test_first_reaction_is_acked_and_played() {
  tip::Intake<tip::kRecentIds> intake;
  tip::IntakeDecision decision = intake.accept(reactionNumber(1));
  TEST_ASSERT_TRUE(decision.ack);
  TEST_ASSERT_TRUE(decision.play);
}

void test_duplicate_is_acked_but_not_played() {
  tip::Intake<tip::kRecentIds> intake;
  intake.accept(reactionNumber(1));
  tip::IntakeDecision repeat = intake.accept(reactionNumber(1));
  TEST_ASSERT_TRUE(repeat.ack);
  TEST_ASSERT_FALSE(repeat.play);
  TEST_ASSERT_FALSE(intake.accept(reactionNumber(1)).play);
}

void test_ring_remembers_the_last_16_ids() {
  TEST_ASSERT_EQUAL_UINT32(16, tip::kRecentIds);
  tip::Intake<tip::kRecentIds> intake;
  for (int n = 1; n <= 16; n++) {
    TEST_ASSERT_TRUE(intake.accept(reactionNumber(n)).play);
  }
  // All 16 are still known.
  for (int n = 1; n <= 16; n++) {
    TEST_ASSERT_FALSE(intake.accept(reactionNumber(n)).play);
  }
  // A 17th pushes the oldest out: id 1 plays again, id 2 to 17 do not.
  TEST_ASSERT_TRUE(intake.accept(reactionNumber(17)).play);
  TEST_ASSERT_TRUE(intake.accept(reactionNumber(1)).play);
  TEST_ASSERT_FALSE(intake.accept(reactionNumber(17)).play);
  TEST_ASSERT_FALSE(intake.accept(reactionNumber(3)).play);
}

void test_reactions_play_in_order_of_arrival() {
  tip::Player<tip::kWaitingReactions> player;
  player.enqueue(reactionNumber(1, Kind::Plain, 1), nullptr);
  player.enqueue(reactionNumber(2, Kind::Warm, 2), nullptr);
  tip::Player<tip::kWaitingReactions>::Tick tick = player.tick(1000, LinkStatus::Connected);
  TEST_ASSERT_NOT_NULL(tick.started);
  TEST_ASSERT_EQUAL_STRING(reactionNumber(1).id, tick.started->id);
  TEST_ASSERT_EQUAL_UINT8(255, tick.level);
  TEST_ASSERT_NULL(player.tick(1699, LinkStatus::Connected).started);
  tick = player.tick(1700, LinkStatus::Connected);
  TEST_ASSERT_NOT_NULL(tick.started);
  TEST_ASSERT_EQUAL_STRING(reactionNumber(2).id, tick.started->id);
  TEST_ASSERT_EQUAL_UINT8(2, tick.started->repeats);
  // WARM with repeats 2 runs 4.8 s, then the status pattern takes over again.
  TEST_ASSERT_TRUE(player.tick(1700 + 4799, LinkStatus::Connected).started == nullptr);
  TEST_ASSERT_TRUE(player.playing());
  player.tick(1700 + 4800, LinkStatus::Connected);
  TEST_ASSERT_FALSE(player.playing());
}

void test_overflow_drops_oldest_waiting_and_never_the_playing_one() {
  TEST_ASSERT_EQUAL_UINT32(8, tip::kWaitingReactions);
  tip::Player<tip::kWaitingReactions> player;
  player.enqueue(reactionNumber(1, Kind::Playful, 5), nullptr);
  player.tick(0, LinkStatus::Connected);
  TEST_ASSERT_TRUE(player.playing());

  Reaction dropped{};
  for (int n = 2; n <= 9; n++) {
    TEST_ASSERT_FALSE(player.enqueue(reactionNumber(n), &dropped));
  }
  TEST_ASSERT_EQUAL_size_t(8, player.waiting());
  TEST_ASSERT_TRUE(player.enqueue(reactionNumber(10), &dropped));
  TEST_ASSERT_EQUAL_STRING(reactionNumber(2).id, dropped.id);
  TEST_ASSERT_TRUE(player.enqueue(reactionNumber(11), &dropped));
  TEST_ASSERT_EQUAL_STRING(reactionNumber(3).id, dropped.id);
  TEST_ASSERT_EQUAL_size_t(8, player.waiting());

  // The playing reaction runs to its end untouched, then the waiting ones follow from 4 to 11.
  TEST_ASSERT_EQUAL_STRING(reactionNumber(1).id, player.current().id);
  TEST_ASSERT_NULL(player.tick(9169, LinkStatus::Connected).started);
  TEST_ASSERT_EQUAL_STRING(reactionNumber(1).id, player.current().id);
  uint32_t now = 9170;
  for (int n = 4; n <= 11; n++) {
    tip::Player<tip::kWaitingReactions>::Tick tick = player.tick(now, LinkStatus::Connected);
    TEST_ASSERT_NOT_NULL(tick.started);
    TEST_ASSERT_EQUAL_STRING(reactionNumber(n).id, tick.started->id);
    now += 700;
  }
  TEST_ASSERT_EQUAL_size_t(0, player.waiting());
}

void test_status_pattern_when_idle() {
  tip::Player<tip::kWaitingReactions> player;
  TEST_ASSERT_EQUAL_UINT8(255, player.tick(10, LinkStatus::Connecting).level);
  TEST_ASSERT_EQUAL_UINT8(0, player.tick(10, LinkStatus::Connected).level);
}

void test_playback_survives_millis_wraparound() {
  tip::Player<tip::kWaitingReactions> player;
  player.enqueue(reactionNumber(1, Kind::Plain, 1), nullptr);
  uint32_t start = 0xFFFFFF00u;
  player.tick(start, LinkStatus::Connected);
  TEST_ASSERT_TRUE(player.playing());
  TEST_ASSERT_EQUAL_UINT8(255, player.tick(start + 600, LinkStatus::Connected).level);
  player.tick(start + 700, LinkStatus::Connected);
  TEST_ASSERT_FALSE(player.playing());
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_first_reaction_is_acked_and_played);
  RUN_TEST(test_duplicate_is_acked_but_not_played);
  RUN_TEST(test_ring_remembers_the_last_16_ids);
  RUN_TEST(test_reactions_play_in_order_of_arrival);
  RUN_TEST(test_overflow_drops_oldest_waiting_and_never_the_playing_one);
  RUN_TEST(test_status_pattern_when_idle);
  RUN_TEST(test_playback_survives_millis_wraparound);
  return UNITY_END();
}
