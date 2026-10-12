#include "tip_link.h"

#include <string.h>

namespace tip {

uint32_t Backoff::next(uint32_t connectedForMs) {
  if (connectedForMs >= config_.stableAfterMs) {
    attempt_ = 0;
  }
  uint32_t step = config_.capMs;
  // Past 2^16 times the first delay the cap has long been reached; the bound keeps the shift safe.
  if (attempt_ < 16) {
    uint64_t doubled = static_cast<uint64_t>(config_.firstMs) << attempt_;
    if (doubled < config_.capMs) {
      step = static_cast<uint32_t>(doubled);
    }
  }
  attempt_++;
  uint64_t maxJitter = static_cast<uint64_t>(step) * config_.jitterPercent / 100;
  uint64_t jitter = (maxJitter * random_(context_)) >> 32;
  return step - static_cast<uint32_t>(jitter);
}

uint32_t Backoff::nextAfterReplaced(uint32_t connectedForMs) {
  uint32_t delay = next(connectedForMs);
  return delay < config_.replacedWaitMs ? config_.replacedWaitMs : delay;
}

int httpStatus(const char *line, size_t length) {
  static const char kPrefix[] = "HTTP/";
  const size_t prefixLength = sizeof(kPrefix) - 1;
  if (line == nullptr || length < prefixLength || memcmp(line, kPrefix, prefixLength) != 0) {
    return 0;
  }
  size_t i = prefixLength;
  while (i < length && line[i] != ' ') {
    i++;
  }
  i++;
  if (i + 3 > length) {
    return 0;
  }
  int status = 0;
  for (size_t j = i; j < i + 3; j++) {
    if (line[j] < '0' || line[j] > '9') {
      return 0;
    }
    status = status * 10 + (line[j] - '0');
  }
  char after = i + 3 < length ? line[i + 3] : ' ';
  return after == ' ' || after == '\r' || after == '\n' ? status : 0;
}

CertTime certTimeFromUnix(int64_t seconds) {
  int64_t days = seconds / 86400;
  int64_t rest = seconds % 86400;
  if (rest < 0) {
    rest += 86400;
    days--;
  }
  // Civil from days, Howard Hinnant's algorithm.
  days += 719468;
  int64_t era = (days >= 0 ? days : days - 146096) / 146097;
  int64_t dayOfEra = days - era * 146097;
  int64_t yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
  int64_t dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
  int64_t mp = (5 * dayOfYear + 2) / 153;
  int day = static_cast<int>(dayOfYear - (153 * mp + 2) / 5 + 1);
  int month = static_cast<int>(mp < 10 ? mp + 3 : mp - 9);
  int year = static_cast<int>(yearOfEra + era * 400 + (month <= 2 ? 1 : 0));
  return CertTime{year,
                  month,
                  day,
                  static_cast<int>(rest / 3600),
                  static_cast<int>(rest % 3600 / 60),
                  static_cast<int>(rest % 60)};
}

int compareCertTime(const CertTime &a, const CertTime &b) {
  const int left[] = {a.year, a.mon, a.day, a.hour, a.min, a.sec};
  const int right[] = {b.year, b.mon, b.day, b.hour, b.min, b.sec};
  for (size_t i = 0; i < 6; i++) {
    if (left[i] != right[i]) {
      return left[i] < right[i] ? -1 : 1;
    }
  }
  return 0;
}

Validity checkValidity(const CertTime &notBefore, const CertTime &notAfter, const CertTime &now) {
  if (compareCertTime(now, notBefore) < 0) {
    return Validity::NotYetValid;
  }
  if (compareCertTime(now, notAfter) > 0) {
    return Validity::Expired;
  }
  return Validity::Valid;
}

}  // namespace tip
