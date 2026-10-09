# Brief 03: Device channels and the virtual panda

## Overview

Brief 02 publishes `TipReacted` once per captured tip. This brief delivers that reaction to the physical panda over an authenticated WebSocket, and to anyone watching a public observer channel. It also builds the virtual panda page and a fake device client, so the whole flow works end to end before the ESP32 arrives.

`docs/DEVICE_PROTOCOL.md` is the contract. Implement it exactly. If something in it cannot be implemented as written, stop and raise it as an open question.

Start a fresh session. Read `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/DEVICE_PROTOCOL.md` and `CODEBASE.md` first.

Commit in small logical steps this time: schema, device channel, delivery, observer channel, keepalive, virtual panda, fake device, docs. Each commit green.

## Answers to the Brief 02 questions

- A reaction is still published when the tip was refunded or reversed while classification ran. Keep the current behaviour.
- Delete the placeholder owner (`unclaimed@tip.invalid`) in this brief's migration if it owns no devices.

## Part 0: Device secret fixes

The README's secret command fails in Windows PowerShell 5.1, because `RandomNumberGenerator.Fill` only exists in newer .NET. The failure is silent enough that the command still prints 43 `A` characters: a secret of 32 zero bytes, which the seeder accepted.

1. Replace the README command with one that works in Windows PowerShell 5.1 and PowerShell 7:
   ```powershell
   $b = New-Object byte[] 32; $r = [Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); $r.Dispose(); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
   ```
   Add the equivalent for macOS and Linux: `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`.
2. The seeder refuses to start when the decoded secret is clearly not random: all bytes identical, or fewer than 16 distinct byte values among the 32. The error message says how to generate a proper one. Test both rejections and that a real random secret passes.
3. Problem details for 404 currently leak framework internals, for example `"detail":"No static resource t/owner/login."`. Every 404 should carry a fixed detail such as "The requested resource was not found.", whatever raised it. Apply the same rule to 405 and any other framework raised error: fixed, generic detail text, our own `code`. Test that an unknown path's body contains no framework wording.

## Part 1: Schema (`V3__reaction_delivery.sql`)

Delivery state belongs to `devices`, not `tipping`, so it gets its own table instead of more columns on `tip`:

```
reaction_delivery
  tip_id        uuid primary key
  device_id     uuid not null references device(id)
  reaction      varchar(10) not null (check: POSITIVE, NEUTRAL, NEGATIVE)
  reacted_at    timestamptz not null
  sent_count    int not null default 0
  last_sent_at  timestamptz null
  acked_at      timestamptz null
```

Partial index on `(device_id, reacted_at)` where `acked_at is null`.

Plus the placeholder owner cleanup.

## Part 2: Device channel

`wss://<host>/ws/device/{deviceId}` with `Authorization: Bearer <deviceSecret>`, using plain Spring WebSocket (`TextWebSocketHandler`). No STOMP or SockJS. The ESP32 speaks raw WebSocket.

### Handshake

- A `HandshakeInterceptor` parses `deviceId` as a uuid and the bearer token, then calls `DeviceSecretVerifier`. Any failure returns HTTP 401 before the upgrade, with no body detail. Malformed id, missing header, wrong secret and unknown device all look the same.
- Failed handshakes limited to 10 per minute per IP (429). The secret cannot be brute forced, but each attempt costs a hash and a database read.
- Origin: leave Spring's default same origin check. The ESP32 sends no `Origin` header, which Spring allows. Browsers cannot set an `Authorization` header on a WebSocket, so cross site WebSocket hijacking cannot reach this endpoint. Write that reasoning as a short comment on the config.
- `/ws/**` is permitted in the security chain. Authentication for these endpoints lives in the interceptors.

### Session registry

- `ConcurrentHashMap<UUID, WebSocketSession>`. Every stored session is wrapped in `ConcurrentWebSocketSessionDecorator` (send time limit 5 s, buffer 64 KB). Raw `WebSocketSession.sendMessage` is not thread safe, and the dispatcher, resend job, keepalive and connect replay can all send at once.
- Replacement: register with `put`, and if it returns an old session, close the old one with code 4000 ("replaced"). On close, remove with `remove(deviceId, session)` so a late close of the old session can never evict the new one. Test this race.
- On connect, send `hello`, then replay (Part 3).
- Inbound text messages over 1 KB close the session with 1009. Invalid JSON or unknown `type` is ignored and logged at DEBUG. After 20 invalid messages in a minute, close with 1008.
- Log connect and disconnect at INFO with the device id and close code. Never the secret.

## Part 3: Delivery

### Dispatcher

`@TransactionalEventListener` on `TipReacted` (after commit):

1. Insert the `reaction_delivery` row with `ON CONFLICT (tip_id) DO NOTHING`. If nothing was inserted, the event is a duplicate: stop.
2. If the device is connected, send the `reaction` message and record the send (`sent_count + 1`, `last_sent_at`).
3. Broadcast to the device's observers (Part 4).

### Ack

