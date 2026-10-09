# Brief 01: Foundation and PayPal payments

## Overview

Set up the TiP repository and build the payment core: a tip page, server side PayPal order creation and capture, verified webhooks, and a tip state machine that is safe under races and retries. At the end, a real sandbox payment made from a phone moves a tip to `CAPTURED` exactly once and publishes `TipCaptured`.

No AI, no WebSocket, no owner login yet. Those are Brief 02.

Read `CLAUDE.md`, `docs/ARCHITECTURE.md` and `docs/DEVICE_PROTOCOL.md` first. They are already in the folder. Keep them as they are.

## Before you start

- Docker Desktop must be running. Check with `docker info`. If it is not running, stop and say so.
- Check `java -version` shows 21.
- Use the APIMatic Context Plugin for any PayPal API detail: request and response shapes, headers, error names, webhook verification. Do not guess PayPal fields from memory.

## Codebase context

Empty folder apart from the docs. Package root `dev.tip`. Gradle project name `tip`. Repo name `TiP`.

## Build by step

Run `./gradlew build` after each step and keep it green before moving on.

### 1. Repository and build

- `git init` on branch `develop`. Do not create `main`, the owner does that.
- Gradle wrapper, Kotlin DSL, Java toolchain 21. Spring Boot latest stable from start.spring.io with: Web, Validation, Data JPA, Flyway, PostgreSQL driver, Security, Actuator.
- Test dependencies: Spring Boot test, Testcontainers PostgreSQL (with `@ServiceConnection`), WireMock, ArchUnit, AssertJ.
- Spotless with palantir-java-format, wired into `check`.
- Root files: `.gitignore` (Gradle, IntelliJ, VS Code, `.env`, `msg.txt`), `.gitattributes` (`* text=auto eol=lf`, `*.ps1 text eol=crlf`, `gradlew.bat text eol=crlf`), `LICENSE` (MIT, copyright "TiP contributors", year 2026), `.env.example`, `docker-compose.yml` with PostgreSQL 16 for local runs, `README.md`, `CODEBASE.md`.
- Copy the provided `.github/pull_request_template.md` as is.

### 2. CI and CD

`.github/workflows/ci.yml`:

- Triggers: push to any branch, pull requests into `develop` and `main`.
- Job `build`: ubuntu-latest, Temurin 21, Gradle cache, `./gradlew build`.
- Job `secrets-scan`: gitleaks on the full history.
- Job `deploy`: only on push to `main`, `needs: [build, secrets-scan]`, calls `curl -fsS -X POST "$RENDER_DEPLOY_HOOK_URL"` from the repo secret `RENDER_DEPLOY_HOOK_URL`. Skip with a clear log line if the secret is empty, so CI is green before Render exists.

`Dockerfile`: multi stage. Build with `eclipse-temurin:21-jdk`, run with `eclipse-temurin:21-jre`, non root user, `-XX:MaxRAMPercentage=75`, port from `PORT` env var.

### 3. Configuration

Typed `@ConfigurationProperties` records, validated at startup so the app fails fast on missing config:

- `TIP_PUBLIC_BASE_URL`
- `PAYPAL_BASE_URL` (default sandbox), `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID`
- `TIP_CURRENCY` (default `GBP`), `TIP_MIN_MINOR` (default 100), `TIP_MAX_MINOR` (default 10000), `TIP_PRESET_AMOUNTS_MINOR` (default `100,200,500`)
- `TIP_DEMO_DEVICE_SLUG` and `TIP_DEMO_DEVICE_NAME`, optional. If set, a seeder creates that device on startup when it does not exist.
- Database via the standard `SPRING_DATASOURCE_*` variables.

Also set `server.forward-headers-strategy=framework` so client IPs behind Render's proxy are correct.

### 4. Database

Flyway `V1__init.sql` creating `device`, `tip` and `paypal_webhook_event` as in `docs/ARCHITECTURE.md`. Leave out `owner_id` and `secret_hash` for now, Brief 02 adds them. Add:

- Check constraints on `tip.status`, `tip.amount_minor > 0`, `char_length(currency) = 3`.
- Unique constraints on `tip.paypal_order_id`, `tip.paypal_capture_id`, `paypal_webhook_event.event_id`, `device.slug`.
- Index on `tip (status, created_at)` for the scheduled jobs.

`ai_status` and `reaction` exist on `tip` now with `ai_status` defaulting to `PENDING`, so Brief 02 does not need a migration for them.

### 5. Features

**devices**: `Device` entity, repository, the demo seeder, and `GET /api/public/devices/{slug}` returning `displayName`, `currency`, `presetAmountsMinor`, `minMinor`, `maxMinor` and `paypalClientId`. 404 as problem details for an unknown slug.

**paypal**: a `PayPalGateway` in `paypal.api` with `createOrder`, `captureOrder` and `verifyWebhook`. Implementation uses the PayPal Server SDK for Java where it covers the call and `RestClient` where it does not. Requirements:

- Orders: intent `CAPTURE`, one purchase unit, `custom_id` = tip id, description `Tip for <display name>`, shipping preference `NO_SHIPPING`, user action `PAY_NOW`.
- `PayPal-Request-Id` on create (`create-<tipId>`) and capture (`capture-<tipId>`).
- Map PayPal errors to typed results: declined instrument, already captured, order not found, other.
- Webhook verification uses the verify webhook signature endpoint with our `PAYPAL_WEBHOOK_ID` and the five `PAYPAL-*` transmission headers. Read the request body as a raw string and verify that exact payload. Parse only after verification.

**tipping**:

