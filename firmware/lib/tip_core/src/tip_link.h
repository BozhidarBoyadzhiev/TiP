// Connection policy of the device channel: reconnect backoff, the dead connection check, the
// handshake status and the certificate validity check.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {

struct BackoffConfig {
  uint32_t firstMs;
  uint32_t capMs;
  uint32_t jitterPercent;
  uint32_t stableAfterMs;
  // The shortest wait after the server closed this connection because another one replaced it.
  uint32_t replacedWaitMs;
  // The wait after the server rejected the device's credentials (HTTP 401).
  uint32_t authRetryMs;
};

// Exponential backoff: firstMs, doubling up to capMs, each delay shortened by up to jitterPercent
// at random, so it never exceeds the cap. It starts over only after a connection that stayed up
// stableAfterMs, so a server that accepts and drops at once cannot cause a tight loop.
class Backoff {
 public:
  // Returns a uniformly random 32 bit value.
  using Random = uint32_t (*)(void *context);

  Backoff(const BackoffConfig &config, Random random, void *context)
      : config_(config), random_(random), context_(context) {}

  // The delay before the next attempt. `connectedForMs` is how long the connection that just ended
  // was up, 0 for an attempt that never connected.
  uint32_t next(uint32_t connectedForMs);

  // Like next(), but at least replacedWaitMs: another client with the same identity took over, and
  // reconnecting at once would only replace it back. Both then settle instead of fighting.
  uint32_t nextAfterReplaced(uint32_t connectedForMs);

  // The fixed wait after a 401. Wrong credentials stay wrong until someone fixes them, so retrying
  // sooner only fills the server's failed handshake limit; the backoff itself is left untouched.
  uint32_t afterAuthRejected() const { return config_.authRetryMs; }

  uint32_t attempt() const { return attempt_; }

 private:
  BackoffConfig config_;
  Random random_;
  void *context_;
  uint32_t attempt_ = 0;
};

// True when nothing has arrived for `limitMs` or longer. Wraps correctly with millis().
inline bool isSilent(uint32_t nowMs, uint32_t lastReceivedMs, uint32_t limitMs) {
  return nowMs - lastReceivedMs >= limitMs;
}

// The status code of an HTTP status line such as "HTTP/1.1 401 Unauthorized", or 0 when the text
// is not one.
int httpStatus(const char *line, size_t length);

// A certificate date in UTC, as X.509 stores it.
struct CertTime {
  int year, mon, day, hour, min, sec;
};

enum class Validity : uint8_t { Valid, NotYetValid, Expired };

CertTime certTimeFromUnix(int64_t seconds);
int compareCertTime(const CertTime &a, const CertTime &b);
Validity checkValidity(const CertTime &notBefore, const CertTime &notAfter, const CertTime &now);

}  // namespace tip
