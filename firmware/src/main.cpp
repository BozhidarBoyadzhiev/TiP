// TiP panda firmware: connects to the server's device channel over verified wss, acks each
// reaction on receipt and plays its light pattern on the onboard LED, and has a PN532 emulate an
// NFC tag holding the panda's tip page. See firmware/DESIGN.md.
#include <Arduino.h>

#include "log.h"
#include "tasks.h"
#include "tip_config.h"
#include "tip_protocol.h"
#include "watchdog.h"

namespace {

void printChipInfo() {
  uint64_t mac = ESP.getEfuseMac();
  tip::logLine("Chip: %s rev v%u.%u, %u cores, flash %lu bytes, MAC %02X:%02X:%02X:%02X:%02X:%02X",
          ESP.getChipModel(), ESP.getChipRevision() / 100, ESP.getChipRevision() % 100,
          ESP.getChipCores(), static_cast<unsigned long>(ESP.getFlashChipSize()),
          static_cast<uint8_t>(mac), static_cast<uint8_t>(mac >> 8), static_cast<uint8_t>(mac >> 16),
          static_cast<uint8_t>(mac >> 24), static_cast<uint8_t>(mac >> 32),
          static_cast<uint8_t>(mac >> 40));
}

}  // namespace

void setup() {
  tip::logBegin();
  delay(500);
  tip::logLine("TiP panda firmware, device %s on %s", TIP_DEVICE_SLUG, TIP_SERVER_HOST);
  printChipInfo();
  tip::watchdogBegin();
  QueueHandle_t reactions = xQueueCreate(tip::kWaitingReactions, sizeof(tip::Reaction));
  tip::startPlaybackTask(reactions);
  tip::startNetworkTask(reactions);
  tip::startNfcTask();
}

// Everything runs in the network, playback and NFC tasks.
void loop() { vTaskDelete(nullptr); }
