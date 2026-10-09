# TiP device protocol

This is a shared contract. The backend, the ESP32 firmware and the virtual panda all follow it. Change it only through a brief from the lead.

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
- One live connection per device. A new authenticated connection closes the old one with close code 4000 ("replaced").
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

### Device to server

```json
{ "type": "ack", "id": "<tipId>" }
```

Ack means "received". The device sends it as soon as a reaction arrives, before playback starts, and also for a repeat it does not play again. Resends come 5 seconds apart and a pattern can run up to 10 seconds, or wait behind others in the queue, so an ack sent after playback would bring resends in the middle of a pattern.

### Delivery rules

- At least once. The server resends an unacknowledged reaction after 5 seconds, up to 3 times.
- The device keeps the last 16 tip ids it has played and does not play repeats again.
- The device acks every reaction it receives, including repeats it does not play again. A lost ack otherwise leads to three needless resends.
- On connect, the server sends unacknowledged reactions from the last 60 seconds, oldest first. Older ones are dropped.
- Playback is queued. A reaction that arrives while another is playing waits and plays after it, in order of arrival (queue size 4, drop oldest when full). It is acked on arrival, not when it plays.
- A reaction that is published while a device is connecting may arrive twice: once live and once from the replay. This is expected. The device acks the repeat and does not play it again. The server keeps its connect order (hello, then register, then replay) on purpose: a rare duplicate costs one small message, while the alternative orderings delay a real reaction by up to 5 seconds.

### Keepalive

- The server sends a WebSocket ping every 20 seconds. The device library answers with pong.
- The server closes connections that miss two pongs.
- The device reconnects with backoff: 1, 2, 4, 8, 16, then 30 seconds, with up to 20 percent random jitter.

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
| Auth rejected (401) | Three quick flicks every 5 s, no reconnect until reboot |

The virtual panda plays the same timings so the video and the judges' experience match.
