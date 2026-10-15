# Deploying to Render

TiP runs on Render as one Docker web service (Starter, 512 MB, 0.5 CPU) and one Render PostgreSQL 16 database (Basic-256mb), both in Frankfurt. The service builds from the `Dockerfile` in the repository root.

## Environment variables

Set these on the web service under **Environment**. "Secret" means: never in the repository, never in a screenshot, rotate it if it leaks. Variables marked **differs from local** must not reuse your `.env` value.

### Set by Render, do not set yourself

| Variable | What it is |
|---|---|
| `PORT` | The port Render routes to. The app listens on it. Do not set `SERVER_PORT` on Render: it would win over `PORT` and Render would not reach the app. |

### Public URL

| Variable | Secret | What it is |
|---|---|---|
| `TIP_PUBLIC_BASE_URL` | no | **Differs from local.** `https://<service>.onrender.com`, no trailing path. The virtual panda's WebSocket only accepts this origin, so it must be the exact `https` address people open. |

### Database

Render's database page lists host, port, database, username and password separately. Use the **internal** host, which only works from a service in the same region.

| Variable | Secret | What it is |
|---|---|---|
| `SPRING_DATASOURCE_URL` | no | `jdbc:postgresql://<internal host>:<port>/<database>`, for example `jdbc:postgresql://dpg-abc123-a:5432/tip`. Not Render's own `postgresql://user:pass@...` URL: the JDBC driver needs this form and the credentials go in the two variables below. |
| `SPRING_DATASOURCE_USERNAME` | no | The database username. |
| `SPRING_DATASOURCE_PASSWORD` | yes | The database password. |
| `TIP_DB_POOL_SIZE` | no | Optional. Maximum database connections, 5 by default. Basic-256mb allows few connections, and old and new instance overlap briefly during a deploy, so keep it small. |

SSL to the database stays off: the internal connection runs inside Render's private network. Only if you ever use the **external** host, add `?sslmode=require` to the URL.

Flyway creates and migrates the schema on startup, also on an empty database, so there is nothing to run by hand. `TIP_DB_PORT` is only for docker compose on your machine; do not set it on Render.

### PayPal

| Variable | Secret | What it is |
|---|---|---|
| `PAYPAL_BASE_URL` | no | `https://api-m.sandbox.paypal.com` (the default, may be left out). |
| `PAYPAL_CLIENT_ID` | no | Client id of the sandbox REST app. It also appears in the tip page. |
| `PAYPAL_CLIENT_SECRET` | yes | Secret of the sandbox REST app. |
| `PAYPAL_WEBHOOK_ID` | no | **Differs from local.** Id of the webhook registered for `https://<service>.onrender.com/webhooks/paypal` (see below). Every webhook is verified against it. |

### Claude

| Variable | Secret | What it is |
|---|---|---|
| `TIP_AI_ENABLED` | no | `true` (default) to classify messages, `false` to react `PLAIN` to every tip without calling Claude. |
| `ANTHROPIC_API_KEY` | yes | API key. Required while AI is enabled; the app refuses to start without it. |
| `TIP_AI_MODEL` | no | Optional, `claude-haiku-5-5` by default. |

### Tip rules

| Variable | Secret | What it is |
|---|---|---|
| `TIP_CURRENCY` | no | ISO 4217 code, `GBP` by default. |
| `TIP_MIN_MINOR` | no | Smallest tip in minor units, 100 by default. |
| `TIP_MAX_MINOR` | no | Largest tip in minor units, 10000 by default. |
| `TIP_PRESET_AMOUNTS_MINOR` | no | Preset buttons in minor units, `100,200,500` by default. |

### Demo owner and device

