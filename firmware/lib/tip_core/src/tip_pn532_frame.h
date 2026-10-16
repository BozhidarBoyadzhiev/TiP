// PN532 host link frames (PN532 user manual, section 6.2): building normal information frames to
// the PN532 and reading its ACK, NACK, error and information frames back byte by byte. No I/O
// here; src/pn532.cpp moves the bytes over HSU.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {
namespace pn532 {

// Transport results, the codes Seeed-Studio/PN532 uses, so logs compare with that library's.
constexpr int16_t kBadAck = -1;
constexpr int16_t kTimeout = -2;
constexpr int16_t kInvalidFrame = -3;
constexpr int16_t kNoSpace = -4;

// The ACK frame. Sent by the host, it aborts the command the PN532 is running.
constexpr uint8_t kAckFrame[] = {0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00};

// A normal frame carries at most 254 data bytes after its frame identifier.
constexpr size_t kMaxFrameData = 254;
// Preamble, start code, LEN, LCS, TFI, DCS and postamble around the data.
constexpr size_t kFrameOverhead = 8;

// Writes a host to PN532 frame (TFI D4) whose data is header then body. Returns its length, or 0
// when the data is longer than kMaxFrameData or the frame does not fit in capacity.
size_t buildFrame(const uint8_t *header, size_t headerLength, const uint8_t *body, size_t bodyLength,
                  uint8_t *out, size_t capacity);

// Finds frames in the bytes the PN532 sends: skips anything before a 00 FF start code, then checks
// the length and data checksums. Starts over after every complete frame.
class FrameReader {
 public:
  enum class Result : uint8_t {
    kNeedMore,
    kAck,
    kNack,
    // An information frame: tfi() and data() hold it.
    kFrame,
    // The PN532's application level error frame (TFI 7F).
    kError,
    // A wrong checksum, or an extended frame, which this link never asks for.
    kBad,
  };

  void reset() { state_ = State::kStart0; }
  Result feed(uint8_t byte);

  // After kFrame: the frame identifier and the data that follows it.
  uint8_t tfi() const { return body_[0]; }
  const uint8_t *data() const { return body_ + 1; }
  size_t length() const { return length_ - 1; }

 private:
  enum class State : uint8_t { kStart0, kStartFF, kLength, kLengthChecksum, kBody, kDataChecksum };

  Result finish(Result result);

  State state_ = State::kStart0;
  uint8_t length_ = 0;
  uint8_t received_ = 0;
  uint8_t sum_ = 0;
  uint8_t body_[255] = {0};
};

// The data of the PN532's answer to command: checks that the frame is PN532 to host (TFI D5) and
// answers command (response code command + 1), then copies what follows into out. Returns its
// length, kInvalidFrame for another frame, or kNoSpace when it is longer than capacity.
int16_t unpackResponse(const FrameReader &frame, uint8_t command, uint8_t *out, size_t capacity);

}  // namespace pn532
}  // namespace tip
