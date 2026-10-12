#include "log.h"

#include <Arduino.h>
#include <esp_log.h>
#include <stdarg.h>
#include <stdio.h>

namespace tip {

namespace {

constexpr unsigned long kBaud = 115200;
// Buffered, so a line rarely holds the mutex for the time it takes to send it at 115200 baud.
constexpr size_t kTxBufferBytes = 2048;
constexpr size_t kLineBytes = 256;

SemaphoreHandle_t mutex = nullptr;

// Formats into `line`, drops trailing line breaks, marks a cut line and adds one newline.
size_t formatLine(char (&line)[kLineBytes], const char *format, va_list args) {
  int length = vsnprintf(line, kLineBytes - 1, format, args);
  if (length < 0) {
    return 0;
  }
  size_t used = static_cast<size_t>(length) < kLineBytes - 2 ? static_cast<size_t>(length) : kLineBytes - 2;
  if (static_cast<size_t>(length) > used) {
    line[used - 3] = '.';
    line[used - 2] = '.';
    line[used - 1] = '.';
  }
  while (used > 0 && (line[used - 1] == '\n' || line[used - 1] == '\r')) {
    used--;
  }
  line[used++] = '\n';
  line[used] = '\0';
  return used;
}

void writeLine(const char *line, size_t length) {
  // Never block or take a mutex from an interrupt; such a line is dropped.
  if (mutex == nullptr || xPortInIsrContext()) {
    return;
  }
  xSemaphoreTake(mutex, portMAX_DELAY);
  Serial.write(reinterpret_cast<const uint8_t *>(line), length);
  xSemaphoreGive(mutex);
}

int espLog(const char *format, va_list args) {
  char line[kLineBytes];
  size_t length = formatLine(line, format, args);
  writeLine(line, length);
  return static_cast<int>(length);
}

}  // namespace

void logBegin() {
  Serial.setTxBufferSize(kTxBufferBytes);
  Serial.begin(kBaud);
  mutex = xSemaphoreCreateMutex();
  esp_log_set_vprintf(espLog);
}

void logLine(const char *format, ...) {
  char line[kLineBytes];
  va_list args;
  va_start(args, format);
  size_t length = formatLine(line, format, args);
  va_end(args);
  writeLine(line, length);
}

}  // namespace tip
