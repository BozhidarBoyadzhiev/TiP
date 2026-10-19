# Running TiP yourself

Everything you need to run TiP on your own machine. The README has the short version. Running it on Render is in [DEPLOYMENT.md](DEPLOYMENT.md).

## Prerequisites

- Java 21 (Temurin recommended). The Gradle wrapper downloads Gradle itself.
- Docker Desktop, for the local database and for the tests (Testcontainers).
- A PayPal developer account with a sandbox REST app: <https://developer.paypal.com/dashboard/applications/sandbox>.
- ngrok, or any HTTPS tunnel. You only need it to receive PayPal webhooks locally.
- An Anthropic API key from <https://console.anthropic.com>, unless you run with `TIP_AI_ENABLED=false`.
- For the physical panda only: PlatformIO Core, an ESP32 DevKit and a PN532 board for the NFC tag. The PN532 is optional: the panda works without it. The firmware's native tests also need a host C++ compiler (`winget install BrechtSanders.WinLibs.POSIX.UCRT` on Windows). [firmware/README.md](../firmware/README.md) has the setup, secrets, build, flash and test steps.

## Settings

Copy the example configuration:

```bash
cp .env.example .env
```

`.env` is git ignored. Never commit it. `bootRun`, `fakeDevice` and `aiEval` read it as environment variables.

