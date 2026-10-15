#include "watchdog.h"

#include <esp_system.h>
#include <esp_task_wdt.h>

#include "log.h"
#include "tip_config.h"

namespace tip {

namespace {

const char *resetReasonName(esp_reset_reason_t reason) {
  switch (reason) {
    case ESP_RST_POWERON:
      return "power on";
    case ESP_RST_EXT:
      return "external pin";
    case ESP_RST_SW:
      return "software restart";
    case ESP_RST_PANIC:
      return "panic";
    case ESP_RST_INT_WDT:
      return "interrupt watchdog";
    case ESP_RST_TASK_WDT:
      return "task watchdog";
    case ESP_RST_WDT:
      return "other watchdog";
    case ESP_RST_DEEPSLEEP:
      return "deep sleep";
    case ESP_RST_BROWNOUT:
      return "brownout";
    case ESP_RST_SDIO:
      return "SDIO";
    default:
      return "unknown";
  }
}

}  // namespace

void watchdogBegin() {
  esp_reset_reason_t reason = esp_reset_reason();
  logLine("Boot: reset reason %s (%d)", resetReasonName(reason), static_cast<int>(reason));
  esp_task_wdt_config_t config = {};
  config.timeout_ms = kTaskWatchdogMs;
  // Core 0's idle task stays watched, as in the core's default configuration.
  config.idle_core_mask = 1 << 0;
  config.trigger_panic = true;
  esp_err_t result = esp_task_wdt_reconfigure(&config);
  if (result == ESP_ERR_INVALID_STATE) {
    result = esp_task_wdt_init(&config);
  }
  if (result != ESP_OK) {
    logLine("Watchdog: setup failed (%d)", static_cast<int>(result));
    return;
  }
  logLine("Watchdog: %lu s for the net, led and nfc tasks", static_cast<unsigned long>(kTaskWatchdogMs / 1000));
}

void watchdogWatch() {
  esp_err_t result = esp_task_wdt_add(nullptr);
  if (result != ESP_OK) {
    logLine("Watchdog: could not watch task %s (%d)", pcTaskGetName(nullptr), static_cast<int>(result));
  }
}

void watchdogFeed() { esp_task_wdt_reset(); }

}  // namespace tip
