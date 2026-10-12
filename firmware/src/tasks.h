// The two application tasks. The network task owns WiFi, time and the WebSocket and acks
// reactions; the playback task alone owns the LED. A FreeRTOS queue of tip::Reaction carries
// reactions from the first to the second, and the network task never waits on it.
#pragma once

#include <Arduino.h>

#include "tip_patterns.h"

namespace tip {

void startNetworkTask(QueueHandle_t reactions);
void startPlaybackTask(QueueHandle_t reactions);

// What the status pattern shows while no reaction plays. Written by the network task.
LinkStatus linkStatus();

}  // namespace tip
