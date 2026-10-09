# Codebase map

Spring Boot 4 monolith, package by feature under `dev.tip`. A feature uses another feature only through its `api` subpackage or Spring events; `ArchitectureTest` enforces it.

## Packages

| Package | What lives there |
|---|---|
| `dev.tip` | `TipApplication` (scheduling and config properties scan on) |
| `shared` | `TipProperties` (`TIP_*` config), `ClockConfig` (UTC `Clock`), `SecurityConfig` (fallback filter chain: public paths, deny the rest with 403), `SecurityDefaults` (CSP, headers and problem details 401/403 handlers every chain applies), `ProblemWriter` (problem details outside MVC), `CspNonce` (per request CSP nonce filter), `DomainException` + `ProblemDetailsAdvice` (RFC 9457 errors), `ProblemErrorController` (replaces Boot's `/error`) |
| `owners` | `Owner` entity, `OwnerRepository`, `OwnerService` (the only `UserDetailsService`, implements `OwnerProvisioning`), `PasswordConfig` (`DelegatingPasswordEncoder`), `OwnerSecurityConfig` (form login chain for `/owner/**` and `/api/owner/**`), `LoginRateLimiter` + `LoginRateLimitFilter` (5 per minute per IP and per email), `OwnerPageController`, `OwnerEmails` (lowercase) |
| `owners.api` | `OwnerPrincipal` (signed in owner, read with `@AuthenticationPrincipal`), `OwnerProvisioning` (create owner if missing) |
| `devices` | `Device` entity (owner, secret hash), `DeviceRepository`, `DeviceService`, `DeviceSecrets` (decode and SHA-256), `DefaultDeviceSecretVerifier`, `DemoDeviceSeeder`, `DemoDeviceProperties`, `DemoOwnerProperties`, `DeviceController` |
| `devices.api` | `DeviceDirectory` (lookup by slug, ownership check), `DeviceSecretVerifier` (constant time secret check, for Brief 03), `DeviceSummary`, `DeviceSlug` (slug pattern), `DeviceNotFoundException` |
| `paypal` | `PayPalConfig` (SDK client, OkHttp host rewrite to `PAYPAL_BASE_URL`, `RestClient`), `PayPalOrders` (create, capture, get via SDK), `PayPalRefunds` (capture refund via SDK), `WebhookSignatureVerifier` (`RestClient`), `PayPalAccessTokens` (shares the SDK OAuth token), `DefaultPayPalGateway`, `PayPalProperties` |
| `paypal.api` | `PayPalGateway`, `CreateOrderCommand`, `CreateOrderResult`, `CaptureResult`, `RefundResult`, `WebhookTransmission`, `WebhookVerification`, `PayPalPublicConfig` |
| `tipping` | `Tip` entity, `TipStatus`, `TipRepository` (conditional updates), `TipTransitions` (only place status changes, publishes `TipCaptured`), `TipService`, `TipController`, `TipRefundService`, `TipRefundController`, `PayPalWebhookController`, `PayPalWebhookProcessor`, `PayPalWebhookEvent`, `TipRateLimiter`, `AbandonedTipsJob`, `MessageSanitizer`, `TipPageController`, `TipExceptions` |
| `tipping.api` | `TipCaptured(tipId, deviceId, capturedAt)` event |
| `ai` | `AiConfig` (`@EnableAsync`, `aiExecutor` virtual threads, Anthropic client only when enabled), `AiProperties`, `TipCapturedListener` (async, after commit), `TipReactionWorker` (claim, classify or skip, finish), `AiTipRepository` (conditional updates on the tip's AI columns), `TipReactions` (final write, publishes `TipReacted`), `AiRecoveryJob` (every 30 s), `MessageClassifier`, `ClaudeMessageClassifier` (structured output, prompt), `ClassificationParser` (validates the result), `AiStatus` |
| `ai.api` | `TipReacted(tipId, deviceId, reaction, reactedAt)` event, `Reaction` |

## Security filter chains

1. `OwnerSecurityConfig` (order 1) matches `/owner`, `/owner/**`, `/api/owner/**`. Form login, CSRF on, request cache off. Unauthenticated pages redirect to `/owner/login`; the API answers 401 problem details.
2. `SecurityConfig` (lowest order) matches everything else. Public paths are listed; the rest is 403 problem details. CSRF ignored for `/api/public/**` and `/webhooks/**`.

Both apply `SecurityDefaults`: CSP with nonce, HSTS, referrer policy, problem details for 403 (`FORBIDDEN`, `CSRF_TOKEN_INVALID`).

## Endpoints

| Method and path | Handler | Notes |
|---|---|---|
| `GET /t/{slug}` | `TipPageController` | `static/tip.html` with the request's CSP nonce |
| `GET /api/public/devices/{slug}` | `DeviceController` | display name, currency, presets, bounds, PayPal client id |
| `POST /api/public/devices/{slug}/tips` | `TipController` | `{amountMinor, currency, message}` → 201 `{tipId, paypalOrderId}`; 10 per minute per IP |
| `POST /api/public/tips/{tipId}/capture` | `TipController` | → `{status}`; 422 `INSTRUMENT_DECLINED`; 409 `TIP_CLOSED` for `FAILED` and `ABANDONED` |
| `POST /webhooks/paypal` | `PayPalWebhookController` | verified raw body; 400 unverified, 503 verification down |
| `GET /owner/login` | `OwnerPageController` | `static/owner-login.html` with nonce and CSRF token; `owner-login.js` posts the form |
| `POST /owner/login` | Spring Security form login | `email`, `password`, `_csrf` → 302 `/owner`; 401 `LOGIN_FAILED`; 429 `RATE_LIMITED` |
| `GET /owner` | `OwnerPageController` | placeholder "Signed in as …" with a sign out form |
| `POST /owner/logout` | Spring Security | → 302 `/owner/login?signedOut` |
| `POST /api/owner/tips/{tipId}/refund` | `TipRefundController` | session and CSRF; 200 `{status: REFUNDED, refundStatus: COMPLETED}`, 202 `{status: CAPTURED, refundStatus: PENDING}`; 404 for other owners' tips; 409 `TIP_NOT_REFUNDABLE`; 502 `PAYPAL_REFUND_FAILED` |
| `GET /actuator/health` | Actuator | only public actuator endpoint |

Everything else is denied. Every error, including those from Spring Security and the servlet container, is problem details with a `code` property.

## Tables

`V1__init.sql`:
- `device`: id, unique slug, display_name, created_at
- `tip`: id, device_id, amount_minor, currency, message, status (check constrained), unique paypal_order_id and paypal_capture_id, reaction, flagged, ai_status (default `PENDING`), created_at, captured_at; index on (status, created_at)
- `paypal_webhook_event`: unique event_id, event_type, resource_id, received_at

`V2__owners_ai_refunds.sql`:
- `owner`: id, unique lowercase email, password_hash, display_name, created_at. Devices that existed before go to a placeholder owner `unclaimed@tip.invalid` that cannot sign in; the seeder takes the demo device over.
- `device`: owner_id (not null, foreign key), secret_hash (32 bytes)
- `tip`: status adds `REVERSED`; ai_claimed_at, ai_attempts, flag_reason, reaction_at; ai_status check constraint; partial index for unfinished AI work

## How a tip flows

1. The phone opens `/t/{slug}`; `tip.js` loads the device config and the PayPal JS SDK.
2. PayPal's `createOrder` callback posts to `/api/public/devices/{slug}/tips`. `TipService` validates amount and currency, sanitises the message, saves a `CREATED` tip, calls `PayPalGateway.createOrder` (request id `create-<tipId>`, `custom_id` = tip id) and stores the order id. PayPal failure marks the tip `FAILED`.
3. After approval, `onApprove` posts to `/api/public/tips/{tipId}/capture`. `TipService` captures the stored order (request id `capture-<tipId>`); `ORDER_ALREADY_CAPTURED` is reconciled with `lookupOrder`.
4. PayPal also sends `PAYMENT.CAPTURE.*` webhooks. `PayPalWebhookController` verifies the raw body, then `PayPalWebhookProcessor` inserts the event row (`ON CONFLICT DO NOTHING`) and applies the transition in one transaction: `COMPLETED` captures, `PENDING` pends, `DENIED` and `DECLINED` fail, `REFUNDED` refunds, `REVERSED` reverses.
5. Both paths call `TipTransitions`. Its conditional `UPDATE ... WHERE status IN (...)` lets exactly one caller win; only the winner publishes `TipCaptured`, which listeners receive after commit.
6. `TipCapturedListener` runs `TipReactionWorker` on the AI executor: claim the tip with a conditional update, call Claude (or skip when there is no message or AI is disabled), then a final write conditional on the same claim sets the reaction and publishes `TipReacted` after commit. A failed attempt goes back to `PENDING`; the third failure stores `NEUTRAL` as `FAILED`. `AiRecoveryJob` reruns the flow for pending tips and expired claims.
7. `AbandonedTipsJob` marks `CREATED` tips older than 6 hours `ABANDONED` every 10 minutes.

## How a refund flows

1. A signed in owner posts `/api/owner/tips/{tipId}/refund` with the CSRF token.
2. `TipRefundService` checks the tip's device belongs to the owner through `DeviceDirectory.isOwnedBy` (404 otherwise) and that it is `CAPTURED` (409 otherwise).
3. `PayPalGateway.refundCapture` refunds in full with request id `refund-<tipId>`. `COMPLETED` (or `CAPTURE_FULLY_REFUNDED`) runs `TipTransitions.refund`; `PENDING` answers 202 and the `PAYMENT.CAPTURE.REFUNDED` webhook runs the same transition later.

## Startup seeding

`DemoDeviceSeeder` creates the demo owner through `OwnerProvisioning` when the email is new, then creates the demo device or reassigns it to that owner and updates its secret hash from `TIP_DEMO_DEVICE_SECRET`.

## Tests

`src/test/java/dev/tip`: `IntegrationTest` base (random port, Testcontainers PostgreSQL, shared WireMock servers for PayPal and Anthropic, Anthropic answers `NEUTRAL` by default), `PayPalStubs`, `AnthropicStubs`, `WebhookEvents`, `TestData`, `CapturedEvents` and `ReactedEvents` (after commit listeners). Feature tests sit in the matching package. Race tests: `CaptureWebhookRaceTest` (capture and webhook over real HTTP), `AiRaceTest` (capture listener and recovery job), `RefundWebhookRaceTest` (refund response and webhook). `AiDisabledTest` runs a context with AI off and no key. The test profile shortens the AI timeout to 2 s and moves the recovery job to once an hour so tests drive it.
