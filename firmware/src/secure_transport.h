// The TLS transport under the WebSocket library (its NETWORK_CUSTOM mode). Every connection is
// verified against ESP-IDF's built in Mozilla CA bundle with the hostname checked, and the validity
// dates of every certificate the server presents are checked against SNTP time before the library
// sends anything, so the device secret never goes to a server whose chain fails the check.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace tip {
namespace transport {

// Counts TLS connection attempts, so the network task can tell that one ended without the
// library raising an event.
uint32_t attempts();
bool lastAttemptSucceeded();
// Why the last attempt failed, for the log.
const char *lastFailure();
// The status code of the HTTP answer to the last handshake, 0 before it has arrived.
int handshakeStatus();

}  // namespace transport
}  // namespace tip
