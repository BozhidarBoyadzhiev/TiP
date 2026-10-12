// Plays reactions one after another. A reaction that arrives while another plays waits in a queue
// of N; when the queue is full the oldest waiting one is dropped, never the one playing, so the
// newest tipper, who is probably standing there, still sees theirs.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "tip_patterns.h"
#include "tip_protocol.h"

namespace tip {

template <size_t N>
class Player {
 public:
  struct Tick {
    uint8_t level;
    // The reaction that started playing on this tick, or nullptr.
    const Reaction *started;
  };

  // Queues the reaction. Returns true and fills `dropped` when the oldest waiting one made room.
  bool enqueue(const Reaction &reaction, Reaction *dropped) {
    bool full = count_ == N;
    if (full) {
      if (dropped != nullptr) {
        *dropped = waiting_[head_];
      }
      head_ = (head_ + 1) % N;
      count_--;
    }
    waiting_[(head_ + count_) % N] = reaction;
    count_++;
    return full;
  }

  // Advances playback to `nowMs`: finishes the current reaction when its time is up and starts
  // the next waiting one. Returns the LED level, or the status level when nothing plays.
  Tick tick(uint32_t nowMs, LinkStatus status) {
    Tick result{0, nullptr};
    if (playing_ && nowMs - startedMs_ >= durationMs(current_.kind, current_.repeats)) {
      playing_ = false;
    }
    if (!playing_ && count_ > 0) {
      current_ = waiting_[head_];
      head_ = (head_ + 1) % N;
      count_--;
      playing_ = true;
      startedMs_ = nowMs;
      result.started = &current_;
    }
    result.level = playing_ ? levelAt(current_.kind, current_.repeats, nowMs - startedMs_)
                            : statusLevel(status, nowMs);
    return result;
  }

  bool playing() const { return playing_; }
  const Reaction &current() const { return current_; }
  size_t waiting() const { return count_; }

 private:
  Reaction waiting_[N] = {};
  size_t head_ = 0;
  size_t count_ = 0;
  Reaction current_ = {};
  bool playing_ = false;
  uint32_t startedMs_ = 0;
};

}  // namespace tip
