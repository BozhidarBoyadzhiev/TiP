#include "tip_alive_check.h"

namespace tip {

AliveCheck aliveCheck(uint32_t now, uint32_t timerStartedAt, bool fieldSeen, uint32_t lastFieldAt,
                      uint32_t intervalMs, uint32_t recentFieldMs) {
  if (now - timerStartedAt < intervalMs) {
    return AliveCheck::kWait;
  }
  if (fieldSeen && now - lastFieldAt < recentFieldMs) {
    return AliveCheck::kRestartTimer;
  }
  return AliveCheck::kProbe;
}

}  // namespace tip