| Variable | Secret | What it is |
|---|---|---|
| `TIP_DEMO_OWNER_EMAIL` | no | Owner login, created on the first start. Changing it later creates a new owner but never moves an existing demo device to them: the device stays with its owner and the log shows one WARN naming the slug and both owner ids. |
| `TIP_DEMO_OWNER_PASSWORD` | yes | **Differs from local.** 12 to 72 characters. Only used when the owner is created; changing it later has no effect. |
| `TIP_DEMO_OWNER_NAME` | no | Display name, defaults to the email. |
| `TIP_DEMO_DEVICE_SLUG` | no | Slug of the demo panda, the last part of the NFC tag URL. |
| `TIP_DEMO_DEVICE_NAME` | no | Name the tip page and the panda show. |
| `TIP_DEMO_DEVICE_SECRET` | yes | **Differs from local.** A fresh 32 byte base64url secret (see README). The firmware of the panda that talks to Render needs the same value. |

### Judge access codes

| Variable | Secret | What it is |
|---|---|---|
| `TIP_ACCESS_CODES` | no (holds only hashes) | Entries `label:sha256hex:expiresAt` separated by `;`, for example `judges:3f2a…:2026-12-31T23:59:59Z`. The label is `[a-z0-9-]{1,32}` and shows up in the logs; `expiresAt` is ISO 8601 with an offset. Empty or unset means no codes. A malformed entry stops the app at startup, naming the entry by its position. An expired code is refused exactly like a wrong one. |

Make each code on your machine:

```bash
./gradlew newAccessCode -Plabel=judges -Pexpires=2026-12-31T23:59:59Z
```

It prints the code (`TIP-XXXXX-XXXXX-XXXXX-XXXXX`, 100 random bits) once, and the entry to add to `TIP_ACCESS_CODES`. It writes neither to a file. The code goes only into Devpost's private **Additional info** field for the judges; never into the repository, an issue, a pull request, a chat or a screenshot. Only the entry, which holds the SHA-256 of the code, goes on Render. To revoke a code, remove its entry and redeploy; sandboxes it already made run to their own expiry (72 hours at most). Typing is forgiving: case, spaces, dashes, the `TIP` prefix and the letters O, I and L for 0, 1 and 1 do not matter.

### Judge test payment details

Optional. A judge's sandbox shows them under **Test payment details**, so a judge can pay a tip in the PayPal sandbox without making an account. They are PayPal sandbox values that move no real money, but they still live only on Render: never in the repository, a test or a screenshot. Only a sandbox session can read them (`GET /api/owner/test-payment`, `no-store`); the app never logs them.

| Variable | Secret | What it is |
|---|---|---|
| `TIP_JUDGE_CARD_NUMBER` | yes | A sandbox test card number, from developer.paypal.com > Testing Tools > Card Generator. |
| `TIP_JUDGE_CARD_EXPIRY` | yes | Its expiry as PayPal's form takes it, for example `MM/YY`. |
| `TIP_JUDGE_CARD_CVC` | yes | Its CVC. The card shows only when all three card values are set. |
| `TIP_JUDGE_PAYPAL_EMAIL` | yes | The email of a sandbox personal (buyer) account, from Sandbox Accounts. |
| `TIP_JUDGE_PAYPAL_PASSWORD` | yes | Its password. The account shows only when both values are set. |

With neither the card nor the account set, the button is not shown. The billing details in the box (name, postcode, mobile, email) are fixed sample data in the code.

### Rate limits

Per client IP (the address from `TIP_CLIENT_IP_HEADER`), in memory. The defaults sit well above what an honest person does; change one only if real use hits it. A refusal is 429 `RATE_LIMITED` and one WARN line `rate_limited endpoint=<name> ip=<hash>` (`owner=<sandbox id>` for the per sandbox limit on resume links).

| Variable | Default | Limits |
|---|---|---|
| `TIP_RATE_TIP_ORDERS_PER_MINUTE` | 10 | tip order creation, `POST /api/public/devices/{slug}/tips` (each costs a PayPal call) |
| `TIP_RATE_CAPTURES_PER_MINUTE` | 30 | captures, `POST /api/public/tips/{id}/capture` |
| `TIP_RATE_JUDGE_SESSIONS_PER_HOUR` | 10 | access code attempts, right or wrong, `POST /judges/session` (each success makes a sandbox) |
| `TIP_RATE_DEMO_SESSIONS_PER_MINUTE` | 30 | public demo sign ins, `POST /demo/session` |
| `TIP_RATE_RESUMES_PER_MINUTE` | 20 | resume links used, `POST /judges/resume` |
| `TIP_RATE_RESUME_LINKS_PER_TEN_MINUTES` | 5 | resume links made, `POST /api/owner/resume-link`, per sandbox rather than per IP |