- `POST /api/public/devices/{slug}/tips` with `{ amountMinor, currency, message }`. Amount within config bounds, currency equals `TIP_CURRENCY`, message trimmed, control characters removed, empty becomes null, max 280 characters. Creates the tip, creates the PayPal order, stores the order id, returns 201 with `tipId` and `paypalOrderId`. If PayPal fails, mark the tip `FAILED` and return 502 problem details.
- `POST /api/public/tips/{tipId}/capture`. Uses the stored order id. Outcomes:
  - completed: transition to `CAPTURED`, return 200 `{ status: "CAPTURED" }`
  - pending: transition to `PENDING`, return 200 `{ status: "PENDING" }`
  - declined: tip stays `CREATED`, return 422 with `code: "INSTRUMENT_DECLINED"` so the page can call `actions.restart()`
  - already captured: fetch the order from PayPal, reconcile, return current status
  - tip already `CAPTURED`: return 200 with the current status without calling PayPal
- `POST /webhooks/paypal`. Verify, then in one transaction insert the event row and apply the transition. Handle `PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.CAPTURE.PENDING`, `PAYMENT.CAPTURE.DENIED`, `PAYMENT.CAPTURE.REFUNDED`. Find the tip by `custom_id`, falling back to the related order id. Store and acknowledge other event types without action. Unverified: 400, nothing stored. Duplicate event id: 200, nothing done. Processing error: roll back and return 500 so PayPal retries.
- All transitions through a conditional update as described in `docs/ARCHITECTURE.md`. Only the caller that wins publishes `TipCaptured(tipId, deviceId, capturedAt)` with `@TransactionalEventListener(phase = AFTER_COMMIT)` semantics. For now a listener in `tipping` logs it at INFO without the message text.
- Scheduled job every 10 minutes: tips in `CREATED` older than 6 hours become `ABANDONED`.
- Rate limit tip creation at 10 per minute per client IP with an in memory token bucket (Bucket4j). 429 as problem details.

**shared**: problem details advice, the `Clock` bean, security config.

### 6. Security config

- Permit: `GET /t/**`, static assets, `/api/public/**`, `POST /webhooks/paypal`, `GET /actuator/health`. Deny everything else for now.
- CSRF disabled only for `/api/public/**` and `/webhooks/**`. These are stateless JSON endpoints with no session cookie, so there is nothing for CSRF to ride on. Keep it on everywhere else for the dashboard later.
- Headers: a Content Security Policy that allows our origin plus the domains the PayPal JS SDK needs (check them with the plugin), `frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`, HSTS.

### 7. Tip page

`GET /t/{slug}` serves `static/tip.html`. Plain HTML, CSS and JS, phone first.

- Loads device config, shows "Tip <display name>", preset amount buttons, a custom amount field, an optional message box with a 280 character counter, and the PayPal button.
- `createOrder` calls our create endpoint and returns `paypalOrderId`. `onApprove` calls capture. On 422 `INSTRUMENT_DECLINED` call `actions.restart()`.
- Success screen: "Thank you. The panda got your tip." Pending screen: "Your payment is processing." Clear error messages otherwise. User text is rendered with `textContent` only.

### 8. README

Sections: what TiP is (two sentences), prerequisites, local setup (`docker compose up -d`, copy `.env.example` to `.env`, fill sandbox keys, `./gradlew bootRun`), testing webhooks locally with ngrok and registering the webhook in the PayPal sandbox app, running tests, project layout, licence.

## Tests (required)

Through HTTP against Testcontainers PostgreSQL with PayPal stubbed in WireMock:

1. Create tip: valid input returns 201 and stores `CREATED` with the order id.
2. Create tip: amount below min, above max, wrong currency, message over 280 characters each return 400. Unknown slug returns 404.
3. Create tip: PayPal error marks the tip `FAILED` and returns 502.
4. Create tip: the 11th request in a minute from one IP returns 429.
5. Capture: completed moves to `CAPTURED` and publishes exactly one event.
6. Capture: declined returns 422 and the tip stays `CREATED`.
7. Capture: calling capture twice calls PayPal once and publishes one event.
8. Race: capture endpoint and `PAYMENT.CAPTURE.COMPLETED` webhook run concurrently on two threads, started together with a latch. Exactly one `TipCaptured` event and one capture id stored. Repeat the race 20 times in the test.
9. Webhook: failed verification returns 400 and stores nothing.
10. Webhook: same event id twice is processed once.
11. Webhook: `PAYMENT.CAPTURE.REFUNDED` moves `CAPTURED` to `REFUNDED`.
12. Webhook: processing failure rolls back the event row.
13. Scheduled job: old `CREATED` tips become `ABANDONED`, recent ones do not. Use a fixed `Clock`.
14. Security: an unknown path returns 401 or 403. Response headers include the CSP.
15. ArchUnit: feature packages only depend on other features' `api` packages or `shared`.
16. Config: the app fails to start when `PAYPAL_CLIENT_ID` is missing.

Plus unit tests for message sanitising.

## Do

- Keep each commit to one logical change: scaffold, CI, config, schema, paypal, tipping, webhooks, tip page, README.
- Use the plugin for every PayPal detail.
- Write `CODEBASE.md` as a short map: packages, main classes, endpoints, tables, how a tip flows.

## Do not

- Do not add AI, WebSockets, owner login or the dashboard.
- Do not call real PayPal from tests.
- Do not put any key, client id or webhook id in a committed file.
- Do not push, create `main` or touch remotes.

## Human actions to list in your report

Expect to list at least: create the PayPal sandbox app and fill `.env`; start ngrok and register the webhook with the four event types; copy the webhook id into `.env`; create the GitHub repo and push. The owner will do these.

## Report

Use the format in `CLAUDE.md`. Teammate activity: "none" for this brief, the remote does not exist yet.
