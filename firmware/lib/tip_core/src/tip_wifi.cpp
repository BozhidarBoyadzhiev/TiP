#include "tip_wifi.h"

#include <string.h>

namespace tip {

namespace {

bool bestRssi(const char *ssid, const ScanResult *seen, size_t seenCount, int32_t *rssi) {
  bool found = false;
  for (size_t i = 0; i < seenCount; i++) {
    if (seen[i].ssid != nullptr && strcmp(seen[i].ssid, ssid) == 0 && (!found || seen[i].rssi > *rssi)) {
      *rssi = seen[i].rssi;
      found = true;
    }
  }
  return found;
}

}  // namespace

size_t chooseWifiOrder(const WifiNetwork *networks, size_t networkCount, const ScanResult *seen,
                       size_t seenCount, WifiCandidate *order) {
  if (networkCount > kMaxWifiNetworks) {
    networkCount = kMaxWifiNetworks;
  }
  size_t visible = 0;
  for (size_t i = 0; i < networkCount; i++) {
    int32_t rssi = 0;
    if (!bestRssi(networks[i].ssid, seen, seenCount, &rssi)) {
      continue;
    }
    // Insertion after every candidate at least as strong keeps ties in list order.
    size_t at = visible;
    while (at > 0 && order[at - 1].rssi < rssi) {
      order[at] = order[at - 1];
      at--;
    }
    order[at] = {static_cast<uint8_t>(i), true, rssi};
    visible++;
  }
  if (visible > 0) {
    return visible;
  }
  for (size_t i = 0; i < networkCount; i++) {
    order[i] = {static_cast<uint8_t>(i), false, 0};
  }
  return networkCount;
}

}  // namespace tip