Every per IP limit counts an IPv6 client by its /64 prefix, since one connection usually holds a whole /64. Owner sign in keeps its own fixed limit of 5 a minute per IP and per email, and PayPal webhooks that PayPal rejects are limited to 10 a minute per IP (`rate_limited endpoint=paypal_webhook`), which PayPal's own deliveries never meet.

The whole app classifies at most 200 tip messages a UTC hour and 1000 a UTC day (`tip.ai.cap.*` in `application.properties`), because sandbox payments cost nothing: past either, a tip reacts `PLAIN` once with `aiStatus` `UNAVAILABLE` and is never classified later, and the log shows one WARN `ai_cap_reached` for that window. The hard cost limit is the spend limit set in the Anthropic console ($10): when it is reached the app keeps taking tips, every tip reacts `PLAIN` once with `aiStatus` `UNAVAILABLE`, the notes stay as they were, and the model is tried again once every 10 minutes (`ai_breaker_opened` and `ai_breaker_closed` in the log).

### Client address

| Variable | Secret | What it is |
|---|---|---|
| `TIP_CLIENT_IP_HEADER` | no | **On Render set `CF-Connecting-IP`**, see "Behind Render's proxy" below. The header the client address is read from, `X-Forwarded-For` by default. On another host, set it to the header the host's edge overwrites, chosen as in "Choosing the client IP header on a new host" below. |
| `TIP_DEBUG_CLIENT_IP` | no | Optional, `false` by default. **For a one time check only.** With `true` the app logs one line per `/api/public/` request with the connection address, the resolved client address and the raw `X-Forwarded-For`, `X-Real-IP`, `True-Client-IP` and `CF-Connecting-IP` headers. That writes client IPs to the log, so set it back to `false` as soon as the check is done. |

### JVM

The `Dockerfile` sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, so the heap uses three quarters of the 512 MB instead of the JVM's default quarter. Do not set `JAVA_TOOL_OPTIONS` on Render unless you mean to replace that.

## Health check

Set the service's **Health Check Path** to:

```
/actuator/health
```

It answers `200 {"status":"UP"}` and nothing else: no component names or details. It checks the database connection and local disk space only. It never calls PayPal or Anthropic, so an outage there cannot make Render restart a healthy app. If the database is unreachable it answers 503.

## Access log lines

One line each, without personal data: IPs only as an HMAC under a key made fresh at every start, never codes, tokens, emails or message text.

| Line | Level | When |
|---|---|---|
| `sandbox_created label=<code label> owner=<id>` | INFO | an access code made a sandbox |
| `sandbox_expired owner=<id> label=<code label>` | INFO | the hourly job deleted an expired sandbox |
| `resume_link_used owner=<id>` | INFO | a resume link signed a browser in |
| `demo_session ip=<hash>` | INFO | someone opened the public demo |
| `ai_breaker_opened status=<status> type=<API error type> message=<API error message> until=<time>`, `ai_breaker_closed` | INFO | a 400, 401, 402, 403 or 404 from the model API (spend limit, credit, key, permission, model) rested the model, and it came back |
| `ai_cap_reached window=<hour or day> limit=<n> since=<window start>` | WARN | the classification cap ran out; once per window |
| `code_rejected ip=<hash>` | WARN | a wrong or expired access code |
| `rate_limited endpoint=<name> ip=<hash>` | WARN | a per IP limit refused a request |
| `daily_summary sandboxes=<label:n,…> tipsPaid=<kind:n,…> aiCalls=<n> aiFailures=<n>` | INFO | 00:10 London time, from the database, for the last 24 hours |

## Behind Render's proxy

