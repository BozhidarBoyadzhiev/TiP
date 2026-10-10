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
| `TIP_DEMO_OWNER_EMAIL` | no | Owner login, created on the first start. |
| `TIP_DEMO_OWNER_PASSWORD` | yes | **Differs from local.** 12 to 72 characters. Only used when the owner is created; changing it later has no effect. |
| `TIP_DEMO_OWNER_NAME` | no | Display name, defaults to the email. |
| `TIP_DEMO_DEVICE_SLUG` | no | Slug of the demo panda, the last part of the NFC tag URL. |
| `TIP_DEMO_DEVICE_NAME` | no | Name the tip page and the panda show. |
| `TIP_DEMO_DEVICE_SECRET` | yes | **Differs from local.** A fresh 32 byte base64url secret (see README). The firmware of the panda that talks to Render needs the same value. |

### JVM

The `Dockerfile` sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, so the heap uses three quarters of the 512 MB instead of the JVM's default quarter. Do not set `JAVA_TOOL_OPTIONS` on Render unless you mean to replace that.

## Health check

Set the service's **Health Check Path** to:

```
/actuator/health
```

It answers `200 {"status":"UP"}` and nothing else: no component names or details. It checks the database connection and local disk space only. It never calls PayPal or Anthropic, so an outage there cannot make Render restart a healthy app. If the database is unreachable it answers 503.

## Behind Render's proxy

Render terminates TLS and forwards plain HTTP with `X-Forwarded-For` and `X-Forwarded-Proto`. The app uses Tomcat's remote IP handling (`server.forward-headers-strategy=native`): it trusts these headers only from private addresses, which is where Render's proxy connects from, and takes the rightmost `X-Forwarded-For` entry that is not a private address as the client. A client that prepends fake addresses therefore still lands in its own rate limit bucket, and different clients get different buckets. Requests forwarded as `https` count as secure: the session cookie is `Secure`, redirects stay on `https`, HSTS is sent, and the panda page opens `wss://`.

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
8. Rate limits are per client. Six wrong sign ins from one network get `Too many sign in attempts` on the sixth; a phone on mobile data can still sign in right after. Then send eleven tip requests for an unknown slug, each with a different made up `X-Forwarded-For`; the first ten get 404 and the eleventh 429:

   ```bash
   for i in $(seq 1 11); do curl -s -o /dev/null -w "%{http_code}\n" -H "X-Forwarded-For: 203.0.113.$i" -H "Content-Type: application/json" -d '{"amountMinor":100,"currency":"GBP"}' https://<service>.onrender.com/api/public/devices/no-such-panda/tips; done
   ```
9. Redeploy through CI. The fake device logs a 1001 close and reconnects, and the panda page goes back to **Live**.
