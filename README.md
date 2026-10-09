# TiP

TiP (Tip integrated Panda) is an NFC tipping device: a cardboard panda holds a coin with an NFC tag, a phone tap opens a tip page, and the tipper pays with PayPal and can leave a message.
The backend takes the payment through PayPal Orders v2, confirms it through verified webhooks, and has Claude read the message so the panda reacts with its LED. Owners sign in to refund tips.

## Prerequisites

- Java 21 (Temurin recommended). The Gradle wrapper downloads Gradle itself.
- Docker Desktop, for the local database and for the tests (Testcontainers).
- A PayPal developer account with a sandbox REST app: <https://developer.paypal.com/dashboard/applications/sandbox>.
- ngrok (or any HTTPS tunnel), only to receive PayPal webhooks locally.
- An Anthropic API key from <https://console.anthropic.com>, unless you run with `TIP_AI_ENABLED=false`.

## Local setup

1. Copy the example configuration:

   ```bash
   cp .env.example .env
   ```

2. Fill `.env`. Every variable is explained in `.env.example`. `.env` is git ignored; never commit it.
   - `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` from your sandbox app.
   - `ANTHROPIC_API_KEY`, or `TIP_AI_ENABLED=false` to run without Claude (every tip then reacts `NEUTRAL`).
   - `TIP_DEMO_OWNER_EMAIL`, `TIP_DEMO_OWNER_PASSWORD` (12 or more characters) and `TIP_DEMO_OWNER_NAME` to get an owner login.
   - `TIP_DEMO_DEVICE_SLUG`, `TIP_DEMO_DEVICE_NAME` and `TIP_DEMO_DEVICE_SECRET` to get a device owned by that owner.

   Generate the device secret (32 random bytes, base64url) in Windows PowerShell 5.1 or PowerShell 7:

   ```powershell
   $b = New-Object byte[] 32; $r = [Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); $r.Dispose(); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
   ```

   Or on macOS and Linux:

   ```bash
   openssl rand -base64 32 | tr '+/' '-_' | tr -d '='
   ```

   Only the SHA-256 of the secret is stored. Changing `TIP_DEMO_DEVICE_SECRET` and restarting rotates it. The firmware needs the same value.

3. Start PostgreSQL 16:

   ```bash
   docker compose up -d
   ```

   Compose publishes PostgreSQL on the host port in `TIP_DB_PORT`, read from `.env` in this folder (5432 when it is missing). If 5432 is taken on your machine, put for example `TIP_DB_PORT=55432` in `.env` and use the same port in `SPRING_DATASOURCE_URL`. **A `.env` copied from an older `.env.example` has no `TIP_DB_PORT` line**, and compose then silently uses 5432: add the line, or copy `.env.example` again. `docker compose config` shows the port compose will use.

4. Run the app. `bootRun` reads `.env` as environment variables:

   ```bash
   ./gradlew bootRun
   ```

5. Open `http://localhost:8080/t/<your demo slug>` and tip with a sandbox personal account.
6. Sign in as the demo owner at `http://localhost:8080/owner/login`. The session cookie is marked `Secure`; browsers accept that on `localhost` over plain HTTP, but on any other host use HTTPS.
7. Watch the virtual panda at `http://localhost:8080/p/<your demo slug>`. It plays the same light patterns as the physical one. The rude message strobe can be disabled with your operating system's reduce motion setting (`prefers-reduced-motion`): the panda then holds a steady light instead of flashing.

The app refuses to start when a required setting is missing or invalid, such as `PAYPAL_CLIENT_ID`, `ANTHROPIC_API_KEY` while AI is enabled, a demo owner password under 12 characters, or a device secret that is not 32 bytes or is clearly not random (all bytes the same, or fewer than 16 distinct byte values).

## Testing webhooks locally

PayPal sends webhooks to a public HTTPS URL, so expose your local app:

1. Start a tunnel to port 8080:

   ```bash
   ngrok http 8080
   ```

2. In the PayPal developer dashboard, open your sandbox app, go to **Webhooks**, and add a webhook with the URL `https://<your-ngrok-host>/webhooks/paypal`.
3. Subscribe it to these six event types:
   - `PAYMENT.CAPTURE.COMPLETED`
   - `PAYMENT.CAPTURE.PENDING`
   - `PAYMENT.CAPTURE.DENIED`
   - `PAYMENT.CAPTURE.DECLINED`
   - `PAYMENT.CAPTURE.REFUNDED`
   - `PAYMENT.CAPTURE.REVERSED`
4. Copy the webhook id shown by PayPal into `PAYPAL_WEBHOOK_ID` in `.env` and restart the app. Every webhook is verified against this id, and unverified ones are rejected with 400.

The ngrok host changes on every free ngrok start, so update the webhook URL in the dashboard when it does.

## Running tests

Docker must be running.

```bash
./gradlew build
```

This compiles, checks formatting with Spotless (fix with `./gradlew spotlessApply`), and runs the tests, including the ArchUnit boundary rules. Tests run against PostgreSQL in Testcontainers, and PayPal and Anthropic are stubbed with WireMock, so no test calls a real external service and no real key is needed.

## Project layout

```
src/main/java/dev/tip/
  ai/         Claude classification of tip messages, exactly once per tip, recovery job
  devices/    devices, device secrets, demo seeder, public device config endpoint
  owners/     owner accounts, login, owner pages
  paypal/     PayPal gateway: Orders v2 and refunds through the PayPal Server SDK, webhook verification
  tipping/    tips, capture, refunds, state machine, webhooks, rate limit, scheduled job, tip page
  shared/     configuration, clock, security, problem details
src/main/resources/
  db/migration/   Flyway migrations
  static/         tip page and owner pages (plain HTML, CSS, JS)
docs/             architecture and device protocol
```

`CODEBASE.md` maps the classes, endpoints, tables and the tip flow in more detail.

## Licence

MIT, see `LICENSE`.
