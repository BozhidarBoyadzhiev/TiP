// Committed configuration of the panda firmware: server, device slug, NFC tag and timings.
// The device id and secret are per deployment and live in secrets.h.
#pragma once

#include <stdint.h>

// Server
#define TIP_SERVER_HOST "tip-integrated-panda.onrender.com"
#define TIP_SERVER_PORT 443
#define TIP_DEVICE_SLUG "demo-panda"
#define TIP_DEVICE_PATH_PREFIX "/ws/device/"
// The server's public base URL (port 443, so none in it). The NFC tag holds its tip page,
// TIP_PUBLIC_BASE_URL "/t/" TIP_DEVICE_SLUG.
#define TIP_PUBLIC_BASE_URL "https://" TIP_SERVER_HOST

// PN532 UART2 speed: 115200 (its power-on speed, the one the 05c phone taps used) or 921600.
// Override with PLATFORMIO_BUILD_FLAGS="-DTIP_NFC_BAUD=921600".
#ifndef TIP_NFC_BAUD
#define TIP_NFC_BAUD 115200
#endif

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

// The network task logs the longest pause between two of its steps this often.
constexpr uint32_t kStepGapLogMs = 60000;

// NFC: looking for a PN532 that is missing or stopped answering starts at this wait and doubles to
// the cap. A failure while it answers first gets a quick resync (wake-up and SAMConfig) with
// kNfcResyncAttempts tries, from kNfcResyncFirstMs doubling.
constexpr uint32_t kNfcRetryFirstMs = 1000;
constexpr uint32_t kNfcRetryCapMs = 30000;
constexpr uint32_t kNfcResyncFirstMs = 20;
constexpr uint32_t kNfcResyncAttempts = 6;
// While armed and untouched, the tag is stopped this often to check the PN532 still answers, so
// one that was unplugged or lost power is found again. The tag is off for about 20 ms each time.
constexpr uint32_t kNfcAliveCheckMs = 10000;
// After a read the tag stays off this long: an iPhone left on the coin reads it again about 500 ms
// later and shows a second prompt. Real taps are 20 s or more apart.
constexpr uint32_t kNfcRearmHoldOffMs = 3000;
// Then it waits until there has been no field for kNfcFieldQuietMs, at most kNfcFieldWaitCapMs.
constexpr uint32_t kNfcFieldQuietMs = 100;
constexpr uint32_t kNfcFieldWaitCapMs = 3000;

}  // namespace tip
