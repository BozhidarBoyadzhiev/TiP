# Codebase map

Spring Boot 4 monolith, package by feature under `dev.tip`. A feature uses another feature only through its `api` subpackage or Spring events; `ArchitectureTest` enforces it.

## Packages

| Package | What lives there |
|---|---|
| `dev.tip` | `TipApplication` (scheduling and config properties scan on) |
| `shared` | `TipProperties` (`TIP_*` config), `ClockConfig` (UTC `Clock`), `SecurityConfig`, `CspNonce` (per request CSP nonce filter), `DomainException` + `ProblemDetailsAdvice` (RFC 9457 errors) |
| `devices` | `Device` entity, `DeviceRepository`, `DeviceService`, `DemoDeviceSeeder`, `DeviceController`, `DemoDeviceProperties` |
| `devices.api` | `DeviceDirectory` (lookup by slug), `DeviceSummary`, `DeviceSlug` (slug pattern), `DeviceNotFoundException` |
| `paypal` | `PayPalConfig` (SDK client, OkHttp host rewrite to `PAYPAL_BASE_URL`, `RestClient`), `PayPalOrders` (create, capture, get via SDK), `WebhookSignatureVerifier` (`RestClient`), `PayPalAccessTokens` (shares the SDK OAuth token), `DefaultPayPalGateway`, `PayPalProperties` |
| `paypal.api` | `PayPalGateway`, `CreateOrderCommand`, `CreateOrderResult`, `CaptureResult`, `WebhookTransmission`, `WebhookVerification`, `PayPalPublicConfig` |
| `tipping` | `Tip` entity, `TipStatus`, `TipRepository` (conditional updates), `TipTransitions` (only place status changes, publishes `TipCaptured`), `TipService`, `TipController`, `PayPalWebhookController`, `PayPalWebhookProcessor`, `PayPalWebhookEvent`, `TipRateLimiter`, `AbandonedTipsJob`, `MessageSanitizer`, `TipPageController`, `TipCapturedLogger`, `TipExceptions` |
| `tipping.api` | `TipCaptured(tipId, deviceId, capturedAt)` event |

## Endpoints

| Method and path | Handler | Notes |
|---|---|---|
| `GET /t/{slug}` | `TipPageController` | `static/tip.html` with the request's CSP nonce |
| `GET /api/public/devices/{slug}` | `DeviceController` | display name, currency, presets, bounds, PayPal client id |
| `POST /api/public/devices/{slug}/tips` | `TipController` | `{amountMinor, currency, message}` → 201 `{tipId, paypalOrderId}`; 10 per minute per IP |
| `POST /api/public/tips/{tipId}/capture` | `TipController` | → `{status}`; 422 `INSTRUMENT_DECLINED` |
| `POST /webhooks/paypal` | `PayPalWebhookController` | verified raw body; 400 unverified, 503 verification down |
| `GET /actuator/health` | Actuator | only public actuator endpoint |

Everything else is denied. Errors are problem details with a `code` property.

## Tables (`V1__init.sql`)

- `device`: id, unique slug, display_name, created_at
- `tip`: id, device_id, amount_minor, currency, message, status (check constrained), unique paypal_order_id and paypal_capture_id, reaction, flagged, ai_status (default `PENDING`), created_at, captured_at; index on (status, created_at)
- `paypal_webhook_event`: unique event_id, event_type, resource_id, received_at

## How a tip flows

1. The phone opens `/t/{slug}`; `tip.js` loads the device config and the PayPal JS SDK.
2. PayPal's `createOrder` callback posts to `/api/public/devices/{slug}/tips`. `TipService` validates amount and currency, sanitises the message, saves a `CREATED` tip, calls `PayPalGateway.createOrder` (request id `create-<tipId>`, `custom_id` = tip id) and stores the order id. PayPal failure marks the tip `FAILED`.
3. After approval, `onApprove` posts to `/api/public/tips/{tipId}/capture`. `TipService` captures the stored order (request id `capture-<tipId>`); `ORDER_ALREADY_CAPTURED` is reconciled with `lookupOrder`.
4. PayPal also sends `PAYMENT.CAPTURE.*` webhooks. `PayPalWebhookController` verifies the raw body, then `PayPalWebhookProcessor` inserts the event row (`ON CONFLICT DO NOTHING`) and applies the transition in one transaction.
5. Both paths call `TipTransitions`. Its conditional `UPDATE ... WHERE status IN (...)` lets exactly one caller win; only the winner publishes `TipCaptured`, which listeners receive after commit.
6. `AbandonedTipsJob` marks `CREATED` tips older than 6 hours `ABANDONED` every 10 minutes.

## Tests

`src/test/java/dev/tip`: `IntegrationTest` base (random port, Testcontainers PostgreSQL, shared WireMock as PayPal), `PayPalStubs`, `WebhookEvents`, `TestData`, `CapturedEvents` (after commit listener). Feature tests sit in the matching package; `CaptureWebhookRaceTest` races capture and webhook over real HTTP.