Render terminates TLS and forwards plain HTTP with `X-Forwarded-For` and `X-Forwarded-Proto`. The app uses Tomcat's remote IP handling (`server.forward-headers-strategy=native`): it trusts these headers only from private addresses, which is where Render's proxy connects from, and takes the rightmost `X-Forwarded-For` entry that is not a private address as the client. A client that prepends fake addresses therefore still lands in its own rate limit bucket, and different clients get different buckets. A different header can be chosen with `TIP_CLIENT_IP_HEADER`, for example `True-Client-IP` or `CF-Connecting-IP`, which Cloudflare in front of Render sets. Such a single value header counts only when it is sent once and holds exactly one IP literal. A list, a hostname, an address with a port or anything else is ignored, and so is a missing header: the client is then the connection address, Render's proxy, which all such requests share. `X-Forwarded-For` keeps the rightmost rule above.

On Render, set `TIP_CLIENT_IP_HEADER=CF-Connecting-IP`. Render sits behind Cloudflare, and a production check showed:

- Cloudflare answers 403 to any client that sends its own `CF-Connecting-IP`, so the header the app sees is always the one Cloudflare set.
- Cloudflare overwrites a forged `True-Client-IP`.
- Cloudflare appends its own edge address to `X-Forwarded-For`, so the rightmost entry that is not a private address is a Cloudflare edge node. With the `X-Forwarded-For` default, every client behind the same edge node would share one rate limit bucket.

The `X-Forwarded-For` default is only right behind a proxy that appends the client's address last, with nothing public appended after it.

Requests forwarded as `https` count as secure: the session cookie is `Secure`, redirects stay on `https`, HSTS is sent, and the panda page opens `wss://`.

## How deploys happen

1. A push to `main` runs CI (`.github/workflows/ci.yml`): build and tests, then the secrets scan.
2. When both pass, the `deploy` job posts to the Render deploy hook in the GitHub secret `RENDER_DEPLOY_HOOK_URL`. Without that secret the job skips the deploy.
3. Render builds the Docker image and starts the new instance. Once its health check passes, Render switches traffic and sends `SIGTERM` to the old one, which closes every device and observer WebSocket with 1001 so devices reconnect to the new instance.

Render's own **Auto-Deploy** must be **Off** on the service, so the deploy hook is the only way a deploy starts and nothing reaches Render without green CI.

## One time setup outside the app

- In the PayPal developer dashboard, add a second webhook to the sandbox app with the URL `https://<service>.onrender.com/webhooks/paypal`, subscribed to the same six `PAYMENT.CAPTURE.*` events as in the README, and put its id in `PAYPAL_WEBHOOK_ID`. Keep your ngrok webhook for local runs.
- Add `RENDER_DEPLOY_HOOK_URL` as a GitHub Actions secret (Render: service **Settings > Deploy Hook**).
- Write `https://<service>.onrender.com/t/<slug>` to the NFC tag, and point the panda's firmware at `<service>.onrender.com` with the Render device secret.

## Smoke test after the first deploy

