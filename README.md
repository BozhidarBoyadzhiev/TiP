# TiP

TiP (Tip integrated Panda) is an NFC tipping device: a cardboard panda holds a coin with an NFC tag, a phone tap opens a tip page, and the tipper pays with PayPal and can leave a message.
The backend takes the payment through PayPal Orders v2, confirms it through verified webhooks, and (from Brief 02) has Claude read the message so the panda reacts with its LED.

## Prerequisites

- Java 21 (Temurin recommended). The Gradle wrapper downloads Gradle itself.
- Docker Desktop, for the local database and for the tests (Testcontainers).
- A PayPal developer account with a sandbox REST app: <https://developer.paypal.com/dashboard/applications/sandbox>.
- ngrok (or any HTTPS tunnel), only to receive PayPal webhooks locally.

## Local setup

1. Start PostgreSQL 16:

   ```bash
   docker compose up -d
   ```

   If port 5432 is already taken on your machine, set `TIP_DB_PORT` (for example `TIP_DB_PORT=55432 docker compose up -d`) and use the same port in `SPRING_DATASOURCE_URL`.

2. Copy the example configuration:

   ```bash
   cp .env.example .env
   ```

3. Fill `.env` with your sandbox app's `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`, and set `TIP_DEMO_DEVICE_SLUG` and `TIP_DEMO_DEVICE_NAME` to get a device on startup. Every variable is explained in `.env.example`. `.env` is git ignored; never commit it.

4. Run the app. `bootRun` reads `.env` as environment variables:

   ```bash
   ./gradlew bootRun
   ```

5. Open `http://localhost:8080/t/<your demo slug>` and tip with a sandbox personal account.

The app refuses to start when a required setting such as `PAYPAL_CLIENT_ID` is missing.

## Testing webhooks locally

PayPal sends webhooks to a public HTTPS URL, so expose your local app:

1. Start a tunnel to port 8080:

   ```bash
   ngrok http 8080
   ```

2. In the PayPal developer dashboard, open your sandbox app, go to **Webhooks**, and add a webhook with the URL `https://<your-ngrok-host>/webhooks/paypal`.
3. Subscribe it to these four event types:
   - `PAYMENT.CAPTURE.COMPLETED`
   - `PAYMENT.CAPTURE.PENDING`
   - `PAYMENT.CAPTURE.DENIED`
   - `PAYMENT.CAPTURE.REFUNDED`
4. Copy the webhook id shown by PayPal into `PAYPAL_WEBHOOK_ID` in `.env` and restart the app. Every webhook is verified against this id, and unverified ones are rejected with 400.

The ngrok host changes on every free ngrok start, so update the webhook URL in the dashboard when it does.

## Running tests

Docker must be running.

```bash
./gradlew build
```

This compiles, checks formatting with Spotless (fix with `./gradlew spotlessApply`), and runs the tests, including the ArchUnit boundary rules. Tests run against PostgreSQL in Testcontainers, and PayPal is stubbed with WireMock, so no test calls a real external service.

## Project layout

```
src/main/java/dev/tip/
  devices/    devices, demo seeder, public device config endpoint
  paypal/     PayPal gateway: Orders v2 through the PayPal Server SDK, webhook verification
  tipping/    tips, capture, state machine, webhooks, rate limit, scheduled job, tip page
  shared/     configuration, clock, security, problem details
src/main/resources/
  db/migration/   Flyway migrations
  static/         tip page (plain HTML, CSS, JS)
docs/             architecture and device protocol
```

`CODEBASE.md` maps the classes, endpoints, tables and the tip flow in more detail.

## Licence

MIT, see `LICENSE`.
