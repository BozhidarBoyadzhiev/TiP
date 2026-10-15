// The ESP-IDF task watchdog, so a call that never returns (a stuck TLS read, a driver hang)
// restarts the board instead of leaving the panda dark until someone power cycles it. The net,
// led and nfc tasks each watch themselves and feed it on every pass of every wait loop; one that
// goes kTaskWatchdogMs without feeding it triggers a panic and a restart.
#pragma once

namespace tip {

// Sets the watchdog's timeout to kTaskWatchdogMs and logs why the board last reset. Call in
// setup(), before the tasks start.
void watchdogBegin();

// Subscribes the calling task. Call once at the start of a task's loop.
void watchdogWatch();

// Tells the watchdog the calling task is alive.
void watchdogFeed();

}  // namespace tip
