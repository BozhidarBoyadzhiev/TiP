# Codebase map

Spring Boot 4 monolith, package by feature under `dev.tip`. A feature uses another feature only through its `api` subpackage or Spring events; `ArchitectureTest` enforces it.

## Runtime and deployment

Runs on Render as one Docker container (`Dockerfile`: exec form `java -jar`, so the JVM is PID 1 and gets `SIGTERM`; `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`). `application.properties` listens on `PORT` (8080 without it; `SERVER_PORT` wins), caps the Hikari pool at `TIP_DB_POOL_SIZE` (5), and uses `server.forward-headers-strategy=native`: Tomcat's `RemoteIpValve` trusts `X-Forwarded-*` only from private addresses and takes the rightmost untrusted `X-Forwarded-For` entry as the client, so `getRemoteAddr()` is the real client for every rate limit, HTTP and WebSocket alike, and a forwarded `https` request is secure. Health probes are off, so `/actuator/health` answers only `{"status":"UP"}` (database, disk space, ping, ssl; never PayPal or Anthropic). `docs/DEPLOYMENT.md` has the variables, health path, deploy flow and smoke test.

## Packages

| Package | What lives there |
|---|---|
| `dev.tip` | `TipApplication` (scheduling and config properties scan on) |
| `shared` | `TipProperties` (`TIP_*` config), `ClockConfig` (UTC `Clock`), `SecurityConfig` (fallback filter chain: public paths, deny the rest with 403), `SecurityDefaults` (CSP, headers and problem details 401/403 handlers every chain applies), `ProblemWriter` (problem details outside MVC), `CspNonce` (per request CSP nonce filter), `DomainException` + `ProblemDetailsAdvice` (RFC 9457 errors), `GenericProblems` (fixed detail and code for framework raised errors such as 404 and 405), `ProblemErrorController` (replaces Boot's `/error`) |
| `owners` | `Owner` entity, `OwnerRepository`, `OwnerService` (the only `UserDetailsService`, implements `OwnerProvisioning`), `PasswordConfig` (`DelegatingPasswordEncoder`), `OwnerSecurityConfig` (form login chain for `/owner/**` and `/api/owner/**`), `LoginRateLimiter` + `LoginRateLimitFilter` (5 per minute per IP and per email), `OwnerPageController`, `OwnerEmails` (lowercase) |
| `owners.api` | `OwnerPrincipal` (signed in owner, read with `@AuthenticationPrincipal`), `OwnerProvisioning` (create owner if missing) |
| `devices` | `Device` entity (owner, secret hash), `DeviceRepository`, `DeviceService`, `DeviceSecrets` (decode, SHA-256, randomness checks), `DefaultDeviceSecretVerifier`, `DemoDeviceSeeder` (logs the demo device id), `DemoDeviceProperties`, `DemoOwnerProperties`, `DeviceController`, `PandaPageController` |
| `devices` channels | `WebSocketConfig` (raw WebSocket endpoints, delivery executor, container buffers), `DeviceHandshakeInterceptor` (bearer secret, uniform 401), `HandshakeFailureLimiter` (10 failed handshakes per minute per IP), `DeviceChannelHandler` (hello, replay, ack, size and invalid message limits), `DeviceSessions` (one session per device, replacement with 4000), `ObserverHandshakeInterceptor` (slug 404, `ObserverConnectionLimiter` 5 per IP), `ObserverChannelHandler`, `ObserverSessions` (concurrent sets by slug), `SessionKeepalive` (pings every 20 s, closes after 45 s without a pong, closes all on shutdown), `ProblemHandshakeHandler` (problem details for non upgrade requests), `ChannelMessages` (protocol JSON), `InvalidMessages`, `ClientIps` (the handshake's resolved client IP), `Uuids` (strict lowercase uuid) |
| `devices` delivery | `ReactionDispatcher` (after commit `TipReacted` listener on the delivery executor), `ReactionDeliveryRepository` (insert, send, ack, replay and resend queries, each its own transaction), `ReactionSender` (send and record), `ReactionResendJob` (every second), `PendingReaction` |
| `devices.api` | `DeviceDirectory` (lookup by slug, ownership check), `DeviceSecretVerifier` (constant time secret check, for Brief 03), `DeviceSummary`, `DeviceSlug` (slug pattern), `DeviceNotFoundException` |
| `paypal` | `PayPalConfig` (SDK client, OkHttp host rewrite to `PAYPAL_BASE_URL`, `RestClient`), `PayPalOrders` (create, capture, get via SDK), `PayPalRefunds` (capture refund via SDK), `WebhookSignatureVerifier` (`RestClient`), `PayPalAccessTokens` (shares the SDK OAuth token), `DefaultPayPalGateway`, `PayPalProperties` |
| `paypal.api` | `PayPalGateway`, `CreateOrderCommand`, `CreateOrderResult`, `CaptureResult`, `RefundResult`, `WebhookTransmission`, `WebhookVerification`, `PayPalPublicConfig` |
| `tipping` | `Tip` entity, `TipStatus`, `TipRepository` (conditional updates), `TipTransitions` (only place status changes, publishes `TipCaptured`), `TipService`, `TipController`, `TipRefundService`, `TipRefundController`, `PayPalWebhookController`, `PayPalWebhookProcessor`, `PayPalWebhookEvent`, `TipRateLimiter`, `AbandonedTipsJob`, `MessageSanitizer`, `TipPageController`, `TipExceptions` |
| `tipping.api` | `TipCaptured(tipId, deviceId, capturedAt)` event |
| `ai` | `AiConfig` (`@EnableAsync`, `aiExecutor` virtual threads, Anthropic client only when enabled), `AiProperties`, `TipCapturedListener` (async, after commit), `TipReactionWorker` (claim, classify or skip, finish), `AiTipRepository` (conditional updates on the tip's AI columns), `TipReactions` (final write, publishes `TipReacted`; its `Outcome` decides reaction and repeats together), `ReactionRepeats` (amount to repeats 1 to 5, flagged always 1), `AiRecoveryJob` (every 30 s), `MessageClassifier`, `ClaudeMessageClassifier` (structured output, prompt with the flagging rule and boundary examples), `ClassificationParser` (validates the result: kind, flag, owner note only on `REQUEST`, at most 100 characters), `MessageKind` (what the model read), `AiStatus`, `EvalClassifier` (public seam for the `aiEval` tool only: binds `tip.ai.*` and runs the production classifier) |
| `ai.api` | `TipReacted(tipId, deviceId, reaction, repeats, reactedAt)` event (repeats 1 to 5), `Reaction` (`WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`) |

## Security filter chains

1. `OwnerSecurityConfig` (order 1) matches `/owner`, `/owner/**`, `/api/owner/**`. Form login, CSRF on, request cache off. Unauthenticated pages redirect to `/owner/login`; the API answers 401 problem details.
2. `SecurityConfig` (lowest order) matches everything else. Public paths are listed (including `/p/**` and `/ws/**`, whose handshake interceptors do their own checks); the rest is 403 problem details. CSRF ignored for `/api/public/**` and `/webhooks/**`.

Both apply `SecurityDefaults`: CSP with nonce (`connect-src` also names the request's own `ws:`/`wss:` origin), HSTS, referrer policy, problem details for 403 (`FORBIDDEN`, `CSRF_TOKEN_INVALID`).

## Endpoints

| Method and path | Handler | Notes |
|---|---|---|
| `GET /t/{slug}` | `TipPageController` | `static/tip.html` with the request's CSP nonce; success screen links to `/p/{slug}` |
| `GET /p/{slug}` | `PandaPageController` | `static/panda.html` (virtual panda, `panda.js`, `panda.css`) with the request's CSP nonce |
| `GET /ws/device/{deviceId}` | `DeviceChannelHandler` | WebSocket, `Authorization: Bearer <secret>`; 401 for any failure, 429 after 10 failures a minute per IP |
| `GET /ws/observe/{deviceSlug}` | `ObserverChannelHandler` | WebSocket, public and read only, origin of `TIP_PUBLIC_BASE_URL` only; 404 unknown slug, 429 for a 6th connection per IP |
| `GET /api/public/devices/{slug}` | `DeviceController` | display name, currency, presets, bounds, PayPal client id |
| `POST /api/public/devices/{slug}/tips` | `TipController` | `{amountMinor, currency, message}` → 201 `{tipId, paypalOrderId}`; 10 per minute per IP |
| `POST /api/public/tips/{tipId}/capture` | `TipController` | → `{status}`; 422 `INSTRUMENT_DECLINED`; 409 `TIP_CLOSED` for `FAILED` and `ABANDONED` |
| `POST /webhooks/paypal` | `PayPalWebhookController` | verified raw body; 400 unverified, 503 verification down |
| `GET /owner/login` | `OwnerPageController` | `static/owner-login.html` with nonce and CSRF token; `owner-login.js` posts the form |
| `POST /owner/login` | Spring Security form login | `email`, `password`, `_csrf` → 302 `/owner`; 401 `LOGIN_FAILED`; 429 `RATE_LIMITED` |
| `GET /owner` | `OwnerPageController` | placeholder "Signed in as …" with a sign out form |
| `POST /owner/logout` | Spring Security | → 302 `/owner/login?signedOut` |
| `POST /api/owner/tips/{tipId}/refund` | `TipRefundController` | session and CSRF; 200 `{status: REFUNDED, refundStatus: COMPLETED}`, 202 `{status: CAPTURED, refundStatus: PENDING}`; 404 for other owners' tips; 409 `TIP_NOT_REFUNDABLE`; 502 `PAYPAL_REFUND_FAILED` |
| `GET /actuator/health` | Actuator | only public actuator endpoint; `{"status":"UP"}` without components; Render's health check path |

Everything else is denied. Every error, including those from Spring Security and the servlet container, is problem details with a `code` property.

## Tables

`V1__init.sql`:
- `device`: id, unique slug, display_name, created_at
- `tip`: id, device_id, amount_minor, currency, message, status (check constrained), unique paypal_order_id and paypal_capture_id, reaction, flagged, ai_status (default `PENDING`), created_at, captured_at; index on (status, created_at)
- `paypal_webhook_event`: unique event_id, event_type, resource_id, received_at

`V2__owners_ai_refunds.sql`:
- `owner`: id, unique lowercase email, password_hash, display_name, created_at. Devices that existed before go to a placeholder owner `unclaimed@tip.invalid` that cannot sign in; the seeder takes the demo device over and V3 removes the placeholder.
- `device`: owner_id (not null, foreign key), secret_hash (32 bytes)
- `tip`: status adds `REVERSED`; ai_claimed_at, ai_attempts, flag_reason, reaction_at; ai_status check constraint; partial index for unfinished AI work

`V3__reaction_delivery.sql`:
- `reaction_delivery`: tip_id primary key, device_id, reaction (check constrained), reacted_at, sent_count, last_sent_at, acked_at; partial index on (device_id, reacted_at) where acked_at is null. No foreign key to `tip`, which `devices` does not own.
- Deletes the V2 placeholder owner when it owns no devices.

`V4__reaction_model.sql`:
- `tip`: message_kind and owner_note (varchar 100), both null; message_kind and reaction checked against `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`
- `reaction_delivery`: reaction checked against the same four values
- Existing rows: `POSITIVE` becomes `WARM`, `NEUTRAL` and `NEGATIVE` become `PLAIN`; classified (`DONE`) tips get the same message_kind

`V5__reaction_repeats.sql`:
- `reaction_delivery`: repeats smallint, default 1 (existing rows), checked 1 to 5

## How a tip flows

1. The phone opens `/t/{slug}`; `tip.js` loads the device config and the PayPal JS SDK.
2. PayPal's `createOrder` callback posts to `/api/public/devices/{slug}/tips`. `TipService` validates amount and currency, sanitises the message, saves a `CREATED` tip, calls `PayPalGateway.createOrder` (request id `create-<tipId>`, `custom_id` = tip id) and stores the order id. PayPal failure marks the tip `FAILED`.
3. After approval, `onApprove` posts to `/api/public/tips/{tipId}/capture`. `TipService` captures the stored order (request id `capture-<tipId>`); `ORDER_ALREADY_CAPTURED` is reconciled with `lookupOrder`.
4. PayPal also sends `PAYMENT.CAPTURE.*` webhooks. `PayPalWebhookController` verifies the raw body, then `PayPalWebhookProcessor` inserts the event row (`ON CONFLICT DO NOTHING`) and applies the transition in one transaction: `COMPLETED` captures, `PENDING` pends, `DENIED` and `DECLINED` fail, `REFUNDED` refunds, `REVERSED` reverses.
5. Both paths call `TipTransitions`. Its conditional `UPDATE ... WHERE status IN (...)` lets exactly one caller win; only the winner publishes `TipCaptured`, which listeners receive after commit.
6. `TipCapturedListener` runs `TipReactionWorker` on the AI executor: claim the tip with a conditional update, call Claude (or skip when there is no message or AI is disabled), then a final write conditional on the same claim stores `message_kind`, `reaction` (`PLAIN` when flagged, otherwise the kind), the flag and `owner_note` (unflagged `REQUEST` only), and publishes `TipReacted` after commit with `repeats` from `tip.amount_minor` and its currency (`ReactionRepeats`, 1 when flagged). Skipped tips store `PLAIN` with no kind. A failed attempt goes back to `PENDING`; the third failure stores `PLAIN` as `FAILED`. `AiRecoveryJob` reruns the flow for pending tips and expired claims.
7. `AbandonedTipsJob` marks `CREATED` tips older than 6 hours `ABANDONED` every 10 minutes.

## How a reaction reaches the panda

1. `TipReactions` publishes `TipReacted` after commit. `ReactionDispatcher` handles it on the delivery executor (virtual threads), never on the publisher's thread, which still holds the committed transaction's connection.
2. It inserts the `reaction_delivery` row, with its repeats, using `ON CONFLICT (tip_id) DO NOTHING`; no row inserted means a duplicate, and nothing more happens.
3. If the device has a session in `DeviceSessions`, `ReactionSender` sends `{type: reaction, id, reaction, repeats, at}` and records the send. Then `ObserverSessions` broadcasts `{type, reaction, repeats, at}` to the slug's observers. Resends and replays send the stored repeats.
4. The device acks with `{type: ack, id}` on receipt, before playing; the update is conditional on the session's own device and `acked_at is null`.
5. `ReactionResendJob` resends to connected devices every second: unacked, fewer than 4 sends, last send 5 s ago, under 60 s old. A device that connects gets `hello`, replaces any old session (closed with 4000) and gets its unacked reactions of the last 60 s, oldest first.
6. `SessionKeepalive` pings every device and observer session every 20 s and closes those without a pong for 45 s with 1001. On shutdown it closes every session with 1001.

The virtual panda (`/p/{slug}`) loads the device name from `/api/public/devices/{slug}`, opens `/ws/observe/{slug}` and plays the protocol's four patterns on `requestAnimationFrame` in their colours (`WARM` pink, `PLAYFUL` yellow, `REQUEST` blue, `PLAIN` soft white), each a unit played `base + (repeats - 1)` times with its gap, queue of 4, captions, connection status and backoff. With `prefers-reduced-motion` it holds a steady light in the reaction's colour for the scaled pattern's length.

## Tools

`src/tools/java/dev/tip/tools` is its own Gradle source set (`tools`), not in the app jar. It compiles against the app's classes and dependencies. `./gradlew fakeDevice --args="--url=ws://localhost:8080 --device=<uuid> [--secret=<secret>]"` runs `FakeDevice`, which reads `.env` like `bootRun` and falls back to `TIP_DEMO_DEVICE_SECRET`. `FakeDevice` (JDK `HttpClient` WebSocket, listener, acks every reaction on receipt including repeats it does not play, reads `repeats` with missing as 1 and clamping, stops on 401), `Player` (queue of 4, drop oldest, plays in order), `LedPattern` (protocol units, gaps, base units, scaled lengths and a text picture), `RecentIds` (last 16 tip ids), `Backoff` (1 to 30 s, up to 20 % jitter).

`./gradlew aiEval` runs `AiEval`: it binds `tip.ai.*` from `application.properties` and the environment (`.env` loaded by the task), builds the production classifier through `dev.tip.ai.EvalClassifier`, runs the labelled cases in `src/tools/resources/ai-eval-cases.jsonl` (message, kind or a list of accepted kinds, flagged, ownerNote `true`, `false` or `"ifRequest"`) one by one against the real model, and prints each disagreement and accuracy for kind, flag, owner note and all three. Without a key it exits with a message. Never part of `build`, `check` or CI.

## How a refund flows

1. A signed in owner posts `/api/owner/tips/{tipId}/refund` with the CSRF token.
2. `TipRefundService` checks the tip's device belongs to the owner through `DeviceDirectory.isOwnedBy` (404 otherwise) and that it is `CAPTURED` (409 otherwise).
3. `PayPalGateway.refundCapture` refunds in full with request id `refund-<tipId>`. `COMPLETED` (or `CAPTURE_FULLY_REFUNDED`) runs `TipTransitions.refund`; `PENDING` answers 202 and the `PAYMENT.CAPTURE.REFUNDED` webhook runs the same transition later.

## Startup seeding

`DemoDeviceSeeder` creates the demo owner through `OwnerProvisioning` when the email is new, then creates the demo device or reassigns it to that owner and updates its secret hash from `TIP_DEMO_DEVICE_SECRET`. It refuses a secret that is clearly not random and logs the demo device id, never the secret.

## Tests

`src/test/java/dev/tip`: `IntegrationTest` base (random port, Testcontainers PostgreSQL, shared WireMock servers for PayPal and Anthropic, Anthropic answers `PLAIN` by default), `PayPalStubs`, `AnthropicStubs`, `WebhookEvents`, `TestData`, `CapturedEvents` and `ReactedEvents` (after commit listeners), `MutableClock` (the application `Clock` in tests, pinned and advanced by tests). Feature tests sit in the matching package. Race tests: `CaptureWebhookRaceTest` (capture and webhook over real HTTP), `AiRaceTest` (capture listener and recovery job), `RefundWebhookRaceTest` (refund response and webhook). `AiDisabledTest` runs a context with AI off and no key. Device channel tests (`DeviceChannelTest`, `ReactionDeliveryTest`, `ObserverChannelTest`, `KeepaliveTest`) use `TestSocket` (a real `StandardWebSocketClient` that records messages and can `sync` with a ping) and `RawWebSocket` (a plain socket client that shows handshake responses byte for byte and never answers pings); they set the client IP with `X-Forwarded-For`, drive time through the `MutableClock` bean and run the resend and keepalive jobs themselves. `PandaPageTest` covers the page, its CSP, its same origin `ws:`/`wss:` URL and the four reactions with their captions. `BehindProxyTest` sends real HTTP with Render's forwarded headers: spoofed leftmost `X-Forwarded-For` entries share the real client's tip and login buckets, different clients get their own, no header still works, a forwarded HTTPS login sets a `Secure` cookie and redirects stay on `https`, and health shows only its status and has no external contributors; `DeviceChannelTest` and `ObserverChannelTest` check the same spoofing on the handshakes (`TestData.uniquePublicIp()` gives non private client addresses). `HttpsBaseUrlTest` runs with an `https` `TIP_PUBLIC_BASE_URL` and checks the observer origin. `ServerPortTest` resolves `server.port` from `PORT` and `SERVER_PORT`; `DatabaseSettingsTest` checks the pool size. `ResendJobRunner` lets tests outside `devices` run the resend job. `ReactionRepeatsTest` covers every amount boundary and the flagged override. `EvalClassifierTest` checks the eval seam sends the production prompt and schema to a local WireMock. `dev.tip.tools` tests run the fake device's parts (scaled lengths, clamping), the fake device against the app (including ack before playback and queued order) and the eval's scoring with a fake classifier; the test classpath includes the `tools` output. The test profile shortens the AI timeout to 2 s and moves the recovery job, the resend job and the keepalive to once an hour so tests drive them.
