// When the NFC task stops its armed tag to check that the PN532 still answers.
#pragma once

#include <stdint.h>

namespace tip {

enum class AliveCheck : uint8_t { kWait, kProbe, kRestartTimer };

// timerStartedAt is when the tag became ready or the timer last restarted; lastFieldAt is when a phone's
// field was last seen, meaningless while fieldSeen is false. The check is due intervalMs after the
// timer started. When it is due and a field was seen less than recentFieldMs ago, the timer restarts
// instead, so a phone that may be about to tap again never finds the tag stopped. Safe across the
// wrap of millis().
AliveCheck aliveCheck(uint32_t now, uint32_t timerStartedAt, bool fieldSeen, uint32_t lastFieldAt,
                      uint32_t intervalMs, uint32_t recentFieldMs);

}  // namespace tip
