// TiP panda firmware: connects to the server's device channel over verified wss, acks each
// reaction on receipt and plays its light pattern on the onboard LED. See firmware/README.md.
#include <Arduino.h>

#include "tasks.h"
#include "tip_config.h"
#include "tip_protocol.h"

namespace {

constexpr size_t kSerialTxBufferBytes = 2048;

void printChipInfo() {
  uint64_t mac = ESP.getEfuseMac();
  Serial.printf("Chip: %s rev v%u.%u, %u cores, flash %lu bytes, MAC %02X:%02X:%02X:%02X:%02X:%02X\n",
                ESP.getChipModel(), ESP.getChipRevision() / 100, ESP.getChipRevision() % 100,
                ESP.getChipCores(), static_cast<unsigned long>(ESP.getFlashChipSize()),
                static_cast<uint8_t>(mac), static_cast<uint8_t>(mac >> 8), static_cast<uint8_t>(mac >> 16),
                static_cast<uint8_t>(mac >> 24), static_cast<uint8_t>(mac >> 32),
                static_cast<uint8_t>(mac >> 40));
}

}  // namespace

void setup() {
  // Buffered, so a log line never holds a task for the time it takes to send it at 115200 baud.
  Serial.setTxBufferSize(kSerialTxBufferBytes);
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.printf("TiP panda firmware, device %s on %s\n", TIP_DEVICE_SLUG, TIP_SERVER_HOST);
  printChipInfo();
  QueueHandle_t reactions = xQueueCreate(tip::kWaitingReactions, sizeof(tip::Reaction));
  tip::startPlaybackTask(reactions);
  tip::startNetworkTask(reactions);
}

// Everything runs in the network and playback tasks.
void loop() { vTaskDelete(nullptr); }
