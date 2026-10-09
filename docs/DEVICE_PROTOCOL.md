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
{ "type": "reaction", "id": "<tipId>", "reaction": "POSITIVE", "at": "2026-10-13T14:02:11Z" }
```

`reaction` is one of `POSITIVE`, `NEUTRAL`, `NEGATIVE`.

```json
{ "type": "hello", "deviceName": "Busker Panda", "serverTime": "2026-10-13T14:00:00Z" }
```

Sent once after a successful handshake.

### Device to server

```json
{ "type": "ack", "id": "<tipId>" }
```

Sent after the device starts playing a reaction.

### Delivery rules

- At least once. The server resends an unacknowledged reaction after 5 seconds, up to 3 times.
- The device keeps the last 16 tip ids it has played and ignores repeats.
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
{ "type": "reaction", "reaction": "POSITIVE", "at": "2026-10-13T14:02:11Z" }
```

- No tip id, amount, message or payer data. Observers never send anything. Messages from observers are ignored.

## LED patterns

Single colour LED driven with PWM (ESP32 LEDC, 8 bit duty).

| Reaction | Pattern | Total length |
|---|---|---|
| `POSITIVE` | Heartbeat. Fade 0 to 255 over 400 ms, 255 to 0 over 600 ms, pause 200 ms. Repeat 3 times. | about 3.6 s |
| `NEGATIVE` | Harsh strobe. Full on 60 ms, off 60 ms. 12 flashes, pause 250 ms, 12 more. | about 3.1 s |
| `NEUTRAL` | One solid blink. Full on 700 ms, then off. | 0.7 s |

Status patterns, only when no reaction is playing:

| State | Pattern |
|---|---|
| Connecting to WiFi or server | Short 30 ms flick every 1 s |
| Connected and idle | Off |
| Auth rejected (401) | Three quick flicks every 5 s, no reconnect until reboot |

The virtual panda plays the same timings so the video and the judges' experience match.
