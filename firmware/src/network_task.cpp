#include <WebSocketsClient.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_wifi.h>
#include <time.h>

#include <atomic>

#include "secure_transport.h"
#include "log.h"
#include "tasks.h"
#include "tip_config.h"
#include "tip_intake.h"
#include "tip_link.h"
#include "tip_protocol.h"
#include "tip_wifi.h"
#include "watchdog.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and fill in your values"
#endif

#if !defined(TIP_WIFI_NETWORKS) && defined(TIP_WIFI_SSID)
#error "secrets.h has the old TIP_WIFI_SSID and TIP_WIFI_PASSWORD; replace them with TIP_WIFI_NETWORKS, see include/secrets.example.h"
#elif !defined(TIP_WIFI_NETWORKS) || !defined(TIP_DEVICE_ID) || !defined(TIP_DEVICE_SECRET)
#error "secrets.h needs TIP_WIFI_NETWORKS, TIP_DEVICE_ID and TIP_DEVICE_SECRET, see include/secrets.example.h"
#endif

namespace tip {

namespace {

// Core 1, below the playback task. WiFi and lwIP run on core 0. TLS needs a large stack.
constexpr BaseType_t kCore = 1;
constexpr UBaseType_t kPriority = 2;
constexpr uint32_t kStackBytes = 12288;
constexpr uint32_t kLoopDelayMs = 10;

constexpr WifiNetwork kWifiNetworks[] = {TIP_WIFI_NETWORKS};
constexpr size_t kWifiNetworkCount = sizeof(kWifiNetworks) / sizeof(kWifiNetworks[0]);
static_assert(kWifiNetworkCount >= 1 && kWifiNetworkCount <= kMaxWifiNetworks,
              "TIP_WIFI_NETWORKS in secrets.h needs 1 to 4 networks");
static_assert(wifiSsidsSet(kWifiNetworks, kWifiNetworkCount),
              "every network in TIP_WIFI_NETWORKS in secrets.h needs a non empty SSID");

enum class State : uint8_t { Idle, Connecting, Connected };

QueueHandle_t outgoing;
std::atomic<LinkStatus> status{LinkStatus::Connecting};
WebSocketsClient socket;
Intake<kRecentIds> intake;
uint32_t randomWord(void *) { return esp_random(); }
constexpr BackoffConfig kBackoffConfig{kBackoffFirstMs, kBackoffCapMs, kBackoffJitterPercent,
                                       kStableAfterMs, kReplacedWaitMs, kAuthRetryMs};
Backoff backoff(kBackoffConfig, randomWord, nullptr);
// Its own instance, so failed WiFi rounds never change the WebSocket's backoff.
Backoff wifiBackoff(kBackoffConfig, randomWord, nullptr);

State state = State::Idle;
uint32_t nextAttemptAt = 0;
uint32_t attemptStartedAt = 0;
uint32_t connectedAt = 0;
uint32_t lastReceivedAt = 0;
uint32_t transportAttempts = 0;
const char *closeReason = nullptr;
// Set when the server announced that another connection as this device replaced this one.
bool replaced = false;

// The rejected flicks stay through the retries after a 401, until a connection succeeds.
void setStatus(LinkStatus next) {
  if (next == LinkStatus::Connected || status.load() != LinkStatus::AuthRejected) {
    status.store(next);
  }
}

// The library reconnects by itself in loop() once its reconnect interval has passed. The interval
// is held at the maximum between attempts, so only the backoff decides when to connect.
constexpr unsigned long kLibraryNeverReconnects = 0xFFFFFFFFul;

void scheduleRetry(uint32_t connectedForMs, const char *why) {
  socket.setReconnectInterval(kLibraryNeverReconnects);
  uint32_t delay = replaced ? backoff.nextAfterReplaced(connectedForMs) : backoff.next(connectedForMs);
  replaced = false;
  nextAttemptAt = millis() + delay;
  state = State::Idle;
  setStatus(LinkStatus::Connecting);
  logLine("WS: %s, attempt %lu in %.1f s", why, static_cast<unsigned long>(backoff.attempt() + 1),
          delay / 1000.0);
}

// Closes an open or opening connection. The library's disconnect event schedules the retry; when
// there was no socket to close, there is no event, and the retry is scheduled here.
void closeConnection(const char *why) {
  closeReason = why;
  socket.disconnect();
  closeReason = nullptr;
  if (state == State::Connected) {
    scheduleRetry(millis() - connectedAt, why);
  } else if (state == State::Connecting) {
    scheduleRetry(0, why);
  }
}

void onDisconnected() {
  const char *why = closeReason;
  closeReason = nullptr;
  if (state == State::Connected) {
    uint32_t upMs = millis() - connectedAt;
    logLine("WS: disconnected after %.1f s (%s)", upMs / 1000.0, why != nullptr ? why : "closed");
    if (replaced) {
      logLine("WS: replaced by another connection as %s, waiting at least %lu s", TIP_DEVICE_SLUG,
              static_cast<unsigned long>(kReplacedWaitMs / 1000));
    }
    scheduleRetry(upMs, "reconnecting");
    return;
  }
  if (state != State::Connecting) {
    return;
  }
  int httpStatus = transport::handshakeStatus();
  if (httpStatus == 401) {
    socket.setReconnectInterval(kLibraryNeverReconnects);
    nextAttemptAt = millis() + backoff.afterAuthRejected();
    state = State::Idle;
    status.store(LinkStatus::AuthRejected);
    logLine("WS: auth rejected (401). Check TIP_DEVICE_ID and TIP_DEVICE_SECRET in secrets.h. "
            "Retrying in %lu minutes.",
            static_cast<unsigned long>(kAuthRetryMs / 60000));
    return;
  }
  if (httpStatus != 0) {
    logLine("WS: handshake answered HTTP %d", httpStatus);
  }
  scheduleRetry(0, why != nullptr ? why : "handshake failed");
}

void onReaction(const ParsedMessage &message) {
  const Reaction &reaction = message.reaction;
  IntakeDecision decision = intake.accept(reaction);
  char ack[64];
  size_t ackLength = buildAck(reaction.id, ack, sizeof(ack));
  bool acked = decision.ack && ackLength > 0 && socket.sendTXT(ack, ackLength);
  logLine("WS: reaction %s x%u tip %s received, ack %s", kindName(reaction.kind), reaction.repeats,
          reaction.id, acked ? "sent" : "FAILED");
  if (message.unknownKind) {
    logLine("WS: unknown kind, playing PLAIN with repeats 1");
  }
  if (message.repeatsDefaulted) {
    logLine("WS: repeats missing or not an integer, using 1");
  }
  if (!decision.play) {
    logLine("WS: tip %s is a repeat, acked again, not played again", reaction.id);
    return;
  }
  if (xQueueSend(outgoing, &reaction, 0) != pdTRUE) {
    logLine("WS: LED hand over full, dropped tip %s", reaction.id);
  }
}

void onText(uint8_t *payload, size_t length) {
  ParsedMessage message = parseServerMessage(reinterpret_cast<const char *>(payload), length, kMaxFrameBytes);
  switch (message.type) {
    case MessageType::Hello:
      logLine("WS: hello from the server");
      break;
    case MessageType::Reaction:
      onReaction(message);
      break;
    case MessageType::Closing:
      logLine("WS: the server is closing the connection (%s)", closingReasonName(message.closingReason));
      replaced = message.closingReason == ClosingReason::Replaced;
      break;
    case MessageType::Ignored:
      logLine("WS: ignored a message (%s)", message.problem);
      break;
    case MessageType::Malformed:
      logLine("WS: malformed message of %u bytes, not acked (%s)", static_cast<unsigned>(length),
              message.problem);
      break;
  }
}

void onEvent(WStype_t type, uint8_t *payload, size_t length) {
  if (type != WStype_DISCONNECTED && type != WStype_ERROR) {
    lastReceivedAt = millis();
  }
  switch (type) {
    case WStype_CONNECTED:
      state = State::Connected;
      connectedAt = millis();
      setStatus(LinkStatus::Connected);
      logLine("WS: connected as %s", TIP_DEVICE_SLUG);
      break;
    case WStype_DISCONNECTED:
      onDisconnected();
      break;
    case WStype_TEXT:
      onText(payload, length);
      break;
    case WStype_PING:
    case WStype_PONG:
      break;
    case WStype_ERROR:
      logLine("WS: protocol error");
      break;
    default:
      logLine("WS: ignored a frame of type %d", static_cast<int>(type));
      break;
  }
}

bool clockSet() { return ::time(nullptr) >= kClockSetAfter; }

void logTime() {
  time_t now = ::time(nullptr);
  struct tm utc;
  gmtime_r(&now, &utc);
  char text[32];
  strftime(text, sizeof(text), "%Y-%m-%dT%H:%M:%SZ", &utc);
  logLine("Time: %s", text);
}

// The driver never reconnects by itself (auto reconnect is off). While WiFi is down this scans,
// tries the known networks in chooseWifiOrder's order and, when all of them fail, waits for the
// WiFi backoff before the next scan. A network that drops starts a fresh scan at once, so the panda
// moves to another known network by itself.
bool wifiUp = false;
uint32_t wifiJoinedAt = 0;
// How long the last joined network stayed up, handed to the backoff once so it can start over.
uint32_t wifiUpForMs = 0;
uint32_t nextScanAt = 0;
ScanResult scanResults[kMaxScanResults];

// Waits up to timeoutMs for done() to turn true, checking every kWifiPollMs and feeding the
// watchdog on every pass.
template <typename Done>
bool waitUntil(uint32_t timeoutMs, Done done) {
  uint32_t startedAt = millis();
  while (!done()) {
    if (millis() - startedAt >= timeoutMs) {
      return false;
    }
    watchdogFeed();
    vTaskDelay(pdMS_TO_TICKS(kWifiPollMs));
  }
  return true;
}

size_t scanAndChoose(WifiCandidate *order) {
  int16_t found = WiFi.scanNetworks(true);
  waitUntil(kWifiScanTimeoutMs, [&found] {
    if (found == WIFI_SCAN_RUNNING) {
      found = WiFi.scanComplete();
    }
    return found != WIFI_SCAN_RUNNING;
  });
  if (found == WIFI_SCAN_RUNNING) {
    esp_wifi_scan_stop();
    logLine("WiFi: scan gave no answer in %lu s", static_cast<unsigned long>(kWifiScanTimeoutMs / 1000));
  } else if (found < 0) {
    logLine("WiFi: scan failed (%d)", found);
  }
  size_t seenCount = 0;
  for (int16_t i = 0; i < found && seenCount < kMaxScanResults; i++) {
    const auto *record = static_cast<const wifi_ap_record_t *>(WiFi.getScanInfoByIndex(i));
    if (record != nullptr) {
      scanResults[seenCount++] = {reinterpret_cast<const char *>(record->ssid), record->rssi};
    }
  }
  size_t count = chooseWifiOrder(kWifiNetworks, kWifiNetworkCount, scanResults, seenCount, order);
  WiFi.scanDelete();
  if (order[0].seen) {
    logLine("WiFi: scan found %u networks, %u known", static_cast<unsigned>(seenCount),
            static_cast<unsigned>(count));
  } else {
    logLine("WiFi: scan found %u networks, none known, trying all %u", static_cast<unsigned>(seenCount),
            static_cast<unsigned>(count));
  }
  return count;
}

bool join(const WifiCandidate &candidate) {
  const WifiNetwork &network = kWifiNetworks[candidate.network];
  if (candidate.seen) {
    logLine("WiFi: joining %s (%ld dBm)", network.ssid, static_cast<long>(candidate.rssi));
  } else {
    logLine("WiFi: joining %s (not seen in the scan)", network.ssid);
  }
  WiFi.begin(network.ssid, network.password);
  if (!waitUntil(kWifiJoinTimeoutMs, [] { return WiFi.status() == WL_CONNECTED; })) {
    logLine("WiFi: %s failed after %lu s (status %d)", network.ssid,
            static_cast<unsigned long>(kWifiJoinTimeoutMs / 1000), static_cast<int>(WiFi.status()));
    WiFi.disconnect();
    return false;
  }
  logLine("WiFi: joined %s, IP %s, RSSI %d dBm, sleep %s", network.ssid, WiFi.localIP().toString().c_str(),
          WiFi.RSSI(), WiFi.getSleep() ? "on" : "off");
  return true;
}

// Called while WiFi is down. Returns true once a known network is joined.
bool joinWifi() {
  uint32_t now = millis();
  if (wifiUp) {
    wifiUp = false;
    wifiUpForMs = now - wifiJoinedAt;
    logLine("WiFi: lost after %.1f s, scanning again", wifiUpForMs / 1000.0);
    WiFi.disconnect();
    nextScanAt = now;
  }
  if (static_cast<int32_t>(now - nextScanAt) < 0) {
    return false;
  }
  WifiCandidate order[kMaxWifiNetworks];
  size_t count = scanAndChoose(order);
  for (size_t i = 0; i < count; i++) {
    if (join(order[i])) {
      wifiUp = true;
      wifiJoinedAt = millis();
      return true;
    }
  }
  uint32_t delay = wifiBackoff.next(wifiUpForMs);
  wifiUpForMs = 0;
  nextScanAt = millis() + delay;
  logLine("WiFi: no known network joined, scanning again in %.1f s", delay / 1000.0);
  return false;
}

bool timeReady() {
  static bool wasSet = false;
  static uint32_t lastLog = 0;
  if (clockSet()) {
    if (!wasSet) {
      logTime();
      wasSet = true;
    }
    return true;
  }
  uint32_t now = millis();
  if (now - lastLog >= kWaitLogIntervalMs) {
    logLine("Time: waiting for SNTP from %s, %s, %s. No TLS until the clock is set.", kNtpServer1,
            kNtpServer2, kNtpServer3);
    lastLog = now;
  }
  return false;
}

void startAttempt() {
  // Clears what a failed attempt left behind while still idle, so its disconnect event is ignored.
  socket.disconnect();
  socket.setReconnectInterval(0);
  state = State::Connecting;
  attemptStartedAt = millis();
  lastReceivedAt = attemptStartedAt;
  transportAttempts = transport::attempts();
  replaced = false;
  logLine("WS: connecting to wss://%s as %s", TIP_SERVER_HOST, TIP_DEVICE_SLUG);
}

void step() {
  if (WiFi.status() != WL_CONNECTED) {
    if (state == State::Connecting || state == State::Connected) {
      closeConnection("WiFi lost");
    }
    setStatus(LinkStatus::Connecting);
    if (!joinWifi()) {
      return;
    }
  }
  if (!timeReady()) {
    return;
  }
  uint32_t now = millis();
  if (state == State::Idle) {
    if (static_cast<int32_t>(now - nextAttemptAt) < 0) {
      return;
    }
    startAttempt();
  }
  socket.loop();
  if (state == State::Connecting && transport::attempts() != transportAttempts &&
      !transport::lastAttemptSucceeded()) {
    scheduleRetry(0, transport::lastFailure());
  } else if (state == State::Connecting && millis() - attemptStartedAt >= kHandshakeDeadlineMs) {
    closeConnection("no answer to the handshake");
  } else if (state == State::Connected && isSilent(millis(), lastReceivedAt, kSilenceLimitMs)) {
    logLine("WS: nothing received for %lu s", static_cast<unsigned long>(kSilenceLimitMs / 1000));
    closeConnection("silent connection");
  }
}

bool deviceIdValid() {
  static const char kId[] = TIP_DEVICE_ID;
  return isCanonicalUuid(kId, sizeof(kId) - 1) && strcmp(kId, "00000000-0000-0000-0000-000000000000") != 0;
}

bool wifiTemplateLeft() {
  for (const WifiNetwork &network : kWifiNetworks) {
    if (strcmp(network.ssid, "your-wifi-name") == 0 ||
        (network.password != nullptr && strcmp(network.password, "your-wifi-password") == 0)) {
      return true;
    }
  }
  return false;
}

void networkLoop(void *) {
  if (wifiTemplateLeft()) {
    logLine("Config: replace the template network in TIP_WIFI_NETWORKS in secrets.h");
    status.store(LinkStatus::AuthRejected);
    vTaskDelete(nullptr);
    return;
  }
  if (!deviceIdValid() || strcmp(TIP_DEVICE_SECRET, "your-device-secret") == 0) {
    logLine("Config: set TIP_DEVICE_ID (a lowercase uuid) and TIP_DEVICE_SECRET in secrets.h");
    status.store(LinkStatus::AuthRejected);
    vTaskDelete(nullptr);
    return;
  }
  // Keeps the WiFi configuration in RAM, so joins do not wear the flash and nothing stored
  // reconnects behind the task's back.
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(false);
  logLine("WiFi: %u known networks", static_cast<unsigned>(kWifiNetworkCount));
  configTime(0, 0, kNtpServer1, kNtpServer2, kNtpServer3);

  socket.beginSSL(TIP_SERVER_HOST, TIP_SERVER_PORT, TIP_DEVICE_PATH_PREFIX TIP_DEVICE_ID, "", "");
  // Replaces the library's default "Origin: file://", which the server's same origin check would refuse.
  socket.setExtraHeaders(nullptr);
  socket.setAuthorization("Bearer " TIP_DEVICE_SECRET);
  socket.setReconnectInterval(kLibraryNeverReconnects);
  socket.onEvent(onEvent);
  watchdogWatch();

  // Evidence that the lower priority NFC task never holds this one up: the longest time between
  // the end of one step and the start of the next, kLoopDelayMs when nothing does.
  uint32_t stepEndedAt = millis();
  uint32_t gapLoggedAt = stepEndedAt;
  uint32_t longestGap = 0;
  for (;;) {
    watchdogFeed();
    uint32_t now = millis();
    longestGap = max(longestGap, now - stepEndedAt);
    if (now - gapLoggedAt >= kStepGapLogMs) {
      logLine("Net: longest pause between steps %lu ms in the last %lu s", static_cast<unsigned long>(longestGap),
              static_cast<unsigned long>((now - gapLoggedAt) / 1000));
      gapLoggedAt = now;
      longestGap = 0;
    }
    step();
    stepEndedAt = millis();
    vTaskDelay(pdMS_TO_TICKS(kLoopDelayMs));
  }
}

}  // namespace

LinkStatus linkStatus() { return status.load(); }

void startNetworkTask(QueueHandle_t reactions) {
  outgoing = reactions;
  xTaskCreatePinnedToCore(networkLoop, "net", kStackBytes, nullptr, kPriority, nullptr, kCore);
}

}  // namespace tip
