# TiP owner channel

This is a shared contract between the backend and the owner dashboard page. A change to it must change the server and the dashboard page together.

The owner channel tells a signed in owner's dashboard that something changed, so the page can fetch it. It carries ids and states only, never tip content: no message, translation, note, song, amount, kind or flag. The page then reads the tip through `GET /api/owner/tips/{id}`. So who may see what is decided in one place.

```
wss://<host>/ws/owner
```

## Handshake

- The owner's session cookie (`JSESSIONID`), the same session the dashboard's API calls use. No session, or a session without a signed in owner, gets 401 problem details, like every owner API call. A session of a sandbox past its expiry gets 401 problem details with code `SANDBOX_EXPIRED`, and the session ends. These checks come first, before the `Origin` check.
- A read only session (the public demo dashboard) is upgraded. It is then at once sent `closing` `read_only` and closed. It is never counted among the owner's sockets and hears nothing.
- `Origin` must be sent once and be exactly the origin of `TIP_PUBLIC_BASE_URL` (scheme, host and port). A different, missing or repeated `Origin` gets an empty 403. This blocks cross site WebSocket hijacking: a page on another site cannot open the channel with the owner's cookie.
- No other parameters. The owner comes from the session, never from the URL or a message.

## Server to page

```json
{ "type": "tip", "id": "<tipId>" }
```

One of the owner's tips changed. It was captured, classified (or skipped or failed, so its reaction is final), refunded or reversed. Or the owner changed its verdict: they unhid a flagged message or hid it again. A repeated verdict that changes nothing sends nothing. `GET /api/owner/tips/{id}` has the tip's current state.

```json
{ "type": "today" }
```

Today's numbers may have changed. `GET /api/owner/today` has them. At most one is sent per second per owner. A change marks the owner, if they have a socket open. A job runs every second (`tip.dashboard.today-interval`) and sends one `today` to each marked owner, so a burst of changes in between rides along. The owner is marked before the `tip` message goes out. So the next `today` always covers a tip the page has heard about.

```json
{ "type": "digest" }
```

Today's panda's notes were regenerated, or the owner changed a verdict. Hiding a message again drops the notes. `GET /api/owner/digest` has the notes. It answers `{"status": "rewriting"}` while dropped notes wait to be written again, or `null` when there are none yet.

```json
{ "type": "device", "slug": "busker-panda", "online": false, "lastSeenAt": "2026-10-13T14:02:11Z" }
```

One of the owner's devices changed state. A device is reported offline only after it has been gone 10 seconds. So a replaced connection or a quick reconnect never shows as offline. `online: true` comes when a device connects and was reported offline before, or when nothing was said about it since the server started. `lastSeenAt` is the connect time when online and the moment it went when offline. It is ISO 8601 UTC, to the second. `GET /api/owner/devices` gives the state when the page loads.

```json
{ "type": "closing", "reason": "signed_out" }
```

Sent just before the server closes a socket for a reason the page should act on. Close codes do not reach the browser through Render's proxy: they arrive as 1005. So this message is the only way the page learns why.

| `reason` | Close code | When | What the page should do |
|---|---|---|---|
| `replaced` | 4000 | the owner opened a sixth socket; the oldest goes | do not reconnect, the owner has a newer tab |
| `signed_out` | 4001 | the owner signed out, in any tab | show the sign in page |
| `session_expired` | 4002 | the HTTP session no longer holds a signed in owner, found at a keepalive | show the sign in page |
| `sandbox_expired` | 4003 | the session's sandbox reached its expiry, found at a keepalive | show that the sandbox has ended; do not reconnect |
| `read_only` | 4004 | the session is the read only public demo, right after the upgrade | do not reconnect; the page works without live updates |

Other closes come without `closing`:

- 1008 from the servlet container, the moment the HTTP session expires or is invalidated.
- 1001 after 45 seconds without a pong, or when the server shuts down.
- 1009 for an oversized message.

After those the page reconnects with the client policy of `docs/DEVICE_PROTOCOL.md`. That is 1 s doubling to 60 s, up to 20 % taken off, and a reset only after 60 s connected. A handshake that answers 401 means the session is gone.

Unknown `type` values must be ignored, so the server can add messages later.

## Page to server

Nothing. Whatever the page sends is ignored. A message over 1 KB (1024 bytes) closes the socket with 1009, as on the other channels.

## Limits

- At most 5 sockets per owner. A new one closes the oldest with `closing` `replaced`. The public demo's read only sessions never take a place.
- Keepalive like the other channels: a ping every 20 seconds. A socket is closed with 1001 after 45 seconds without a pong.
- A send must finish within 5 seconds, and at most 64 KB may wait unsent. A socket past either limit gets no more messages or pings, so it misses its pongs and is closed with 1001.
- Signing out closes every socket of the owner, in every tab and session, with `closing` `signed_out`.
- Sockets live in memory, like the device channels, so the backend runs as one instance.
