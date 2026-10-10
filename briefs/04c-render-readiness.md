# Brief 04c: Render readiness

Read `CLAUDE.md`, `CODEBASE.md` and `docs/ARCHITECTURE.md` first. Work on `develop`, commit locally in logical commits, never push. End with the report format from `CLAUDE.md`. If something is unclear, make the most reasonable choice and list it under "Decisions I made".

## Context

The app is about to run on Render: a Docker web service (Starter, 512 MB, 0.5 CPU) and Render PostgreSQL 16 (Basic-256mb), Frankfurt region. Render terminates TLS at its proxy, forwards plain HTTP to the container, sets `X-Forwarded-For` and `X-Forwarded-Proto`, sends `SIGTERM` on deploys, and probes a health check path. It also sets a `PORT` environment variable. Locally the owner runs through ngrok, which is also a proxy, so some of this may already work. Verify each point with a test rather than assuming.

Do not change anything on Render itself. This brief is code, config and docs only.

## 1. Port

The app must listen on `PORT` when it is set, otherwise keep today's behaviour. `server.port=${PORT:8080}` in `application.properties` does this, and a `SERVER_PORT` environment variable still overrides it, so the owner's local 8081 setup keeps working. Confirm that precedence and note it in the README.

## 2. Client IP behind the proxy (most important)

Every rate limit is keyed by client IP (tip creation, failed device handshakes, observer connections, owner login). Two failure modes, both must be ruled out by tests:

- **Shared bucket:** the app sees the proxy address for every request, so all users share one limit. Ten tips a minute for the whole world.
- **Spoofing:** the app takes the leftmost `X-Forwarded-For` entry, which the client controls, so an attacker gets a fresh bucket per request by changing the header.

The correct client IP is the rightmost address that is not a trusted proxy. Render's proxy appends the real client address, so a client prepending fake entries must not change the result. Tomcat's remote IP handling (`server.forward-headers-strategy=native`, with internal proxies left at private ranges) behaves this way; check how the rate limiters currently obtain the IP and make them all use the same resolved address.

Tests:
- Two requests with different leftmost `X-Forwarded-For` values but the same rightmost client address land in the same bucket.
- Two requests with different real client addresses land in different buckets.
- A request with no `X-Forwarded-For` still works (local runs, tests).

Apply the same resolution to the WebSocket handshake paths.

## 3. HTTPS awareness

With `X-Forwarded-Proto: https`, the app must treat the request as secure: session cookie marked `Secure`, any absolute URLs and redirects built with `https`, and nothing redirecting to `http`. Add a test that a login over a forwarded HTTPS request sets a `Secure` session cookie.

## 4. Health check

Confirm the public health path and state it in the report and the deployment doc. It must be cheap and must **not** call PayPal or Anthropic. If Render's health check included external services, a PayPal sandbox outage would make Render restart a healthy app in a loop. Database reachability is fine to include. Make sure the response reveals no component details publicly.

## 5. Process and memory

- The Dockerfile must start Java in exec form so the JVM is PID 1 and receives `SIGTERM`, letting the existing graceful shutdown close WebSocket sessions with 1001.
- Add `-XX:MaxRAMPercentage=75` (or equivalent via `JAVA_TOOL_OPTIONS` in the Dockerfile) so the heap fits a 512 MB container instead of the JVM default of 25%.
- Set the Hikari maximum pool size explicitly to a small value (5 is reasonable) and make it overridable by environment. Basic-256mb has a low connection limit and Render may briefly run the old and new instance together during a deploy.

## 6. URLs and origins

`TIP_PUBLIC_BASE_URL` will be `https://<service>.onrender.com`. Check nothing hardcodes `localhost`, ngrok or `http`: the tip page, the panda page's WebSocket URL (must become `wss`), the observer Origin allow list, and PayPal return and cancel URLs. All must derive from the configured base URL or the request. Add or extend a test with an `https` base URL.

## 7. Database settings

Render's dashboard gives host, port, database, user and password separately. Confirm the app can be configured from those as separate environment variables (whatever names `.env.example` already uses), and that Flyway runs on startup against an empty database. Require SSL to the database only if it works with Render's internal connection; if unsure, leave it off for the internal hostname and say so.

## 8. Deployment doc

Create `docs/DEPLOYMENT.md` with:
- The full list of environment variables for Render, grouped, each with one line saying what it is and whether it is secret. Mark which must differ from local (device secret, demo owner password, PayPal webhook id, public base URL).
- The health check path.
- How deploys happen: CI's deploy job calls the Render deploy hook after a push to `main`, and Render's own auto deploy is off so there is only one mechanism.
- A short smoke test list for after the first deploy.

Link it from the README.

## Done when

`./gradlew build` passes, Spotless clean, all new tests green, docs updated. The report lists the health path, the final environment variable names, and anything the owner must set in Render that the doc does not already cover.