| Variable | Required | What it does | Where to get it |
|---|---|---|---|
| `TIP_DB_PORT` | no | The host port docker compose publishes PostgreSQL on, 5432 when missing. See below. | Your choice |
| `SPRING_DATASOURCE_URL` | yes | The database. `jdbc:postgresql://localhost:5432/tip` in `.env.example`. | Keep the example, with your `TIP_DB_PORT` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | yes | Database login. `tip` and `tip` match `docker-compose.yml`. | Keep the example |
| `TIP_DB_POOL_SIZE` | no | Maximum database connections, 5 by default. | Your choice |
| `SERVER_PORT` | no | The app's HTTP port, 8080 by default. | Your choice |
| `TIP_CLIENT_IP_HEADER` | no | The header the client address is read from, `X-Forwarded-For` by default. Leave it unset locally. | [DEPLOYMENT.md](DEPLOYMENT.md) |
| `TIP_PUBLIC_BASE_URL` | yes | The public origin of the app, used for links and checked by the panda's WebSocket. `http://localhost:8080` in the example. | Your origin, or your ngrok URL |
| `PAYPAL_BASE_URL` | no | The PayPal API, the sandbox by default. Set `https://api-m.paypal.com` only for live. | Keep the default |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | yes | Your PayPal sandbox REST app. | PayPal developer dashboard, Apps & Credentials |
| `PAYPAL_WEBHOOK_ID` | yes | Every webhook is verified against it. | See [Webhooks](#webhooks) |
| `TIP_CURRENCY` | no | ISO 4217 code, `GBP` by default. | Your choice |
| `TIP_MIN_MINOR`, `TIP_MAX_MINOR` | no | Smallest and largest tip in minor units (pence for GBP), 100 and 10000 by default. | Your choice |
| `TIP_PRESET_AMOUNTS_MINOR` | no | Preset buttons in minor units, `100,200,500` by default. | Your choice |
| `TIP_DEMO_OWNER_EMAIL` | no | An owner login, created at startup when no owner has this email. There is no public sign up, so this is how you get one. | Your choice |
| `TIP_DEMO_OWNER_PASSWORD` | with the email | 12 to 72 characters. Only used when the owner is created. Changing it later has no effect. | Your choice |
| `TIP_DEMO_OWNER_NAME` | no | Display name. It defaults to the email. | Your choice |
| `TIP_DEMO_DEVICE_SLUG` | no | A device for that owner, created at startup. Lowercase letters, digits and hyphens, 3 to 40 characters. It becomes `/t/<slug>`. The demo owner must be set too. | Your choice |
| `TIP_DEMO_DEVICE_NAME` | no | The name the tip page and the panda show. | Your choice |
| `TIP_DEMO_DEVICE_SECRET` | with the slug | Base64url of 32 random bytes. | See [Device secret](#device-secret) |
| `TIP_AI_ENABLED` | no | `true` by default. With `false` every tip reacts `PLAIN` and no key is needed. | Your choice |
| `ANTHROPIC_API_KEY` | while AI is on | Claude reads the messages with it. | console.anthropic.com, API Keys |
| `TIP_AI_MODEL` | no | The model id, `claude-haiku-5-5` by default. | Keep the default |
| `TIP_ACCESS_CODES` | no | Access codes for judge sandboxes, stored as hashes. Empty means none. | [DEPLOYMENT.md](DEPLOYMENT.md) |
| `TIP_JUDGE_CARD_NUMBER`, `TIP_JUDGE_CARD_EXPIRY`, `TIP_JUDGE_CARD_CVC`, `TIP_JUDGE_PAYPAL_EMAIL`, `TIP_JUDGE_PAYPAL_PASSWORD` | no | Test payment details shown in a judge's sandbox. | [DEPLOYMENT.md](DEPLOYMENT.md) |
| `TIP_RATE_*` | no | Per client limits. Raise them, never lower them below honest use. | [DEPLOYMENT.md](DEPLOYMENT.md) has the defaults |
| `SPRING_PROFILES_ACTIVE` | no | `dev` adds sample tips. See [Sample data](#sample-data). Never on Render. | Your choice |

`.env.example` explains each one again in its comments.

### Device secret

Generate the device secret in Windows PowerShell 5.1 or PowerShell 7:

```powershell
$b = New-Object byte[] 32; $r = [Security.Cryptography.RandomNumberGenerator]::Create(); $r.GetBytes($b); $r.Dispose(); [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
```

Or on macOS and Linux:

```bash
openssl rand -base64 32 | tr '+/' '-_' | tr -d '='
```

Only the SHA-256 of the secret is stored. Changing `TIP_DEMO_DEVICE_SECRET` and restarting rotates it. The firmware needs the same value, with the demo device's id, in `firmware/include/secrets.h`.

### Startup checks

The app refuses to start when a required setting is missing or invalid. Examples are `PAYPAL_CLIENT_ID`, `ANTHROPIC_API_KEY` while AI is enabled, or a demo owner password under 12 characters or over 72 bytes. So is a device secret that is not 32 bytes, or is clearly not random: all bytes the same, or fewer than 16 distinct byte values.

## Run it

1. Start PostgreSQL 16:

   ```bash
   docker compose up -d
   ```

   Compose publishes PostgreSQL on the host port in `TIP_DB_PORT`. It reads it from `.env` in this folder, and uses 5432 when it is missing. If 5432 is taken on your machine, put for example `TIP_DB_PORT=55432` in `.env` and use the same port in `SPRING_DATASOURCE_URL`. **A `.env` copied from an older `.env.example` has no `TIP_DB_PORT` line.** Compose then silently uses 5432. Add the line, or copy `.env.example` again. `docker compose config` shows the port compose will use.

2. Run the app. `bootRun` reads `.env` as environment variables:

   ```bash
   ./gradlew bootRun
   ```

   It listens on 8080. A `PORT` environment variable changes that (Render sets it). `SERVER_PORT` wins over `PORT`, so a `SERVER_PORT=8081` in `.env` keeps working whatever `PORT` says. If you change the port, use it in `TIP_PUBLIC_BASE_URL` and in the fake device's `--url` too.

3. Open `http://localhost:8080/t/<your demo slug>` and tip with a sandbox personal account.
4. Sign in as the demo owner at `http://localhost:8080/owner/login`. It opens the dashboard at `/owner`, which updates live while the page is open. The session cookie is marked `Secure`. Browsers accept that on `localhost` over plain HTTP, but on any other host use HTTPS.
5. Watch the virtual panda at `<TIP_PUBLIC_BASE_URL>/p/<your demo slug>`. The page must be opened from that origin. It plays the same light patterns as the physical one. With your operating system's reduce motion setting (`prefers-reduced-motion`), the panda holds a steady light in the reaction's colour instead of fading and flicking.

### Sample data

To see sample data without making payments, run with the `dev` profile. Add `SPRING_PROFILES_ACTIVE=dev` to `.env`, or run `./gradlew bootRun --args='--spring.profiles.active=dev'`.

At startup it gives the demo device the same invented sample tips every judge sandbox and the public demo get. That is 28 tips over today and the two days before, in the owner's time zone, with several today. Some are in French, Spanish and German with translations. One invented song is asked for in three spellings. There are two hidden messages, one in Bulgarian, and today's panda's notes. A start on the same day changes nothing. A start on a later day replaces the samples, so today has content again. Real tips are never touched.

Sign in, and the dashboard at `http://localhost:8080/owner` shows them. The raw JSON is at `/api/owner/tips`, `/api/owner/today`, `/api/owner/requests` and `/api/owner/digest`.

Never set this profile on Render. Only under `dev` does the demo seeder move an existing demo device to the owner `TIP_DEMO_OWNER_EMAIL` names. Without it, the device stays with its owner and the log shows a warning.

## See it work without hardware

The virtual panda and the fake device play the same reactions as the physical panda. So the whole flow runs on a laptop and a phone.

1. Run the app as above, with the demo owner and demo device set in `.env`.
2. Find the demo device id. The app logs it at startup, never the secret:

   ```
   Demo device demo-panda has id 5b1f0c2e-8d4a-4c3e-9a71-2f6e0b9d4c18
   ```

3. Open `<TIP_PUBLIC_BASE_URL>/p/demo-panda` on the laptop. The corner says **Live** once it is connected. The panda's WebSocket only accepts the origin in `TIP_PUBLIC_BASE_URL`. So when that is your ngrok URL, open the panda through ngrok too. On `localhost` it stays on **Reconnecting**.
4. Run the fake device in a second terminal. It reads `TIP_DEMO_DEVICE_SECRET` from `.env`, so the secret stays out of your shell history. `--secret=<secret>` works too.

   ```bash
   ./gradlew fakeDevice --args="--url=ws://localhost:8080 --device=<demo device id>"
   ```

   It prints `Connected as "<device name>"`. Then it prints one line per reaction, with the unit count, the repeats and a text picture of the LED pattern. It acks each one on arrival like the real device, including repeats it does not play again. Bigger tips play longer patterns (repeats 1 to 5, see [DEVICE_PROTOCOL.md](DEVICE_PROTOCOL.md)). It reconnects with the protocol backoff when the app restarts, and stops on a wrong secret (401).
5. Tip from a phone. The phone must reach the app over HTTPS for PayPal, so open `https://<your-ngrok-host>/t/demo-panda` (see [Webhooks](#webhooks)). The success screen links to the virtual panda.
6. Watch both react. The virtual panda glows with a caption: "Warm message", "Playful message", "Request noted" or "Tip received". The fake device prints the pattern.

Use your own port in both URLs if you set `SERVER_PORT`.

## Webhooks

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
4. Copy the webhook id PayPal shows into `PAYPAL_WEBHOOK_ID` in `.env` and restart the app. Every webhook is verified against this id. An unverified one gets 400, and after 10 rejected webhooks a minute from one address the next ones get 429.

The ngrok host changes on every free ngrok start. Update the webhook URL in the dashboard when it does.

## Sandbox test payments

The tip page leaves PayPal's **Debit or Credit Card** button on, so a tipper can pay as a guest without a PayPal account. In the sandbox, use the test card in the README. Or make one on PayPal's card testing page (<https://developer.paypal.com/tools/sandbox/card-testing/>): generate a Visa or Mastercard and enter it with any future expiry date and any 3 digit CVV. A sandbox personal account works too.

## Tests

Docker must be running.

```bash
./gradlew build
```

This compiles, checks formatting with Spotless (fix with `./gradlew spotlessApply`) and runs the tests, including the ArchUnit boundary rules. Tests run against PostgreSQL in Testcontainers. PayPal and Anthropic are stubbed with WireMock, so no test calls a real external service and no real key is needed.

## Measuring the AI classifier

`aiEval` runs 58 labelled tip messages through the real Claude model, with the app's own prompt, schema and parser. It prints every disagreement and the accuracy for kind (not scored on flagged cases), flag and owner note. It also scores the language, translation and song on the cases that label them. It calls the paid API, so it is never part of the build or CI. Run it by hand after changing the prompt:

```bash
./gradlew aiEval
```

It reads `ANTHROPIC_API_KEY` and `TIP_AI_MODEL` from `.env` or the environment, like the app. It stops with a message when there is no key.

The cases live in `src/tools/resources/ai-eval-cases.jsonl`, one JSON object per line. Each has `message`, `kind`, `flagged` and `ownerNote`. `kind` is one kind, or a list of the kinds accepted for a truly ambiguous message. `ownerNote` is `true`, `false`, or `"ifRequest"` when a note is expected only if the result is `REQUEST`. A case may also label `language`, `translation` and `song`. The file must be UTF-8 (`.editorconfig` and `.gitattributes` say so). A line that is not stops the run with its line number. Include prompt injection cases. Abusive ones reuse messages already labelled flagged, so the repo needs no new abuse.

The app itself classifies at most 200 messages an hour and 1000 a day. Past that, tips react `PLAIN` once. `aiEval` calls the model directly and is not counted.

### Windows console

Both `aiEval` and `fakeDevice` write UTF-8. Windows PowerShell 5.1 shows non ASCII characters such as `ö` garbled, and the fake device's pattern picture wrong, unless you run this once per session first:

```powershell
[Console]::OutputEncoding = [Text.Encoding]::UTF8
```

## Project layout

```
src/main/java/dev/tip/
  ai/         Claude classification of tip messages, exactly once per tip, recovery job
  dashboard/  owner dashboard API and live channel, the shared sample tips
  devices/    devices, device secrets, demo seeder, device and observer WebSocket channels, reaction delivery, virtual panda page
  owners/     owner accounts and kinds, login, access codes, judge sandboxes, public demo, owner pages
  paypal/     PayPal gateway: Orders v2 and refunds through the PayPal Server SDK, webhook verification
  tipping/    tips, capture, refunds, state machine, webhooks, rate limits, the abandoned, pending and retention jobs, tip page
  shared/     configuration, clock, security, problem details
src/tools/java/dev/tip/tools/
              fake device, AI eval and access code command line tools (not in the app jar)
src/main/resources/
  db/migration/   Flyway migrations
  static/         tip page, virtual panda and owner pages (plain HTML, CSS, JS)
docs/             architecture, device protocol, owner channel, setup, deployment and workflow
firmware/         ESP32 firmware for the physical panda (PlatformIO, separate from the Gradle build)
```

[CODEBASE.md](../CODEBASE.md) maps the classes, endpoints, tables and the tip flow in more detail.
