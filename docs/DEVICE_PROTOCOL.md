# TiP device protocol

This is a shared contract. The backend, the ESP32 firmware and the virtual panda all follow it. Change it only through a brief from the lead. The owner dashboard's live channel is a separate contract, `docs/OWNER_CHANNEL.md`.

## NFC tag

The NTAG215 on the coin holds one NDEF URI record:

```
https://<host>/t/{deviceSlug}
```

`deviceSlug` is lowercase letters, digits and hyphens, 3 to 40 characters. Phones open it in the browser without an app.

v2 (stretch): the ESP32 rewrites the tag through the PN532 after every tip with `https://<host>/t/{deviceSlug}?k={tapToken}`, where `tapToken` is a one time value issued by the backend. Not part of v1.

## Device channel (authenticated)

Used by the physical panda.

```
wss://<host>/ws/device/{deviceId}
Authorization: Bearer <deviceSecret>
```

- `deviceId` is the device uuid. `deviceSecret` is 32 random bytes, base64url encoded, shown once when the device is created.
- The server rejects the handshake with 401 if the secret does not match. No details in the response.
- One live connection per device. A new authenticated connection closes the old one with close code 4000 ("replaced"), after a `closing` message with reason `replaced`.
- The firmware verifies the server certificate with the ESP32 CA bundle. It never disables certificate checks.

### Server to device

```json
{ "type": "reaction", "id": "<tipId>", "reaction": "WARM", "repeats": 3, "at": "2026-10-13T14:02:11Z" }
```

`reaction` is one of `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`, the same values on both channels. A flagged message always arrives as `PLAIN`.

`repeats` is an integer from 1 to 5 that makes the pattern longer for bigger tips (see LED patterns). The server always sends it and stores it with the delivery, so a resend or a replay carries the same value. The server takes the amount charged in the major unit of its currency, rounds it half up to a whole number and maps it:

| Rounded amount | `repeats` |
|---|---|
| 1 or less | 1 |
| 2 | 2 |
| 3 to 4 | 3 |
| 5 to 9 | 4 |
| 10 and up | 5 |

A flagged tip always has `repeats` 1, whatever the amount, so it looks exactly like a plain 1 pound tip. A device treats a missing `repeats` as 1 and clamps values outside 1 to 5 instead of rejecting the message.

```json
{ "type": "hello", "deviceName": "Busker Panda", "serverTime": "2026-10-13T14:00:00Z" }
```

Sent once after a successful handshake.

```json
{ "type": "closing", "reason": "replaced" }
```

Sent just before the server closes a device session for a reason the device should act on; the close follows at once with the same close code as before. Close codes do not reach clients through Render's proxy (they arrive as 1005, no status), so this message is the only way the device learns why. Observers never get `closing`.

| `reason` | Close code | When |
|---|---|---|
| `replaced` | 4000 | another connection authenticated as the same device |
| `timeout` | 1001 | no pong for 45 seconds |
| `shutdown` | 1001 | the server is shutting down, for example during a deploy |

### Device to server

```json
{ "type": "ack", "id": "<tipId>" }
```

Ack means "received", not "played". The device acks every reaction it receives, as soon as it arrives, including a repeat it does not play again. Resends come 5 seconds apart and a pattern can run up to 10 seconds, or wait behind others in the queue, so an ack sent after playback would bring resends in the middle of a pattern, and a repeat left unacked would bring three needless resends.

### Delivery rules

- At least once. The server resends an unacknowledged reaction after 5 seconds, up to 3 times.
- On connect, the server sends unacknowledged reactions from the last 60 seconds, oldest first. Older ones are dropped.
- A reaction that is published while a device is connecting may arrive twice: once live and once from the replay. This is expected. The server keeps its connect order (hello, then register, then replay) on purpose: a rare duplicate costs one small message, while the alternative orderings delay a real reaction by up to 5 seconds.

### Keepalive

- The server sends a WebSocket ping every 20 seconds. The device's WebSocket library answers with pong.
- The server closes connections that miss two pongs, after a `closing` message with reason `timeout`.

## Observer channel (public, read only)

Used by the virtual panda and any public display.

```
wss://<host>/ws/observe/{deviceSlug}
```

- No authentication. Max 5 connections per client IP.
- Receives only:

```json
{ "type": "reaction", "reaction": "WARM", "repeats": 3, "at": "2026-10-13T14:02:11Z" }
```

