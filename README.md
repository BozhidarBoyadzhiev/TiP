# TiP

TiP (Tip integrated Panda) is an NFC tipping device: a cardboard panda holds a coin with an NFC tag, a phone tap opens a tip page, and the tipper pays with PayPal and can leave a message.
The backend takes the payment through PayPal Orders v2, confirms it through verified webhooks, and has Claude read what kind of message it is (warm, playful, a request, or plain) so the panda plays a light pattern for it. Abusive messages are flagged and get the plain blink, so trolls get no show. Owners sign in to see their tips and refund them; a flagged message stays hidden from them until they choose to read it, and they can mark it not abusive to see it like any other tip.

## Prerequisites

- Java 21 (Temurin recommended). The Gradle wrapper downloads Gradle itself.
- Docker Desktop, for the local database and for the tests (Testcontainers).
- A PayPal developer account with a sandbox REST app: <https://developer.paypal.com/dashboard/applications/sandbox>.
- ngrok (or any HTTPS tunnel), only to receive PayPal webhooks locally.
- An Anthropic API key from <https://console.anthropic.com>, unless you run with `TIP_AI_ENABLED=false`.
- For the physical panda only: PlatformIO Core, an ESP32 DevKit, a PN532 board for the NFC tag (optional: the panda works without it) and, for the firmware's native tests, a host C++ compiler (`winget install BrechtSanders.WinLibs.POSIX.UCRT` on Windows). `firmware/README.md` has the setup, secrets, build, flash and test steps.

## Local setup

1. Copy the example configuration:

   ```bash
   cp .env.example .env
   ```

2. Fill `.env`. Every variable is explained in `.env.example`. `.env` is git ignored; never commit it.
   - `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` from your sandbox app.
   - `ANTHROPIC_API_KEY`, or `TIP_AI_ENABLED=false` to run without Claude (every tip then reacts `PLAIN`).
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

   Only the SHA-256 of the secret is stored. Changing `TIP_DEMO_DEVICE_SECRET` and restarting rotates it. The firmware needs the same value, with the demo device's id, in `firmware/include/secrets.h`.

3. Start PostgreSQL 16:

   ```bash
   docker compose up -d
   ```

   Compose publishes PostgreSQL on the host port in `TIP_DB_PORT`, read from `.env` in this folder (5432 when it is missing). If 5432 is taken on your machine, put for example `TIP_DB_PORT=55432` in `.env` and use the same port in `SPRING_DATASOURCE_URL`. **A `.env` copied from an older `.env.example` has no `TIP_DB_PORT` line**, and compose then silently uses 5432: add the line, or copy `.env.example` again. `docker compose config` shows the port compose will use.

4. Run the app. `bootRun` reads `.env` as environment variables:

   ```bash
   ./gradlew bootRun
   ```

   It listens on 8080. A `PORT` environment variable changes that (Render sets it), and `SERVER_PORT` wins over `PORT`, so a `SERVER_PORT=8081` in `.env` keeps working whatever `PORT` says.

5. Open `http://localhost:8080/t/<your demo slug>` and tip with a sandbox personal account.
6. Sign in as the demo owner at `http://localhost:8080/owner/login`; it opens the dashboard at `/owner`, which updates live while the page is open. The session cookie is marked `Secure`; browsers accept that on `localhost` over plain HTTP, but on any other host use HTTPS.

   To see sample data without making payments, run with the `dev` profile: add `SPRING_PROFILES_ACTIVE=dev` to `.env`, or run `./gradlew bootRun --args='--spring.profiles.active=dev'`. On startup it gives the demo device the same invented sample tips every judge sandbox and the public demo get: about thirty tips over the last three days in the owner's time zone with several today, some in French, Spanish and German with translations, songs (all invented) asked for in several spellings, two hidden messages (one in Bulgarian), plus today's panda's notes. A start on the same day changes nothing; a start on a later day replaces the samples so today has content again. Real tips are never touched. Sign in and the dashboard at `http://localhost:8080/owner` shows them; the raw JSON is at `/api/owner/tips`, `/api/owner/today`, `/api/owner/requests` and `/api/owner/digest`. Never set this profile on Render. Only under `dev` does the demo seeder move an existing demo device to the owner `TIP_DEMO_OWNER_EMAIL` names; without it the device stays with its owner and the log shows a warning.
7. Watch the virtual panda at `<TIP_PUBLIC_BASE_URL>/p/<your demo slug>` (the page must be opened from that origin). It plays the same light patterns as the physical one. With your operating system's reduce motion setting (`prefers-reduced-motion`) the panda holds a steady light in the reaction's colour instead of fading and flicking.

The app refuses to start when a required setting is missing or invalid, such as `PAYPAL_CLIENT_ID`, `ANTHROPIC_API_KEY` while AI is enabled, a demo owner password under 12 characters, or a device secret that is not 32 bytes or is clearly not random (all bytes the same, or fewer than 16 distinct byte values).

## See it work without hardware

The virtual panda and the fake device play the same reactions as the physical panda, so the whole flow runs on a laptop and a phone.

1. Run the app as in Local setup, with the demo owner and demo device set in `.env`.
2. Find the demo device id. The app logs it at startup, never the secret:

   ```
   Demo device demo-panda has id 5b1f0c2e-8d4a-4c3e-9a71-2f6e0b9d4c18
   ```

