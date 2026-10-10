# Brief 04d: Client IP on Render, plus small fixes

Read `CLAUDE.md`, `CODEBASE.md`, `docs/ARCHITECTURE.md` and `docs/DEPLOYMENT.md` first. Work on `develop`, commit locally in logical commits, never push. End with the report format from `CLAUDE.md`. If something is unclear, make the most reasonable choice and list it under "Decisions I made".

## Context

Brief 04c switched to Tomcat native remote IP handling, taking the rightmost non-private `X-Forwarded-For` entry. In production on Render, smoke test step 8a failed: eleven tip requests, each with a different made up `X-Forwarded-For`, all returned 404 and none returned 429. Step 8b passed: different real clients get different buckets.

Render staff have stated publicly that Render sets the **first** entry of `X-Forwarded-For` to the real client and appends the client supplied entries after it. Render support also recommends Cloudflare's `True-Client-IP` header for the client address. So on Render the spoofed value is the rightmost public entry, which is what our rule picks.

Neither "first entry" nor `True-Client-IP` is formally documented by Render, so we will not assume. This brief makes the header configurable and adds a temporary diagnostic so the owner can see, in production, which header a client cannot forge.

## 1. Configurable client IP header

Add `TIP_CLIENT_IP_HEADER`, defaulting to `X-Forwarded-For`, and bind it to Tomcat's remote IP header (`server.tomcat.remoteip.remote-ip-header`). Keep the internal proxies at private ranges.

Behaviour to verify with tests:

- Default (`X-Forwarded-For`): today's behaviour, all existing proxy tests unchanged.
- With `True-Client-IP` (and separately `CF-Connecting-IP`): a request from a private proxy address carrying that header resolves to the header's value; a spoofed `X-Forwarded-For` on the same request has no effect on the bucket; two requests with different header values land in different buckets.
- The header is absent: falls back to the connection address, so local runs and tests still work.
- The header contains something that is not a single IP literal (a list, a hostname, garbage): it must not crash the request and must not be used as the key. State what happens in the report.
- Both WebSocket handshake paths use the same resolved address.

If Tomcat's valve cannot do one of these correctly for a single value header, implement the smallest filter that does, and say why.

## 2. Temporary diagnostic log

Add `TIP_DEBUG_CLIENT_IP`, default `false`. When `true`, log one INFO line per request to `/api/public/**` with: the connection address before resolution, the resolved address, and the raw values of `X-Forwarded-For`, `X-Real-IP`, `True-Client-IP` and `CF-Connecting-IP` (absent shown as `-`). Nothing else: no bodies, no cookies, no other headers. Truncate each value to 200 characters. Add a test that it logs nothing when off.

Document in `docs/DEPLOYMENT.md` that this flag is for a one time check and must be turned off afterwards, because it writes client IPs to the logs.

## 3. Update the smoke test in `docs/DEPLOYMENT.md`

- Give step 8a in PowerShell 5.1 form as well as bash. PowerShell 5.1 mangles JSON quotes passed to `curl.exe`, so use a body file:

  ```powershell
  '{"amountMinor":100,"currency":"GBP"}' | Set-Content body.json -NoNewline -Encoding ascii
  1..11 | % { curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Forwarded-For: 203.0.113.$_" -H "Content-Type: application/json" --data-binary "@body.json" https://<service>.onrender.com/api/public/devices/no-such-panda/tips }
  Remove-Item body.json
  ```

- Add the same loop spoofing the configured client IP header, which must also end in 429.
- Add a new section "Choosing the client IP header on a new host" describing the diagnostic procedure below.

## 4. Migration rule in `CLAUDE.md`

Render's zero downtime deploys run the new instance's Flyway migration while the old instance still serves traffic. Add a rule: every migration must keep the previous release working against the new schema. Add columns nullable or with defaults; never rename or drop a column, table or constraint in the same release that stops using it; do those in a later release.

## 5. Gradle deprecation

`build.gradle.kts` line 23 uses the `by sourceSets.creating` delegate, which Gradle 9.6 deprecates and Gradle 10 removes. Replace it with the `create` or `named` API the warning suggests. `fakeDevice` and `aiEval` must still work.

## Done when

`./gradlew build` passes with no Kotlin DSL deprecation warnings from our build script, Spotless clean, new tests green, docs updated. The report lists the human steps below.

## Human steps to list in the report

1. Push `develop`, fast forward `main`, push `main`. Keep the fake device connected to production during this deploy to complete smoke test step 9 (1001 close, then reconnect).
2. In Render, set `TIP_DEBUG_CLIENT_IP=true` and save (this redeploys).
3. Send one request with all three headers forged to different values:
   ```powershell
   '{"amountMinor":100,"currency":"GBP"}' | Set-Content body.json -NoNewline -Encoding ascii
   curl.exe -s -o NUL -w "%{http_code}`n" -H "X-Forwarded-For: 203.0.113.1" -H "True-Client-IP: 203.0.113.2" -H "CF-Connecting-IP: 203.0.113.3" -H "Content-Type: application/json" --data-binary "@body.json" https://<service>.onrender.com/api/public/devices/no-such-panda/tips
   Remove-Item body.json
   ```
4. Find the diagnostic line in the Render log. A header that shows your real IP despite the forged value is one Cloudflare overwrites. Bring the line to the lead with your real IP masked.
5. The lead picks the header. Set `TIP_CLIENT_IP_HEADER` to it, set `TIP_DEBUG_CLIENT_IP=false`, save, and rerun step 8a for both headers.