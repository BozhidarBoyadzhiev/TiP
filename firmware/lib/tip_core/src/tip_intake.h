// What the network side does with a reaction that parsed: ack it always, play it only the first
// time. The server resends until it gets an ack and replays unacked reactions on connect, so a
// repeat means an earlier ack may have been lost: it is acked again, but not played again.
#pragma once

#include <stddef.h>
#include <string.h>

#include "tip_protocol.h"

namespace tip {

// The last N reaction ids, oldest overwritten first.
template <size_t N>
class RecentIds {
 public:
  bool contains(const char *id) const {
    for (size_t i = 0; i < count_; i++) {
      if (strncmp(ids_[i], id, kIdLength) == 0) {
        return true;
      }
    }
    return false;
  }

  void add(const char *id) {
    memcpy(ids_[next_], id, kIdLength);
    ids_[next_][kIdLength] = '\0';
    next_ = (next_ + 1) % N;
    if (count_ < N) {
      count_++;
    }
  }

  size_t size() const { return count_; }

 private:
  char ids_[N][kIdLength + 1] = {};
  size_t next_ = 0;
  size_t count_ = 0;
};

struct IntakeDecision {
  bool ack;
  bool play;
};

template <size_t N>
class Intake {
 public:
  IntakeDecision accept(const Reaction &reaction) {
    if (recent_.contains(reaction.id)) {
      return {true, false};
    }
    recent_.add(reaction.id);
    return {true, true};
  }

 private:
  RecentIds<N> recent_;
};

}  // namespace tip
