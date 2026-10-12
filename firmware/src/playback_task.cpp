#include "tasks.h"
#include "tip_config.h"
#include "tip_player.h"

namespace tip {

namespace {

constexpr uint8_t kLedPin = 2;
constexpr uint32_t kLedFrequencyHz = 5000;
constexpr uint8_t kLedResolutionBits = 8;

// Core 1 with the network task, above it in priority: the LED's 10 ms tick preempts a TLS
// handshake, so a pattern never stutters while the network side is busy.
constexpr BaseType_t kCore = 1;
constexpr UBaseType_t kPriority = 3;
constexpr uint32_t kStackBytes = 4096;

QueueHandle_t incoming;
Player<kWaitingReactions> player;

void playbackLoop(void *) {
  if (!ledcAttach(kLedPin, kLedFrequencyHz, kLedResolutionBits)) {
    Serial.println("LED: ledcAttach failed");
  }
  int lastLevel = -1;
  TickType_t wake = xTaskGetTickCount();
  for (;;) {
    Reaction reaction;
    while (xQueueReceive(incoming, &reaction, 0) == pdTRUE) {
      Reaction dropped;
      if (player.enqueue(reaction, &dropped)) {
        Serial.printf("LED: queue full, dropped waiting tip %s\n", dropped.id);
      }
    }
    Player<kWaitingReactions>::Tick tick = player.tick(millis(), linkStatus());
    if (tick.started != nullptr) {
      const Reaction &started = *tick.started;
      Serial.printf("LED: playing %s %s x%d (repeats %u, %.2f s) tip %s, %u waiting\n",
                    kindName(started.kind), patternFor(started.kind).label,
                    units(started.kind, started.repeats), started.repeats,
                    durationMs(started.kind, started.repeats) / 1000.0, started.id,
                    static_cast<unsigned>(player.waiting()));
    }
    if (tick.level != lastLevel) {
      ledcWrite(kLedPin, tick.level);
      lastLevel = tick.level;
    }
    vTaskDelayUntil(&wake, pdMS_TO_TICKS(kLedTickMs));
  }
}

}  // namespace

void startPlaybackTask(QueueHandle_t reactions) {
  incoming = reactions;
  xTaskCreatePinnedToCore(playbackLoop, "led", kStackBytes, nullptr, kPriority, nullptr, kCore);
}

}  // namespace tip
