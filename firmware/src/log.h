// The one way anything reaches the serial port. Every task logs through logLine(), which formats
// the whole line into a local buffer and writes it under a mutex, so lines from different tasks
// never interleave. ESP-IDF's own log output (WiFi, TLS) is routed through the same path.
#pragma once

namespace tip {

// Opens the serial port, creates the mutex and routes ESP-IDF logging here. Call first in setup().
void logBegin();

// Logs one line; the newline is added. Lines longer than 255 characters are cut and end in "...".
void logLine(const char *format, ...) __attribute__((format(printf, 1, 2)));

}  // namespace tip
