// The LED patterns of docs/DEVICE_PROTOCOL.md, with the step timings of the virtual panda
// (static/js/panda.js), so the physical and the virtual panda look the same.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {

enum class Kind : uint8_t { Warm, Playful, Request, Plain };

constexpr int kMinRepeats = 1;
constexpr int kMaxRepeats = 5;

// One straight line of a unit: over `ms` the duty goes from `from` to `to` (0 to 255).
struct Step {
  uint16_t ms;
  uint8_t from;
  uint8_t to;
};

struct Pattern {
  const char *label;
  const Step *unit;
  size_t unitSteps;
  uint16_t gapMs;
  uint8_t baseUnits;
};

const Pattern &patternFor(Kind kind);
const char *kindName(Kind kind);

// Values outside 1 to 5 are clamped, not rejected.
int clampRepeats(long long repeats);

// base + (repeats - 1) units, with repeats clamped.
int units(Kind kind, int repeats);
uint32_t unitMs(Kind kind);
uint32_t durationMs(Kind kind, int repeats);

// The PWM duty (0 to 255) at `elapsedMs` into the pattern; 0 in the gaps and after the end.
uint8_t levelAt(Kind kind, int repeats, uint32_t elapsedMs);

// Status patterns, only when no reaction is playing.
enum class LinkStatus : uint8_t { Connecting, Connected, AuthRejected };

// Connecting: a 30 ms flick every second. Connected: off.
// Auth rejected: three quick flicks every 5 seconds.
uint8_t statusLevel(LinkStatus status, uint32_t nowMs);

}  // namespace tip