3. Open `<TIP_PUBLIC_BASE_URL>/p/demo-panda` on the laptop. The corner says **Live** once it is connected. The panda's WebSocket only accepts the origin in `TIP_PUBLIC_BASE_URL`, so when that is your ngrok URL, open the panda through ngrok too: on `localhost` it stays on **Reconnecting**.
4. Run the fake device in a second terminal. It reads `TIP_DEMO_DEVICE_SECRET` from `.env`, so the secret stays out of your shell history (`--secret=<secret>` works too):

   ```bash
   ./gradlew fakeDevice --args="--url=ws://localhost:8080 --device=<demo device id>"
   ```

   It prints `Connected as "<device name>"`, then one line per reaction with the unit count, the repeats and a text picture of the LED pattern, and acks each one on arrival like the real device, including repeats it does not play again. Bigger tips play longer patterns (repeats 1 to 5, see `docs/DEVICE_PROTOCOL.md`). It reconnects with the protocol backoff when the app restarts, and stops on a wrong secret (401). In Windows PowerShell 5.1, run `[Console]::OutputEncoding = [Text.Encoding]::UTF8` first so the pattern picture displays correctly.
5. Tip from a phone. The phone must reach the app over HTTPS for PayPal, so open `https://<your-ngrok-host>/t/demo-panda` (see Testing webhooks locally). The success screen links to the virtual panda.
6. Watch both react: the virtual panda glows with a caption ("Warm message", "Playful message", "Request noted" or "Tip received") and the fake device prints the pattern.

Use your own port in both URLs if you set `SERVER_PORT`.

## Public demo and judge sandboxes

TiP stays usable without anyone watching it until 31 Dec 2026 (access closes 2027-01-01 00:00 Europe/London), at three levels:

- **Anyone:** tip the real demo panda and watch it, and look around the shared demo dashboard from the link under the tip card (`/demo`). `POST /demo/session` (a form post with the CSRF token, no code) signs the browser in to a read only demo owner whose sample tips are renewed every night at 00:05 London time. Every change there answers 403 `DEMO_READ_ONLY`.
- **Code holders** (judges, the owner, the teammate): `POST /judges/session` with an access code and the browser's time zone makes a private sandbox, a fully working owner with its own virtual panda at `/p/judge-<8 characters>` and the same sample tips, valid 72 hours (never past the code's expiry). A sample tip cannot be refunded (409 `DEMO_TIP`); a real sandbox tip can. Judges enter the code at `/judges`. "Continue on another device" on the sandbox dashboard shows a one time link, valid 10 minutes, with its QR code (`POST /api/owner/resume-link`). Expired sandboxes are deleted every hour with everything in them.
- **The real owner and the physical panda:** unchanged, and out of reach of the other two.

Make an access code with `./gradlew newAccessCode -Plabel=judges -Pexpires=2026-12-31T23:59:59Z` and see `docs/DEPLOYMENT.md` for where the code and its entry go.

### Paying with a test card

The tip page leaves PayPal's **Debit or Credit Card** button on, so a tipper can pay as a guest without a PayPal account. In the sandbox, get a test card number from PayPal's card generator: in the PayPal developer dashboard open **Testing Tools > Card Testing** (https://developer.paypal.com/tools/sandbox/card-testing/), generate a Visa or Mastercard, and enter it with any future expiry date and any 3 digit CVV. A sandbox personal account works too.

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

## Measuring the AI classifier

`aiEval` runs about 60 labelled tip messages through the real Claude model with the app's own prompt, schema and parser, and prints every disagreement plus the accuracy for kind (not scored on flagged cases), flag and owner note, and for the language, translation and song on the cases that label them. It calls the paid API, so it is never part of the build or CI; run it by hand after changing the prompt:

```bash
./gradlew aiEval
```

It reads `ANTHROPIC_API_KEY` and `TIP_AI_MODEL` from `.env` or the environment, like the app, and stops with a message when there is no key. The cases live in `src/tools/resources/ai-eval-cases.jsonl`, one JSON object per line with `message`, `kind` (one kind, or a list of the kinds accepted for a genuinely ambiguous message), `flagged` and `ownerNote` (`true`, `false`, or `"ifRequest"` when a note is expected only if the result is `REQUEST`). The file must be UTF-8 (`.editorconfig` and `.gitattributes` say so); a line that is not stops the run with its line number. Include prompt injection cases: abusive ones reuse messages already labelled flagged, so the repo needs no new abuse.

Both `aiEval` and `fakeDevice` write UTF-8. Windows PowerShell 5.1 shows non ASCII characters such as `ö` garbled unless you run this once per session first:

```powershell
[Console]::OutputEncoding = [Text.Encoding]::UTF8
```

## Deploying

The app runs on Render as a Docker web service with Render PostgreSQL. `docs/DEPLOYMENT.md` lists every environment variable, the health check path (`/actuator/health`), how CI deploys after a push to `main`, and a smoke test for after a deploy.

## Project layout

```
src/main/java/dev/tip/
  ai/         Claude classification of tip messages, exactly once per tip, recovery job
  dashboard/  owner dashboard API and live channel, the shared sample tips
  devices/    devices, device secrets, demo seeder, device and observer WebSocket channels, reaction delivery, virtual panda page
  owners/     owner accounts and kinds, login, access codes, judge sandboxes, public demo, owner pages
  paypal/     PayPal gateway: Orders v2 and refunds through the PayPal Server SDK, webhook verification
  tipping/    tips, capture, refunds, state machine, webhooks, rate limit, scheduled job, tip page
  shared/     configuration, clock, security, problem details
src/tools/java/dev/tip/tools/
              fake device, AI eval and access code command line tools (not in the app jar)
src/main/resources/
  db/migration/   Flyway migrations
  static/         tip page, virtual panda and owner pages (plain HTML, CSS, JS)
docs/             architecture, device protocol and deployment
firmware/         ESP32 firmware for the physical panda (PlatformIO, separate from the Gradle build)
```

`CODEBASE.md` maps the classes, endpoints, tables and the tip flow in more detail.

## Licence

MIT, see `LICENSE`.
