#include <unity.h>

#include "tip_wifi.h"

using tip::ScanResult;
using tip::WifiCandidate;
using tip::WifiNetwork;

void setUp() {}
void tearDown() {}

namespace {

const WifiNetwork kNetworks[] = {{"home", "p1"}, {"shop", "p2"}, {"phone", "p3"}, {"market", "p4"}};
constexpr size_t kCount = sizeof(kNetworks) / sizeof(kNetworks[0]);

WifiCandidate order[tip::kMaxWifiNetworks];

size_t choose(const ScanResult *seen, size_t seenCount) {
  return tip::chooseWifiOrder(kNetworks, kCount, seen, seenCount, order);
}

void assertCandidate(size_t at, uint8_t network, bool seen, int32_t rssi) {
  TEST_ASSERT_EQUAL_UINT8(network, order[at].network);
  TEST_ASSERT_EQUAL(seen, order[at].seen);
  TEST_ASSERT_EQUAL_INT32(rssi, order[at].rssi);
}

}  // namespace

void test_visible_known_networks_strongest_first() {
  const ScanResult seen[] = {{"shop", -70}, {"market", -50}, {"home", -60}};
  TEST_ASSERT_EQUAL_size_t(3, choose(seen, 3));
  assertCandidate(0, 3, true, -50);
  assertCandidate(1, 0, true, -60);
  assertCandidate(2, 1, true, -70);
}

void test_known_networks_not_seen_are_left_out_while_others_are_seen() {
  const ScanResult seen[] = {{"phone", -80}};
  TEST_ASSERT_EQUAL_size_t(1, choose(seen, 1));
  assertCandidate(0, 2, true, -80);
}

void test_unknown_networks_are_ignored() {
  const ScanResult seen[] = {{"cafe", -30}, {"shop", -75}, {"airport", -40}};
  TEST_ASSERT_EQUAL_size_t(1, choose(seen, 3));
  assertCandidate(0, 1, true, -75);
}

void test_ties_keep_list_order() {
  const ScanResult seen[] = {{"market", -60}, {"shop", -60}, {"home", -60}, {"phone", -45}};
  TEST_ASSERT_EQUAL_size_t(4, choose(seen, 4));
  assertCandidate(0, 2, true, -45);
  assertCandidate(1, 0, true, -60);
  assertCandidate(2, 1, true, -60);
  assertCandidate(3, 3, true, -60);
}

void test_an_ssid_seen_more_than_once_counts_once_with_its_best_rssi() {
  const ScanResult seen[] = {{"home", -85}, {"shop", -65}, {"home", -55}, {"home", -70}};
  TEST_ASSERT_EQUAL_size_t(2, choose(seen, 4));
  assertCandidate(0, 0, true, -55);
  assertCandidate(1, 1, true, -65);
}

void test_ssids_match_exactly() {
  const ScanResult seen[] = {{"Home", -40}, {"home-5g", -40}, {"hom", -40}, {nullptr, -40}};
  TEST_ASSERT_EQUAL_size_t(kCount, choose(seen, 4));
  for (size_t i = 0; i < kCount; i++) {
    assertCandidate(i, static_cast<uint8_t>(i), false, 0);
  }
}

void test_none_visible_tries_every_network_once_in_list_order() {
  const ScanResult seen[] = {{"cafe", -30}};
  TEST_ASSERT_EQUAL_size_t(kCount, choose(seen, 1));
  for (size_t i = 0; i < kCount; i++) {
    assertCandidate(i, static_cast<uint8_t>(i), false, 0);
  }
}

void test_an_empty_scan_tries_every_network_once_in_list_order() {
  TEST_ASSERT_EQUAL_size_t(kCount, choose(nullptr, 0));
  for (size_t i = 0; i < kCount; i++) {
    assertCandidate(i, static_cast<uint8_t>(i), false, 0);
  }
}

void test_a_single_network() {
  const WifiNetwork one[] = {{"home", "p1"}};
  const ScanResult seen[] = {{"home", -66}};
  TEST_ASSERT_EQUAL_size_t(1, tip::chooseWifiOrder(one, 1, seen, 1, order));
  assertCandidate(0, 0, true, -66);
  TEST_ASSERT_EQUAL_size_t(1, tip::chooseWifiOrder(one, 1, nullptr, 0, order));
  assertCandidate(0, 0, false, 0);
}

void test_networks_past_the_fourth_are_ignored() {
  const WifiNetwork five[] = {{"a", ""}, {"b", ""}, {"c", ""}, {"d", ""}, {"e", ""}};
  const ScanResult seen[] = {{"e", -20}};
  TEST_ASSERT_EQUAL_size_t(tip::kMaxWifiNetworks, tip::chooseWifiOrder(five, 5, seen, 1, order));
  assertCandidate(3, 3, false, 0);
}

void test_ssids_set() {
  const WifiNetwork empty[] = {{"home", "p1"}, {"", "p2"}};
  const WifiNetwork missing[] = {{nullptr, "p1"}};
  TEST_ASSERT_TRUE(tip::wifiSsidsSet(kNetworks, kCount));
  TEST_ASSERT_FALSE(tip::wifiSsidsSet(empty, 2));
  TEST_ASSERT_FALSE(tip::wifiSsidsSet(missing, 1));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_visible_known_networks_strongest_first);
  RUN_TEST(test_known_networks_not_seen_are_left_out_while_others_are_seen);
  RUN_TEST(test_unknown_networks_are_ignored);
  RUN_TEST(test_ties_keep_list_order);
  RUN_TEST(test_an_ssid_seen_more_than_once_counts_once_with_its_best_rssi);
  RUN_TEST(test_ssids_match_exactly);
  RUN_TEST(test_none_visible_tries_every_network_once_in_list_order);
  RUN_TEST(test_an_empty_scan_tries_every_network_once_in_list_order);
  RUN_TEST(test_a_single_network);
  RUN_TEST(test_networks_past_the_fourth_are_ignored);
  RUN_TEST(test_ssids_set);
  return UNITY_END();
}
