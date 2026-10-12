#include "secure_transport.h"

#include "log.h"

#include <Arduino.h>
#include <NetworkClientSecure.h>
#include <WebSocketsNetworkClientSecure.h>
#include <time.h>

#include <memory>

#include "tip_config.h"
#include "tip_link.h"

namespace tip {
namespace transport {

namespace {

uint32_t attemptCount = 0;
bool lastOk = false;
const char *failure = "";

// The first line the server sends after the TLS handshake, the HTTP status line of its answer to
// the upgrade request. The library does not report the status, and a 401 must stop reconnects.
char statusLine[48];
size_t statusLength = 0;
bool statusComplete = false;

void resetStatusLine() {
  statusLength = 0;
  statusComplete = false;
}

void recordStatusLine(const uint8_t *data, int length) {
  for (int i = 0; i < length && !statusComplete; i++) {
    statusLine[statusLength++] = static_cast<char>(data[i]);
    statusComplete = data[i] == '\n' || statusLength == sizeof(statusLine);
  }
}

int fail(const char *why) {
  lastOk = false;
  failure = why;
  return 0;
}

CertTime fromX509(const mbedtls_x509_time &time) {
  return CertTime{time.year, time.mon, time.day, time.hour, time.min, time.sec};
}

// Checks notBefore and notAfter of every certificate the server presented, leaf first.
bool chainDatesValid(const mbedtls_x509_crt *chain) {
  if (chain == nullptr) {
    logLine("TLS: the server presented no certificate");
    return false;
  }
  CertTime now = certTimeFromUnix(static_cast<int64_t>(::time(nullptr)));
  int index = 0;
  for (const mbedtls_x509_crt *cert = chain; cert != nullptr; cert = cert->next) {
    const mbedtls_x509_time &from = cert->valid_from;
    const mbedtls_x509_time &to = cert->valid_to;
    Validity validity = checkValidity(fromX509(from), fromX509(to), now);
    logLine("TLS: certificate %d valid %04d-%02d-%02d to %04d-%02d-%02d: %s", index, from.year,
            from.mon, from.day, to.year, to.mon, to.day,
            validity == Validity::Valid         ? "ok"
            : validity == Validity::Expired     ? "EXPIRED"
                                                : "NOT YET VALID");
    if (validity != Validity::Valid) {
      return false;
    }
    index++;
  }
  logLine("TLS: chain date check passed for %d certificates", index);
  return true;
}

}  // namespace

uint32_t attempts() { return attemptCount; }

bool lastAttemptSucceeded() { return lastOk; }

const char *lastFailure() { return failure; }

int handshakeStatus() { return statusComplete ? httpStatus(statusLine, statusLength) : 0; }

}  // namespace transport
}  // namespace tip

struct WebSocketsNetworkClient::Impl {
  NetworkClient plain;
  std::unique_ptr<NetworkClientSecure> secure;
};

// Plain connections: the library's server side needs them to link; the panda never uses them.

WebSocketsNetworkClient::WebSocketsNetworkClient() : _impl(new Impl()) {}

WebSocketsNetworkClient::WebSocketsNetworkClient(WiFiClient client) : _impl(new Impl()) {
  _impl->plain = client;
}

WebSocketsNetworkClient::~WebSocketsNetworkClient() {}

int WebSocketsNetworkClient::connect(IPAddress ip, uint16_t port) { return _impl->plain.connect(ip, port); }

int WebSocketsNetworkClient::connect(const char *host, uint16_t port) {
  return _impl->plain.connect(host, port);
}

int WebSocketsNetworkClient::connect(const char *host, uint16_t port, int32_t timeout) {
  return _impl->plain.connect(host, port, timeout);
}

size_t WebSocketsNetworkClient::write(uint8_t data) { return _impl->plain.write(data); }

size_t WebSocketsNetworkClient::write(const uint8_t *buf, size_t size) {
  return _impl->plain.write(buf, size);
}

size_t WebSocketsNetworkClient::write(const char *str) {
  return _impl->plain.write(reinterpret_cast<const uint8_t *>(str), strlen(str));
}

int WebSocketsNetworkClient::available() { return _impl->plain.available(); }

int WebSocketsNetworkClient::read() { return _impl->plain.read(); }

