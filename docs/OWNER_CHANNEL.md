# TiP owner channel

This is a shared contract between the backend and the owner dashboard page. Change it only through a brief from the lead.

The owner channel tells a signed in owner's dashboard that something changed, so the page can fetch it. It carries ids and states only, never tip content: no message, translation, note, song, amount, kind or flag. The page then reads the tip through `GET /api/owner/tips/{id}`, so who may see what is decided in one place.

```
wss://<host>/ws/owner
```

## Handshake

- The owner's session cookie (`JSESSIONID`), the same session the dashboard's API calls use. No session, or a session without a signed in owner: 401 problem details, like every owner API call.
- `Origin` must be exactly the origin of `TIP_PUBLIC_BASE_URL` (scheme, host and port). A different or missing `Origin` gets an empty 403. This blocks cross site WebSocket hijacking: a page on another site cannot open the channel with the owner's cookie.
- No other parameters. The owner comes from the session, never from the URL or a message.

## Server to page

```json
{ "type": "tip", "id": "<tipId>" }
```

One of the owner's tips was captured, classified (or skipped or failed, so its reaction is final), refunded or reversed, or the owner changed its verdict (unhid a flagged message or hid it again). `GET /api/owner/tips/{id}` has its current state.

```json
{ "type": "today" }
```

Today's numbers may have changed; `GET /api/owner/today` has them. At most one per second per owner: a change marks the owner, and a job that runs every second sends one `today` to each marked owner, so a burst of changes in between rides along. The owner is marked before the `tip` message goes out, so the next `today` always covers a tip the page has heard about.

```json
{ "type": "digest" }
```

Today's panda's notes were regenerated, or dropped because the owner hid a message again; `GET /api/owner/digest` has them, or `null`.

```json
{ "type": "device", "slug": "busker-panda", "online": false, "lastSeenAt": "2026-10-13T14:02:11Z" }
```

One of the owner's devices changed state. A device is reported offline only after it has been gone 10 seconds, so a replaced connection or a quick reconnect never shows as offline. `online: true` comes when a device connects and was reported offline before (or nothing was said about it since the server started). `lastSeenAt` is the connect time when online and the moment it went when offline, ISO 8601 UTC to the second. `GET /api/owner/devices` gives the state when the page loads.

```json
{ "type": "closing", "reason": "signed_out" }
```

Sent just before the server closes a socket for a reason the page should act on. Close codes do not reach the browser through Render's proxy (they arrive as 1005), so this message is the only way the page learns why.

| `reason` | Close code | When | What the page should do |
|---|---|---|---|
| `replaced` | 4000 | the owner opened a sixth socket; the oldest goes | do not reconnect, the owner has a newer tab |
| `signed_out` | 4001 | the owner signed out, in any tab | show the sign in page |
| `session_expired` | 4002 | the HTTP session no longer holds a signed in owner, found at a keepalive | show the sign in page |

Other closes come without `closing`: 1008 from the servlet container the moment the HTTP session expires or is invalidated, 1001 after 45 seconds without a pong or when the server shuts down, 1009 for an oversized message. After those the page reconnects with the client policy of `docs/DEVICE_PROTOCOL.md` (1 s doubling to 60 s, up to 20 % taken off); a handshake that answers 401 means the session is gone.

Unknown `type` values must be ignored, so the server can add messages later.

## Page to server

Nothing. Whatever the page sends is ignored, and a message over 1 KB closes the socket with 1009, as on the other channels.

## Limits

- At most 5 sockets per owner. A new one closes the oldest with `closing` `replaced`.
- Keepalive like the other channels: a ping every 20 seconds, closed with 1001 after 45 seconds without a pong.
- Signing out closes every socket of the owner, in every tab and session, with `closing` `signed_out`.
- Sockets live in memory, like the device channels, so the backend runs as one instance.
