#include <Arduino.h>
#include <HTTPClient.h>
#include <NetworkClientSecure.h>
#include <WiFi.h>
#include <time.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "Copy include/secrets.example.h to include/secrets.h and fill in your WiFi details"
#endif

static constexpr uint8_t LED_PIN = 2;
static constexpr uint32_t LED_FREQ_HZ = 5000;
static constexpr uint8_t LED_RESOLUTION_BITS = 8;
static constexpr uint32_t LED_MAX = (1u << LED_RESOLUTION_BITS) - 1;
static constexpr uint32_t WIFI_TIMEOUT_MS = 20000;
static constexpr uint32_t TIME_SYNC_TIMEOUT_MS = 15000;
static constexpr time_t TIME_VALID_AFTER = 1767225600;  // 2026-01-01T00:00:00Z

static const char *const HEALTH_URL = "https://tip-integrated-panda.onrender.com/actuator/health";
static const char *const EXPIRED_URL = "https://expired.badssl.com/";
static const char *const WRONG_HOST_URL = "https://wrong.host.badssl.com/";

static void printChipInfo() {
  uint64_t mac = ESP.getEfuseMac();
  uint16_t revision = ESP.getChipRevision();
  Serial.printf("Chip model: %s rev v%u.%u, %u cores, %lu MHz\n", ESP.getChipModel(),
                revision / 100, revision % 100, ESP.getChipCores(), ESP.getCpuFreqMHz());
  Serial.printf("Flash: %lu bytes\n", ESP.getFlashChipSize());
  Serial.printf("MAC: %02X:%02X:%02X:%02X:%02X:%02X\n", (uint8_t)(mac), (uint8_t)(mac >> 8),
                (uint8_t)(mac >> 16), (uint8_t)(mac >> 24), (uint8_t)(mac >> 32),
                (uint8_t)(mac >> 40));
}

static void ledDemo() {
  Serial.println("LED: slow fade up and down");
  for (uint32_t duty = 0; duty <= LED_MAX; duty++) {
    ledcWrite(LED_PIN, duty);
    delay(8);
  }
  for (int32_t duty = LED_MAX; duty >= 0; duty--) {
    ledcWrite(LED_PIN, duty);
    delay(8);
  }
  delay(500);
  Serial.println("LED: three blinks");
  for (int i = 0; i < 3; i++) {
    ledcWrite(LED_PIN, LED_MAX);
    delay(250);
    ledcWrite(LED_PIN, 0);
    delay(250);
  }
}

static bool connectWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  Serial.printf("WiFi: connecting to %s\n", TIP_WIFI_SSID);
  WiFi.begin(TIP_WIFI_SSID, TIP_WIFI_PASSWORD);
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - start > WIFI_TIMEOUT_MS) {
      Serial.printf("WiFi: failed, status %d\n", WiFi.status());
      return false;
    }
    delay(250);
  }
  Serial.printf("WiFi: connected, IP %s, RSSI %d dBm, sleep %s\n",
                WiFi.localIP().toString().c_str(), WiFi.RSSI(),
                WiFi.getSleep() ? "on" : "off");
  return true;
}

static bool syncTime() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  uint32_t start = millis();
  while (time(nullptr) < TIME_VALID_AFTER) {
    if (millis() - start > TIME_SYNC_TIMEOUT_MS) {
      Serial.println("Time: SNTP sync failed");
      return false;
    }
    delay(200);
  }
  time_t now = time(nullptr);
  struct tm utc;
  gmtime_r(&now, &utc);
  char text[32];
  strftime(text, sizeof(text), "%Y-%m-%dT%H:%M:%SZ", &utc);
  Serial.printf("Time: %s\n", text);
  return true;
}

static void httpsGet(const char *url) {
  Serial.printf("HTTPS GET %s\n", url);
  NetworkClientSecure client;
  client.useBuiltinCACertBundle();
  HTTPClient http;
  if (!http.begin(client, url)) {
    Serial.println("  begin failed");
    return;
  }
  int status = http.GET();
  if (status > 0) {
    Serial.printf("  status %d\n", status);
    Serial.printf("  body %s\n", http.getString().c_str());
  } else {
    char tlsError[128] = "";
    int tlsCode = client.lastError(tlsError, sizeof(tlsError));
    Serial.printf("  failed: %s (TLS error %d: %s)\n", http.errorToString(status).c_str(),
                  tlsCode, tlsError);
  }
  http.end();
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("TiP firmware bring up");
  printChipInfo();
  if (!ledcAttach(LED_PIN, LED_FREQ_HZ, LED_RESOLUTION_BITS)) {
    Serial.println("LED: ledcAttach failed");
  }
  ledDemo();
  if (!connectWifi() || !syncTime()) {
    return;
  }
  httpsGet(HEALTH_URL);
  httpsGet(EXPIRED_URL);
  httpsGet(WRONG_HOST_URL);
  Serial.println("Bring up done");
}

void loop() { delay(1000); }