`{"type":"ack","id":"<tipId>"}` sets `acked_at` with a conditional update `WHERE tip_id = :id AND device_id = :sessionDevice AND acked_at IS NULL`. A device can never ack another device's tip. Unknown ids are ignored.

### Resend

A job every second, for connected devices only: rows with `acked_at` null, `sent_count < 4`, `last_sent_at` older than 5 s and `reacted_at` within the last 60 s get sent again. That is the first send plus up to 3 resends.

### Replay on connect

Unacked rows for that device with `reacted_at` in the last 60 s, oldest first. Each replay counts as a send.

Make the jobs and the dispatcher take the injected `Clock` so tests drive time directly instead of sleeping.

## Part 4: Observer channel

`wss://<host>/ws/observe/{deviceSlug}`, public and read only.

- Handshake rejects an unknown or malformed slug with 404, and a 6th concurrent connection from one IP with 429.
- Allowed origin: only `TIP_PUBLIC_BASE_URL`. The virtual panda is served from our own origin.
- Registry: `ConcurrentHashMap<String, Set<WebSocketSession>>` with concurrent sets, sessions wrapped in the decorator. Clean up empty sets.
- Observers receive only `{"type":"reaction","reaction":"...","at":"..."}`. No tip id, amount, message, flag or payer data. Inbound messages are ignored. Over 1 KB closes with 1009.
- Observers get live reactions only, no replay.

## Part 5: Keepalive and shutdown

- Every 20 s, ping every device and observer session. Track the last pong per session. Close sessions with no pong for 45 s (two missed pings) with 1001.
- On application shutdown, close all sessions with 1001 so devices reconnect cleanly after a deploy.

## Part 6: Virtual panda

`GET /p/{slug}` serves `static/panda.html`. This is what judges and the demo video use, so it should look good.

- A cardboard style low poly panda holding a coin, in SVG, in keeping with the physical one: brown and dark brown card tones, visible fold lines. The "LED" is a glowing point on the coin.
- Plays the three patterns from `DEVICE_PROTOCOL.md` with the exact timings, driven by `requestAnimationFrame`, with a queue of 4 (drop oldest).
- Under the panda, a short caption after each reaction: "Kind message", "Rude message", "Tip received".
- Connection status in a corner: connecting, live, reconnecting. Reconnect with the protocol's backoff and jitter.
- Respects `prefers-reduced-motion`: it still shows the colour state, without the strobe. Add a short line in the README that the strobe can be disabled this way.
- The tip page success screen links to "Watch the panda" at `/p/{slug}`.
- Check the CSP allows the WebSocket connection (`connect-src`). Verify in a real browser.

## Part 7: Fake device

A command line client that behaves exactly like the ESP32 will, so the protocol can be tested end to end on a laptop:

```
./gradlew fakeDevice --args="--url=ws://localhost:8081 --device=<uuid> --secret=<secret>"
```

- Lives in its own source set (`tools`), not in the app jar.
- Uses the JDK `HttpClient` WebSocket API.
- Prints each reaction with a text version of the LED pattern, sends the ack, keeps the last 16 ids for de-duplication, and reconnects with the protocol backoff.
- The README explains how to get the demo device id (an owner endpoint or a log line at startup, your choice, but never the secret).

## Tests (required)

Real WebSocket clients (`StandardWebSocketClient`) against a random port, Testcontainers PostgreSQL, fixed `Clock`.

1. Handshake: correct secret connects and receives `hello`. Wrong secret, unknown device, missing header and malformed id all get 401 and look identical.
2. 11th failed handshake in a minute from one IP gets 429.
3. Replacement: a second connection closes the first with 4000; the first session's late close does not remove the second from the registry.
4. A reaction reaches a connected device with the right shape.
5. Ack stops resends. Without an ack, exactly 3 resends 5 s apart, then none.
6. A device acking another device's tip changes nothing.
7. Reconnect replays only unacked reactions newer than 60 s, oldest first.
8. A duplicate `TipReacted` creates one delivery row and one send.
9. Concurrency: 50 reactions published from 10 threads to one device all arrive, with no exception from concurrent sends.
10. Observer receives the reaction with only `type`, `reaction` and `at`.
11. Observer: unknown slug gets 404, 6th connection from one IP gets 429, wrong origin is rejected.
12. Inbound messages from observers are ignored. Oversize messages close with 1009 on both channels.
13. Keepalive: a session that stops answering pings is closed after 45 s (driven by the clock).
14. Placeholder owner removed when it owns no devices.
15. ArchUnit still green: `devices` reads tips only through `tipping.api` or events.

## Do

- Update `docs/ARCHITECTURE.md`: the `reaction_delivery` table, the registry and replacement race, and the delivery flow. Leave `DEVICE_PROTOCOL.md` unchanged unless you raised a question.
- README: a "See it work without hardware" section. Run the app, open `/p/demo-panda` on a laptop, run the fake device in a terminal, tip from a phone, watch both react.
- Update `CODEBASE.md`.

## Do not

- No firmware. That is Brief 04 on the 13th.
- No dashboard work.
- Do not change the device protocol on your own.
- Do not push.

## Report

Format in `CLAUDE.md`.