int WebSocketsNetworkClient::read(uint8_t *buf, size_t size) { return _impl->plain.read(buf, size); }

int WebSocketsNetworkClient::peek() { return _impl->plain.peek(); }

void WebSocketsNetworkClient::flush() { _impl->plain.flush(); }

void WebSocketsNetworkClient::stop() { _impl->plain.stop(); }

uint8_t WebSocketsNetworkClient::connected() { return _impl->plain.connected(); }

WebSocketsNetworkClient::operator bool() { return _impl->plain.connected(); }

// TLS connections.

WebSocketsNetworkClientSecure::WebSocketsNetworkClientSecure() {
  _impl->secure.reset(new NetworkClientSecure());
}

WebSocketsNetworkClientSecure::WebSocketsNetworkClientSecure(WiFiClient) {
  _impl->secure.reset(new NetworkClientSecure());
}

WebSocketsNetworkClientSecure::~WebSocketsNetworkClientSecure() {}

int WebSocketsNetworkClientSecure::connect(IPAddress, uint16_t) {
  // Without a host name there is nothing to verify the certificate against.
  tip::transport::attemptCount++;
  return tip::transport::fail("connect by IP address is not allowed");
}

int WebSocketsNetworkClientSecure::connect(const char *host, uint16_t port) {
  return connect(host, port, tip::kTcpConnectTimeoutMs);
}

int WebSocketsNetworkClientSecure::connect(const char *host, uint16_t port, int32_t timeout) {
  using namespace tip::transport;
  attemptCount++;
  resetStatusLine();
  if (::time(nullptr) < tip::kClockSetAfter) {
    return fail("clock not set");
  }
  NetworkClientSecure &tls = *_impl->secure;
  tls.useBuiltinCACertBundle();
  tls.setHandshakeTimeout(tip::kTlsHandshakeTimeoutS);
  tip::logLine("TLS: connecting to %s:%u", host, port);
  if (!tls.connect(host, port, timeout)) {
    char error[100] = "";
    int code = tls.lastError(error, sizeof(error));
    tip::logLine("TLS: handshake failed: %s (%d)", error, code);
    return fail("TLS handshake failed");
  }
  tip::logLine("TLS: chain verified against the CA bundle, hostname matches");
  if (!chainDatesValid(tls.getPeerCertificate())) {
    tls.stop();
    return fail("certificate date check failed");
  }
  lastOk = true;
  failure = "";
  return 1;
}

size_t WebSocketsNetworkClientSecure::write(uint8_t data) { return _impl->secure->write(data); }

size_t WebSocketsNetworkClientSecure::write(const uint8_t *buf, size_t size) {
  return _impl->secure->write(buf, size);
}

size_t WebSocketsNetworkClientSecure::write(const char *str) {
  return _impl->secure->write(reinterpret_cast<const uint8_t *>(str), strlen(str));
}

int WebSocketsNetworkClientSecure::available() { return _impl->secure->available(); }

int WebSocketsNetworkClientSecure::read() {
  int c = _impl->secure->read();
  if (c >= 0) {
    uint8_t byte = static_cast<uint8_t>(c);
    tip::transport::recordStatusLine(&byte, 1);
  }
  return c;
}

int WebSocketsNetworkClientSecure::read(uint8_t *buf, size_t size) {
  int length = _impl->secure->read(buf, size);
  tip::transport::recordStatusLine(buf, length);
  return length;
}

int WebSocketsNetworkClientSecure::peek() { return _impl->secure->peek(); }

void WebSocketsNetworkClientSecure::flush() { _impl->secure->flush(); }

void WebSocketsNetworkClientSecure::stop() { _impl->secure->stop(); }

uint8_t WebSocketsNetworkClientSecure::connected() { return _impl->secure->connected(); }

WebSocketsNetworkClientSecure::operator bool() { return _impl->secure->connected(); }

// The library calls these to configure trust. They are deliberately ignored: connect() always
// uses the built in bundle, so nothing the library does can turn verification off.

void WebSocketsNetworkClientSecure::setCACert(const char *) {}

void WebSocketsNetworkClientSecure::setCACertBundle(const uint8_t *, size_t) {}

void WebSocketsNetworkClientSecure::setInsecure() {}

bool WebSocketsNetworkClientSecure::verify(const char *, const char *) { return false; }
