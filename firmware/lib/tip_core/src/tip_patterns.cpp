#include "tip_patterns.h"

namespace tip {

namespace {

// Heartbeat: fade up over 400 ms, down over 600 ms, pause 200 ms.
constexpr Step kHeartbeat[] = {{400, 0, 255}, {600, 255, 0}, {200, 0, 0}};

// Giggle: six flicks with peaks 255, 140, 230, 110, 255, 170, each rising over 60 ms,
// falling over 90 ms and followed by a 70 ms gap.
constexpr Step kGiggle[] = {
    {60, 0, 255}, {90, 255, 0}, {70, 0, 0}, {60, 0, 140}, {90, 140, 0}, {70, 0, 0},
    {60, 0, 230}, {90, 230, 0}, {70, 0, 0}, {60, 0, 110}, {90, 110, 0}, {70, 0, 0},
    {60, 0, 255}, {90, 255, 0}, {70, 0, 0}, {60, 0, 170}, {90, 170, 0}, {70, 0, 0},
};

// Noted: fade up over 300 ms, hold 500 ms, fade down over 300 ms.
constexpr Step kNoted[] = {{300, 0, 255}, {500, 255, 255}, {300, 255, 0}};

// One solid blink of 700 ms.
constexpr Step kBlink[] = {{700, 255, 255}};

constexpr Pattern kPatterns[] = {
    {"heartbeat", kHeartbeat, sizeof(kHeartbeat) / sizeof(Step), 0, 3},
    {"giggle", kGiggle, sizeof(kGiggle) / sizeof(Step), 250, 2},
    {"noted", kNoted, sizeof(kNoted) / sizeof(Step), 300, 2},
    {"blink", kBlink, sizeof(kBlink) / sizeof(Step), 300, 1},
};

constexpr const char *kNames[] = {"WARM", "PLAYFUL", "REQUEST", "PLAIN"};

constexpr uint32_t kFlickPeriodMs = 1000;
constexpr uint32_t kFlickMs = 30;
constexpr uint32_t kRejectedPeriodMs = 5000;
constexpr uint32_t kRejectedFlicks = 3;
constexpr uint32_t kRejectedFlickMs = 60;
constexpr uint32_t kRejectedFlickGapMs = 120;

}  // namespace

const Pattern &patternFor(Kind kind) { return kPatterns[static_cast<uint8_t>(kind)]; }

const char *kindName(Kind kind) { return kNames[static_cast<uint8_t>(kind)]; }

int clampRepeats(long long repeats) {
  if (repeats < kMinRepeats) {
    return kMinRepeats;
  }
  if (repeats > kMaxRepeats) {
    return kMaxRepeats;
  }
  return static_cast<int>(repeats);
}

int units(Kind kind, int repeats) { return patternFor(kind).baseUnits + clampRepeats(repeats) - 1; }

uint32_t unitMs(Kind kind) {
  const Pattern &pattern = patternFor(kind);
  uint32_t total = 0;
  for (size_t i = 0; i < pattern.unitSteps; i++) {
    total += pattern.unit[i].ms;
  }
  return total;
}

uint32_t durationMs(Kind kind, int repeats) {
  uint32_t count = static_cast<uint32_t>(units(kind, repeats));
  return count * unitMs(kind) + (count - 1) * patternFor(kind).gapMs;
}

uint8_t levelAt(Kind kind, int repeats, uint32_t elapsedMs) {
  const Pattern &pattern = patternFor(kind);
  uint32_t unit = unitMs(kind);
  uint32_t period = unit + pattern.gapMs;
  if (elapsedMs >= durationMs(kind, repeats)) {
    return 0;
  }
  uint32_t within = elapsedMs % period;
  if (within >= unit) {
    return 0;
  }
  for (size_t i = 0; i < pattern.unitSteps; i++) {
    const Step &step = pattern.unit[i];
    if (within < step.ms) {
      int32_t span = static_cast<int32_t>(step.to) - static_cast<int32_t>(step.from);
      return static_cast<uint8_t>(step.from + span * static_cast<int32_t>(within) / step.ms);
    }
    within -= step.ms;
  }
  return 0;
}

uint8_t statusLevel(LinkStatus status, uint32_t nowMs) {
  switch (status) {
    case LinkStatus::Connecting:
      return nowMs % kFlickPeriodMs < kFlickMs ? 255 : 0;
    case LinkStatus::AuthRejected: {
      uint32_t within = nowMs % kRejectedPeriodMs;
      uint32_t slot = kRejectedFlickMs + kRejectedFlickGapMs;
      return within < kRejectedFlicks * slot && within % slot < kRejectedFlickMs ? 255 : 0;
    }
    case LinkStatus::Connected:
    default:
      return 0;
  }
}

}  // namespace tip
