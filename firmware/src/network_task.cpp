#include <WebSocketsClient.h>
#include <WiFi.h>
#include <esp_random.h>
#include <time.h>

#include <atomic>

#include "secure_transport.h"
#include "log.h"
#include "tasks.h"
#include "tip_config.h"
#include "tip_intake.h"
#include "tip_link.h"
#include "tip_protocol.h"

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and fill in your values"
#endif

#if !defined(TIP_DEVICE_ID) || !defined(TIP_DEVICE_SECRET)
#error "secrets.h needs TIP_DEVICE_ID and TIP_DEVICE_SECRET, see include/secrets.example.h"
#endif

namespace tip {

namespace {

// Core 1, below the playback task. WiFi and lwIP run on core 0. TLS needs a large stack.
constexpr BaseType_t kCore = 1;
constexpr UBaseType_t kPriority = 2;
constexpr uint32_t kStackBytes = 12288;
constexpr uint32_t kLoopDelayMs = 10;
constexpr uint32_t kWifiRetryMs = 15000;

enum class State : uint8_t { Idle, Connecting, Connected };

QueueHandle_t outgoing;
std::atomic<LinkStatus> status{LinkStatus::Connecting};
WebSocketsClient socket;
Intake<kRecentIds> intake;
uint32_t randomWord(void *) { return esp_random(); }
Backoff backoff({kBackoffFirstMs, kBackoffCapMs, kBackoffJitterPercent, kStableAfterMs, kReplacedWaitMs,
                 kAuthRetryMs},
                randomWord, nullptr);

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

// WiFi stays up on its own (auto reconnect); this only logs and nudges it.
bool wifiReady() {
  static bool wasUp = false;
  static uint32_t lastLog = 0;
  static uint32_t lastRetry = 0;
  uint32_t now = millis();
  if (WiFi.status() == WL_CONNECTED) {
    if (!wasUp) {
      logLine("WiFi: connected, IP %s, RSSI %d dBm, sleep %s", WiFi.localIP().toString().c_str(),
              WiFi.RSSI(), WiFi.getSleep() ? "on" : "off");
      wasUp = true;
    }
    return true;
  }
  if (wasUp) {
    logLine("WiFi: lost");
    wasUp = false;
    lastRetry = now;
  }
  if (now - lastLog >= kWaitLogIntervalMs) {
    logLine("WiFi: waiting for %s (status %d)", TIP_WIFI_SSID, static_cast<int>(WiFi.status()));
    lastLog = now;
  }
  if (now - lastRetry >= kWifiRetryMs) {
    WiFi.reconnect();
    lastRetry = now;
  }
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
  if (!wifiReady()) {
    if (state == State::Connecting || state == State::Connected) {
      closeConnection("WiFi lost");
    }
    setStatus(LinkStatus::Connecting);
    return;
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

void networkLoop(void *) {
  if (!deviceIdValid() || strcmp(TIP_DEVICE_SECRET, "your-device-secret") == 0) {
    logLine("Config: set TIP_DEVICE_ID (a lowercase uuid) and TIP_DEVICE_SECRET in secrets.h");
    status.store(LinkStatus::AuthRejected);
    vTaskDelete(nullptr);
    return;
  }
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.setAutoReconnect(true);
  logLine("WiFi: connecting to %s", TIP_WIFI_SSID);
  WiFi.begin(TIP_WIFI_SSID, TIP_WIFI_PASSWORD);
  configTime(0, 0, kNtpServer1, kNtpServer2, kNtpServer3);

  socket.beginSSL(TIP_SERVER_HOST, TIP_SERVER_PORT, TIP_DEVICE_PATH_PREFIX TIP_DEVICE_ID, "", "");
  // Replaces the library's default "Origin: file://", which the server's same origin check would refuse.
  socket.setExtraHeaders(nullptr);
  socket.setAuthorization("Bearer " TIP_DEVICE_SECRET);
  socket.setReconnectInterval(kLibraryNeverReconnects);
  socket.onEvent(onEvent);

  for (;;) {
    step();
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
