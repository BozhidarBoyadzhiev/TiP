# Brief 05b: panda firmware, device protocol and reactions (attended)

Goal: the ESP32 connects to production over verified wss as the demo device,
receives reactions, acks them on receipt, and plays the four light patterns
with repeats, exactly like the virtual panda. It survives WiFi drops, server
restarts and dead connections without anyone touching it. NFC is not in this
brief (05c).

The owner is here for physical steps and the end to end test. Whenever you need
him, stop, say exactly what to do, and wait for "done". Save this brief as
briefs/05b-firmware-reactions.md.

## Read first
CLAUDE.md, CODEBASE.md, firmware/README.md, briefs/05a-firmware-bringup.md and
its report decisions, the devices package (WebSocket endpoint, auth, message
types, ack, replay, ping), the fake device, and the virtual panda JS (04f). The
protocol is whatever the server and fake device do today. Match it exactly.
Don't change server code in this brief. If the server seems wrong or the
protocol is ambiguous, stop and report.

## Decisions already made (from the 05a review)
- Partition: huge_app.csv. Remove the HTTPClient health check from the sketch.
- TLS: built-in Mozilla bundle (useBuiltinCACertBundle or the library's
  equivalent bundle attach), hostname verification, never setInsecure.
- Certificate dates: after the handshake, check notBefore/notAfter of every
  certificate the server presents (leaf and intermediates) against SNTP time.
  Any failure disconnects and counts as a failed attempt. Never connect while
  time is unset (treat a year before 2025 as unset).
- SNTP: pool.ntp.org, time.google.com, time.cloudflare.com. Log clearly while
  waiting for time, so a network that blocks NTP is obvious on serial.
- WiFi sleep stays off.

## WebSocket library
Choose one that (1) uses the built-in CA bundle, (2) gives access to the peer
certificate chain after the handshake so the date check is possible, and
(3) lets the firmware see server pings, or at least time since the last
received frame. Prefer one that runs over NetworkClientSecure. Justify the
choice in the report against the alternatives you looked at. If no library
meets all three, stop and report the options before writing the client.

## Configuration and secrets
- Server host, device slug and timings go in a committed config header.
  Defaults: tip-integrated-panda.onrender.com and demo-panda.
- The device secret goes in firmware/include/secrets.h (already git ignored).
  Add a placeholder to secrets.example.h. The owner fills in the real value
  himself. Never print the secret or any auth header, even partly.

## Behaviour
- Ack on receipt: when a reaction arrives and parses, send the ack
  immediately, before queueing or playing it.
- Duplicates: keep the last 16 reaction ids in a ring. A duplicate (a replay
  after reconnect) is acked again, because the earlier ack may have been lost,
  but not played again.
- Queue: bounded at 8 waiting reactions. If it's full, drop the oldest waiting
  one (not the one playing) and log it, so the newest tipper, who is probably
  standing there, still sees theirs.
- Playback: base units WARM 3, PLAYFUL 2, REQUEST 2, PLAIN 1, plus
  (repeats - 1). Clamp repeats to 1..5. Port the unit timings for each pattern
  from the virtual panda JS, so the physical and virtual panda look the same.
  The longest pattern must come out at 9.17 s. Use LEDC PWM on GPIO2.
- Treat server messages as untrusted input even over TLS: cap the frame size,
  parse with a bounded JSON document, and validate every field. An unknown
  kind is acked and played as PLAIN with repeats 1, with a log line. A
  malformed message is logged and not acked, unless the protocol says
  otherwise.
- Data minimisation: the device needs only an id, a kind and repeats. If the
  server sends the tipper's message text or anything else personal to the
  device, don't store or log it, and flag it in the report as a server change
  for later.
- Dead connection: if nothing has been received from the server for 45 s
  (ping or any frame), close and reconnect.
- Reconnect: exponential backoff with jitter, starting around 1 s and capped
  at 60 s. Reset it only after a connection has stayed up for 60 s, so a
  server that accepts and then drops us immediately doesn't cause a tight
  loop. The same applies after a WiFi drop.
- Concurrency: network handling and playback must not block each other. A
  9 s reaction must not delay acks, pings or reconnects. Use a FreeRTOS queue
  between the network side and a playback task that alone owns the LED.
  Explain in the report which task or core each part runs on and why.
- If the server allows only one connection per device, find out what happens
  when the fake device or a second panda connects as demo-panda, and report it.

## Structure and tests
- Keep protocol parsing, the id ring, the queue policy, the pattern timings
  and the backoff calculation in plain C++ with no Arduino includes, under
  firmware/lib, so they can be unit tested on the host.
- Add a native PlatformIO environment and Unity tests: unit counts and total
  duration for every kind and every repeats tier (including the 9.17 s
  maximum), repeats clamping, duplicate handling (acked, not played), queue
  overflow (oldest waiting dropped, playing untouched), the backoff sequence
  with a seeded jitter source and the 60 s reset rule, and parsing (valid,
  oversized, missing fields, wrong types, unknown kind).
- Native tests on Windows need a host compiler. If none is installed, install
  one with winget, and document it in firmware/README.md.
- Add a firmware job to CI: on ubuntu, copy secrets.example.h to secrets.h,
  run pio run and pio test -e native. Name the job firmware. Don't touch the
  existing build, secrets-scan or deploy jobs.

## End to end (with the owner)
1. Owner fills the real demo device secret into secrets.h. Flash and show the
   connect sequence on serial: WiFi, time, TLS, chain date check, connected.
2. Owner makes a sandbox tip on his phone with a warm message, then one with a
   playful message, a request and no message, with different amounts. For
   each: the serial log shows receipt, ack, then playback with the right kind
   and repeats, and the virtual panda page shows the same reaction.
3. Resilience: owner turns the router or hotspot off for about 30 s, then on.
   The panda reconnects by itself. A tip made while it was offline is replayed
   and played once.
4. Owner unplugs and replugs the board. It boots, reconnects and keeps working.

## Docs
- firmware/README.md: library choice, configuration, secrets, native tests,
  and the TLS statement: full chain and hostname verification against the
  Mozilla bundle, validity dates checked in firmware for the presented chain,
  no revocation checking.
- CODEBASE.md: firmware structure and the device side of the protocol.

## Done when
pio run, pio test -e native and gradlew build all pass, end to end steps 1 to 4
pass, docs are updated, and everything is committed locally on develop, not
pushed.

## Report
The usual format, plus: library choice and why, task and core layout, flash
usage after the partition change, the protocol as implemented (message shapes,
no secrets), any server behaviour that surprised you, the second connection
result, and the serial log lines from the end to end run with nothing secret
in them.