- `repeats` is the same value the device gets, so the virtual panda plays what the LED plays. It shows the tier of a tip publicly on purpose: the people standing around should see a big tip land. Missing means 1, out of range is clamped, as on the device channel.
- No tip id, amount, message, message kind, flag or payer data. Observers never send anything. Messages from observers are ignored.

## Client policy

What the clients decide for themselves, within the protocol above. The physical panda (`firmware/`), the fake device (`./gradlew fakeDevice`) and the virtual panda (`static/js/panda.js`) follow it, so the three behave alike; where one differs, it says so.

- Repeats: a device keeps the last 16 tip ids and does not play one of them again (it still acks it). The virtual panda gets no ids and plays what arrives.
- Queue: a reaction that arrives while another plays waits and plays after it, in order of arrival. Up to 8 wait behind the one playing. When the queue is full, the oldest waiting reaction is dropped, never the one playing, so the newest tipper, who is probably standing there, still sees theirs.
- Reconnect: 1 second, doubling to a cap of 60 seconds (1, 2, 4, 8, 16, 32, 60), each delay shortened by up to 20 percent at random. The backoff starts over only after a connection stayed up 60 seconds, so a server that accepts and drops at once cannot cause a tight loop.
- Replaced: after `closing` with reason `replaced`, a device waits at least 60 seconds before reconnecting, so two clients with the same identity settle instead of replacing each other every second. Any other or unknown `reason` uses the normal backoff.
- Dead connection: the physical panda closes and reconnects when nothing (a ping or any frame) has arrived for 45 seconds.
- Rejected (401): the physical panda logs it, shows the rejected status pattern and tries again every 10 minutes, so a corrected secret on the server is picked up without anyone touching the device. The fake device exits instead.
- Unknown kind: a device acks it and plays `PLAIN` with repeats 1, like a plain tip. The virtual panda, which sends no acks, ignores it.

## LED patterns

Single colour LED driven with PWM (ESP32 LEDC, 8 bit duty).

Each pattern is a unit played a number of times with a fixed gap between units. With `repeats` 1 a pattern plays its base number of units; each extra repeat adds one unit: the device plays `base + (repeats - 1)` units.

| Reaction | Unit | Gap between units | Base units |
|---|---|---|---|
| `WARM` | Heartbeat. Fade 0 to 255 over 400 ms, 255 to 0 over 600 ms, pause 200 ms (1.2 s). | none, the unit ends in its pause | 3 |
| `PLAYFUL` | Giggle. Six quick flicks, each rising over 60 ms and falling over 90 ms with a 70 ms gap, peaks 255, 140, 230, 110, 255, 170 (1.32 s). | 250 ms | 2 |
| `REQUEST` | Noted. Fade 0 to 255 over 300 ms, hold 500 ms, fade to 0 over 300 ms (1.1 s). | 300 ms | 2 |
| `PLAIN` | One solid blink. Full on 700 ms, then off. | 300 ms, so repeated blinks are distinguishable | 1 |

Total length by `repeats`:

| Reaction | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| `WARM` | 3.6 s (3 units) | 4.8 s | 6.0 s | 7.2 s | 8.4 s (7 units) |
| `PLAYFUL` | 2.89 s (2 units) | 4.46 s | 6.03 s | 7.6 s | 9.17 s (6 units) |
| `REQUEST` | 2.5 s (2 units) | 3.9 s | 5.3 s | 6.7 s | 8.1 s (6 units) |
| `PLAIN` | 0.7 s (1 unit) | 1.7 s | 2.7 s | 3.7 s | 4.7 s (5 units) |

No pattern runs longer than 10 seconds, so no gap had to be shortened. With `repeats` 1 the patterns are exactly the original ones.

The three long patterns stay apart on a single colour LED by their shape: `WARM` is smooth and slow, `PLAYFUL` quick and jittery, `REQUEST` long flat holds. Abuse has no pattern of its own: a flagged message plays `PLAIN`, so a troll gets nothing worth filming.

Status patterns, only when no reaction is playing:

| State | Pattern |
|---|---|
| Connecting to WiFi or server | Short 30 ms flick every 1 s |
| Connected and idle | Off |
| Auth rejected (401) | Three quick flicks every 5 s, until a connection succeeds; retries every 10 minutes |

The virtual panda plays the same timings so the video and the judges' experience match.
