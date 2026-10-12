// Committed configuration of the panda firmware: server, device slug and timings.
// The device id and secret are per deployment and live in secrets.h.
#pragma once

#include <stdint.h>

// Server
#define TIP_SERVER_HOST "tip-integrated-panda.onrender.com"
#define TIP_SERVER_PORT 443
#define TIP_DEVICE_SLUG "demo-panda"
#define TIP_DEVICE_PATH_PREFIX "/ws/device/"

namespace tip {

// SNTP servers, tried in this order.
constexpr const char *kNtpServer1 = "pool.ntp.org";
constexpr const char *kNtpServer2 = "time.google.com";
constexpr const char *kNtpServer3 = "time.cloudflare.com";

// The clock counts as set from 2025-01-01T00:00:00Z. Before that no TLS connection is attempted.
constexpr int64_t kClockSetAfter = 1735689600;

// Logging cadence while waiting for WiFi or time.
constexpr uint32_t kWaitLogIntervalMs = 5000;

// TLS: the handshake gives up after this long (the core's default is 120 s).
constexpr uint32_t kTlsHandshakeTimeoutS = 15;
// TCP connect timeout before the handshake.
constexpr int32_t kTcpConnectTimeoutMs = 10000;
// From the start of an attempt to the server's 101 answer.
constexpr uint32_t kHandshakeDeadlineMs = 30000;

// Close and reconnect when nothing (a ping or any frame) has arrived for this long.
// The server pings every 20 s.
constexpr uint32_t kSilenceLimitMs = 45000;

// Reconnect backoff: doubles from the first delay up to the cap, each delay shortened by up to
// kBackoffJitterPercent at random. It starts over only after a connection stayed up kStableAfterMs.
constexpr uint32_t kBackoffFirstMs = 1000;
constexpr uint32_t kBackoffCapMs = 60000;
constexpr uint32_t kBackoffJitterPercent = 20;
constexpr uint32_t kStableAfterMs = 60000;
// After the server says another connection as this device replaced us.
constexpr uint32_t kReplacedWaitMs = 60000;
// After the server rejected the device id or secret (HTTP 401).
constexpr uint32_t kAuthRetryMs = 600000;

// Server messages longer than this are dropped unparsed. The server's own inbound limit is 1024.
constexpr uint32_t kMaxFrameBytes = 1024;

// Reactions waiting behind the one playing. When full, the oldest waiting one is dropped.
constexpr uint32_t kWaitingReactions = 8;

// Reaction ids remembered to recognise replays and resends.
constexpr uint32_t kRecentIds = 16;

// The playback task updates the LED this often.
constexpr uint32_t kLedTickMs = 10;

}  // namespace tip
