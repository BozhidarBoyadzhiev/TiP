// The WiFi networks the panda knows and the order it tries them in after a scan.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {

constexpr size_t kMaxWifiNetworks = 4;

// One entry of TIP_WIFI_NETWORKS in secrets.h.
struct WifiNetwork {
  const char *ssid;
  const char *password;
};

// One access point a scan saw. A network with several access points shows up several times.
struct ScanResult {
  const char *ssid;
  int32_t rssi;
};

struct WifiCandidate {
  // Index into the configured list.
  uint8_t network;
  // False when no scan result matched and the network is tried blind (missed scan, hidden SSID).
  bool seen;
  // The best RSSI seen for it in dBm, 0 when not seen.
  int32_t rssi;
};

// True when every network has a non empty SSID. Used by the build to refuse a bad secrets.h.
constexpr bool wifiSsidsSet(const WifiNetwork *networks, size_t count) {
  for (size_t i = 0; i < count; i++) {
    if (networks[i].ssid == nullptr || networks[i].ssid[0] == '\0') {
      return false;
    }
  }
  return true;
}

// Fills `order` (room for kMaxWifiNetworks) with the networks to try and returns how many. The
// configured networks a scan saw come first, strongest first, ties in list order, each once with
// its best RSSI. When the scan saw none of them, every network is tried once in list order.
// Networks past kMaxWifiNetworks are ignored.
size_t chooseWifiOrder(const WifiNetwork *networks, size_t networkCount, const ScanResult *seen,
                       size_t seenCount, WifiCandidate *order);

}  // namespace tip
