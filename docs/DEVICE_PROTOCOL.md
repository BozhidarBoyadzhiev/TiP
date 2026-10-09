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
{ "type": "reaction", "id": "<tipId>", "reaction": "WARM", "at": "2026-10-13T14:02:11Z" }
```

`reaction` is one of `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`, the same values on both channels. A flagged message always arrives as `PLAIN`.

```json
{ "type": "hello", "deviceName": "Busker Panda", "serverTime": "2026-10-13T14:00:00Z" }
```

Sent once after a successful handshake.

### Device to server

```json
{ "type": "ack", "id": "<tipId>" }
```

Sent when the device starts playing a reaction, and right away for a repeat it does not play again.

### Delivery rules

- At least once. The server resends an unacknowledged reaction after 5 seconds, up to 3 times.
- The device keeps the last 16 tip ids it has played and does not play repeats again.
- The device acks every reaction it receives, including repeats it does not play again. A lost ack otherwise leads to three needless resends.
- On connect, the server sends unacknowledged reactions from the last 60 seconds, oldest first. Older ones are dropped.
- If a reaction arrives while another is playing, the device queues it (queue size 4, drop oldest when full).

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
{ "type": "reaction", "reaction": "WARM", "at": "2026-10-13T14:02:11Z" }
```

- No tip id, amount, message or payer data. Observers never send anything. Messages from observers are ignored.

## LED patterns

Single colour LED driven with PWM (ESP32 LEDC, 8 bit duty).

| Reaction | Pattern | Total length |
|---|---|---|
| `WARM` | Heartbeat. Fade 0 to 255 over 400 ms, 255 to 0 over 600 ms, pause 200 ms. Repeat 3 times. | 3.6 s |
| `PLAYFUL` | Giggle. Six quick flicks, each rising over 60 ms and falling over 90 ms with a 70 ms gap, peaks 255, 140, 230, 110, 255, 170. Pause 250 ms, then the six flicks again. | about 2.9 s |
| `REQUEST` | Noted. Fade 0 to 255 over 300 ms, hold 500 ms, fade to 0 over 300 ms, pause 300 ms. Repeat once. | 2.5 s |
| `PLAIN` | One solid blink. Full on 700 ms, then off. | 0.7 s |

The three long patterns stay apart on a single colour LED by their shape: `WARM` is smooth and slow, `PLAYFUL` quick and jittery, `REQUEST` long flat holds. Abuse has no pattern of its own: a flagged message plays `PLAIN`, so a troll gets nothing worth filming.

Status patterns, only when no reaction is playing:

| State | Pattern |
|---|---|
| Connecting to WiFi or server | Short 30 ms flick every 1 s |
| Connected and idle | Off |
| Auth rejected (401) | Three quick flicks every 5 s, no reconnect until reboot |

The virtual panda plays the same timings so the video and the judges' experience match.