1. `https://<service>.onrender.com/actuator/health` answers `{"status":"UP"}`.
2. The service log shows Flyway migrating to the latest version and `Demo device <slug> has id <uuid>`, and no secret.
3. `http://<service>.onrender.com/t/<slug>` ends up on `https` (Render redirects), and the tip page loads the PayPal buttons.
4. Tip with a sandbox personal account. The success screen appears, and the PayPal dashboard shows the webhook delivered with 200.
5. `https://<service>.onrender.com/p/<slug>` shows **Live** and reacts to the tip.
6. The fake device connects over TLS: `./gradlew fakeDevice --args="--url=wss://<service>.onrender.com --device=<uuid> --secret=<Render device secret>"` prints `Connected as` and the reaction. Or the real panda reacts.
7. Sign in at `https://<service>.onrender.com/owner/login`. In the browser's developer tools the `JSESSIONID` cookie is `Secure` and `HttpOnly`.
8. Rate limits are per client.
   1. Six wrong sign ins from one network get `Too many sign in attempts` on the sixth; a phone on mobile data can still sign in right after.
   2. Send eleven tip requests for an unknown slug, each with a different made up `X-Forwarded-For`; the first ten get 404 and the eleventh 429.

      ```bash
      for i in $(seq 1 11); do curl -s -o /dev/null -w "%{http_code}\n" -H "X-Forwarded-For: 203.0.113.$i" -H "Content-Type: application/json" -d '{"amountMinor":100,"currency":"GBP"}' https://<service>.onrender.com/api/public/devices/no-such-panda/tips; done
      ```

      PowerShell 5.1 mangles JSON quotes passed to `curl.exe`, so there the body comes from a file:

      ```powershell
      '{"amountMinor":100,"currency":"GBP"}' | Set-Content body.json -NoNewline -Encoding ascii
      1..11 | % { curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Forwarded-For: 203.0.113.$_" -H "Content-Type: application/json" --data-binary "@body.json" https://<service>.onrender.com/api/public/devices/no-such-panda/tips }
      Remove-Item body.json
      ```

   3. If `TIP_CLIENT_IP_HEADER` is set, send the same eleven requests forging that header instead (here `CF-Connecting-IP`, as on Render). Cloudflare must answer every one with 403 before it reaches the app. A 404 or 429 means a forged header got through to the app. For a header the edge overwrites instead of refusing, such as `True-Client-IP`, wait a minute so the bucket refills first; those requests must end in 429.

      ```bash
      for i in $(seq 1 11); do curl -s -o /dev/null -w "%{http_code}\n" -H "CF-Connecting-IP: 203.0.113.$i" -H "Content-Type: application/json" -d '{"amountMinor":100,"currency":"GBP"}' https://<service>.onrender.com/api/public/devices/no-such-panda/tips; done
      ```

      ```powershell
      '{"amountMinor":100,"currency":"GBP"}' | Set-Content body.json -NoNewline -Encoding ascii
      1..11 | % { curl.exe -s -o NUL -w "%{http_code}`n" -H "CF-Connecting-IP: 203.0.113.$_" -H "Content-Type: application/json" --data-binary "@body.json" https://<service>.onrender.com/api/public/devices/no-such-panda/tips }
      Remove-Item body.json
      ```
9. Access levels: the "Look at a sample dashboard" link under the tip card (`/demo`) shows the sample dashboard and refuses every change; an access code on `/judges` makes a sandbox whose panda page `/p/judge-…` loads. The log shows `demo_session` and `sandbox_created label=<label>`, never a code or an IP.
10. Redeploy through CI. The fake device logs a 1001 close and reconnects, and the panda page goes back to **Live**.

## Choosing the client IP header on a new host

Rate limits are only per client if the app reads the client address from a header the client cannot forge. Hosts differ in which header that is: some append the real address to `X-Forwarded-For` (then the default works), others put it first and the client's own entries after it, and an edge such as Cloudflare overwrites `True-Client-IP` and `CF-Connecting-IP`. Do not guess; check once:

1. Set `TIP_DEBUG_CLIENT_IP=true` and deploy.
2. Send one request with every header forged to a different value:

   ```powershell
   '{"amountMinor":100,"currency":"GBP"}' | Set-Content body.json -NoNewline -Encoding ascii
   curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Forwarded-For: 203.0.113.1" -H "True-Client-IP: 203.0.113.2" -H "CF-Connecting-IP: 203.0.113.3" -H "Content-Type: application/json" --data-binary "@body.json" https://<service>.onrender.com/api/public/devices/no-such-panda/tips
   Remove-Item body.json
   ```
3. Find the line starting `Client IP check:` in the service log. A header that shows your real IP despite the forged value is one the edge overwrites, so a client cannot forge it. `X-Forwarded-For` is only safe as the default if your real IP is its rightmost entry. If the request in step 2 got 403 and left no log line, an edge such as Cloudflare refused the forged `CF-Connecting-IP`; that refusal makes the header unforgeable. Send the request again without that header to see the others.
4. Set `TIP_CLIENT_IP_HEADER` to that header (leave it unset for `X-Forwarded-For`), set `TIP_DEBUG_CLIENT_IP=false`, deploy, and rerun smoke test step 8 for `X-Forwarded-For` and for the chosen header.

`TIP_DEBUG_CLIENT_IP` writes client IPs to the log. Turn it off right after the check; it is not for normal operation.
