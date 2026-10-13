// The three application tasks. The network task owns WiFi, time and the WebSocket and acks
// reactions; the playback task alone owns the LED. A FreeRTOS queue of tip::Reaction carries
// reactions from the first to the second, and the network task never waits on it. The NFC task
// owns the PN532 and shares nothing with the other two.
#pragma once

#include <Arduino.h>

#include "tip_patterns.h"

namespace tip {

void startNetworkTask(QueueHandle_t reactions);
void startPlaybackTask(QueueHandle_t reactions);
void startNfcTask();

// What the status pattern shows while no reaction plays. Written by the network task.
LinkStatus linkStatus();

}  // namespace tip
