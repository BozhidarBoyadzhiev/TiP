#include "tip_pn532_frame.h"

#include <string.h>

namespace tip {
namespace pn532 {

namespace {

constexpr uint8_t kHostToPn532 = 0xD4;
constexpr uint8_t kPn532ToHost = 0xD5;
constexpr uint8_t kErrorTfi = 0x7F;

}  // namespace

size_t buildFrame(const uint8_t *header, size_t headerLength, const uint8_t *body, size_t bodyLength,
                  uint8_t *out, size_t capacity) {
  size_t dataLength = headerLength + bodyLength;
  if (dataLength > kMaxFrameData || dataLength + kFrameOverhead > capacity) {
    return 0;
  }
  uint8_t length = static_cast<uint8_t>(dataLength + 1);
  size_t at = 0;
  out[at++] = 0x00;
  out[at++] = 0x00;
  out[at++] = 0xFF;
  out[at++] = length;
  out[at++] = static_cast<uint8_t>(-length);
  out[at++] = kHostToPn532;
  uint8_t sum = kHostToPn532;
  for (size_t i = 0; i < headerLength; i++) {
    out[at++] = header[i];
    sum += header[i];
  }
  for (size_t i = 0; i < bodyLength; i++) {
    out[at++] = body[i];
    sum += body[i];
  }
  out[at++] = static_cast<uint8_t>(-sum);
  out[at++] = 0x00;
  return at;
}

FrameReader::Result FrameReader::finish(Result result) {
  state_ = State::kStart0;
  return result;
}

FrameReader::Result FrameReader::feed(uint8_t byte) {
  switch (state_) {
    case State::kStart0:
      if (byte == 0x00) {
        state_ = State::kStartFF;
      }
      return Result::kNeedMore;
    case State::kStartFF:
      if (byte == 0xFF) {
        state_ = State::kLength;
      } else if (byte != 0x00) {
        state_ = State::kStart0;
      }
      return Result::kNeedMore;
    case State::kLength:
      length_ = byte;
      state_ = State::kLengthChecksum;
      return Result::kNeedMore;
    case State::kLengthChecksum:
      if (length_ == 0x00 && byte == 0xFF) {
        return finish(Result::kAck);
      }
      if (length_ == 0xFF && byte == 0x00) {
        return finish(Result::kNack);
      }
      if (length_ == 0x00 || static_cast<uint8_t>(length_ + byte) != 0) {
        return finish(Result::kBad);
      }
      received_ = 0;
      sum_ = 0;
      state_ = State::kBody;
      return Result::kNeedMore;
    case State::kBody:
      body_[received_++] = byte;
      sum_ += byte;
      if (received_ == length_) {
        state_ = State::kDataChecksum;
      }
      return Result::kNeedMore;
    case State::kDataChecksum:
      if (static_cast<uint8_t>(sum_ + byte) != 0) {
        return finish(Result::kBad);
      }
      return finish(length_ == 1 && body_[0] == kErrorTfi ? Result::kError : Result::kFrame);
  }
  return finish(Result::kBad);
}

int16_t unpackResponse(const FrameReader &frame, uint8_t command, uint8_t *out, size_t capacity) {
  if (frame.tfi() != kPn532ToHost || frame.length() < 1 || frame.data()[0] != static_cast<uint8_t>(command + 1)) {
    return kInvalidFrame;
  }
  size_t length = frame.length() - 1;
  if (length > capacity) {
    return kNoSpace;
  }
  memcpy(out, frame.data() + 1, length);
  return static_cast<int16_t>(length);
}

}  // namespace pn532
}  // namespace tip
