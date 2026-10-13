Brief 07a: judge sandboxes, public demo, abuse protection (backend)

Read CLAUDE.md, CODEBASE.md, ARCHITECTURE.md, DEVICE_PROTOCOL.md and
OWNER_CHANNEL.md first. Save this brief as briefs/07a-judge-sandboxes.md.
Backend and APIs only; pages come in 07b after a design preview. Where a
check needs a page, test the API.

Context
TiP must stay usable, unattended, until 31 Dec 2026 (access closes
2027-01-01 00:00 Europe/London). Three access levels:
- Public, no code: tip the real demo-panda and watch it (exists), plus a
  shared read only demo dashboard (new).
- Code holders (judges, owner, teammate): a private, fully working sandbox
  owner with its own virtual panda, valid 72 hours (new).
- The real owner account and physical panda: unchanged and unreachable from
  the other two levels.
The only hard limit is a $10 spend limit in the Anthropic console. Every
other protection must be sized so an honest person never hits it.

1. Access codes (no database table, no admin UI)
- TIP_ACCESS_CODES env var: entries "label:sha256hex:expiresAt" separated
  by ";". Label [a-z0-9-]{1,32}, expiresAt ISO 8601 with offset. Parse at
  startup and fail fast on a malformed entry. Empty or unset means no codes.
- Code format: 4 groups of 5 Crockford base32 characters (100 bits), shown
  as TIP-XXXXX-XXXXX-XXXXX-XXXXX. Normalise input before hashing: trim,
  uppercase, drop spaces and dashes and the TIP prefix, map O to 0 and I/L
  to 1. Hash is SHA-256 of the normalised code. Compare against every
  entry with MessageDigest.isEqual (no early exit).
- Gradle task newAccessCode -Plabel=... -Pexpires=... generates a code
  with SecureRandom and prints the code once plus the env entry. It never
  writes either to a file. Document it in DEPLOYMENT.md, including that the
  code goes only into Devpost's private Additional info field, never into
  the repo or chat.
- An expired code is rejected exactly like a wrong one.

2. Sandbox owners
- POST /judges/session (form post, same CSRF and pattern as owner login)
  with code and the browser's time zone. Validate the zone with ZoneId,
  fall back to UTC. On success, in one transaction: create an owner of
  kind SANDBOX (no email, no password, display name "Judge", code label
  stored), expiresAt = min(now + 72 h, the code's expiry); a device of that
  owner with slug judge-<8 random base32 chars> and display name "TiP";
  the seeded data (section 3); then sign the browser in with the normal
  owner session, whose lifetime never exceeds expiresAt. Redirect to the
  dashboard.
- Wrong or expired code: the same failure response as a wrong login,
  RATE_LIMITED as for login.
- Sandbox devices are virtual only: the device channel rejects them exactly
  like an unknown device (no hardware registration). Their observer and
  tip pages work normally.
- Expiry: every owner request and /ws/owner checks it. An expired sandbox
  gets 401 with reason sandbox_expired, and open sockets get
  {type:"closing", reason:"sandbox_expired"}. Its tip and watch pages
  answer like an unknown device.
- Cleanup job, hourly: delete expired sandbox owners and everything under
  them (tips, deliveries, digests, sessions, resume tokens) via FK cascade
  or explicit deletes, in bounded batches. A PayPal webhook or capture for a
  deleted tip is acknowledged and logged, never an error loop.
- Isolation: sandbox owners see only their own data with the identical 404
  for anything else. Add tests across two sandboxes and the real owner for
  every /api/owner endpoint and the reveal and verdict endpoints.

3. Seeded data, shared by dev, sandboxes and the public demo
- Move the sample set into one component used by DevTipSeeder, sandbox
  creation and the public demo. Use invented song and band names only, no
  real songs, artists or brands, in dev too (the video and judges use
  these). Keep the language mix, including a hidden Bulgarian message with
  its translation and a hidden English one.
- Backdate over 3 days in the owner's zone, with several tips today, so
  /today, /requests and the notes all have content. Store a pre-written
  digest and request groups so no AI call is needed to fill a new sandbox.
- Seeded tips carry demo = true, no PayPal ids, no deliveries to send.
- Everything works on seeded tips exactly as on real ones (reveal,
  translate, verdicts, digest after changes), except refund: refunding a
  demo tip returns 409 with code DEMO_TIP and no PayPal call. Tip items
  expose demo so 07b can label it.

4. Public read only demo dashboard
- One owner of kind PUBLIC_DEMO, created idempotently at startup, with a
  device that is also virtual only.
- POST /demo/session (no code) signs the browser in to that owner with a
  read only session. Per IP rate limit, generous.
- Read only is enforced centrally: every non GET /api/owner/** request and
  sign out side effects aside, any mutation returns 403 DEMO_READ_ONLY.
  Add a test that enumerates every mutating owner mapping from the
  handler mappings and asserts 403, so a future endpoint can't slip
  through. Reveal (a GET on seeded content) is allowed.
- No /ws/owner for read only sessions (refuse the upgrade with the usual
  closing reason "read_only"); the per owner socket limit must not apply to
  or be exhausted by the public demo.
- A job at 00:05 Europe/London deletes and reseeds the public demo's data
  so "today" stays current.

5. Continue on another device
- POST /api/owner/resume-link (sandbox sessions only, CSRF) returns a link
  with a 128 bit token, stored hashed, valid 10 minutes, single use, bound
  to the owner.
- GET /judges/resume?t=... consumes it atomically (one UPDATE ... WHERE
  used_at IS NULL AND expires_at > now RETURNING), sets a normal session
  cookie and 303s to the dashboard so the token leaves the address bar.
  Cache-Control no-store, Referrer-Policy no-referrer. Invalid, used or
  expired tokens all get the same response. Per IP rate limit.

6. Abuse protection without usage caps
- Verify and prove with a test that classification only ever runs after a
  verified PayPal capture, never for a created but unpaid tip.
- Per IP limits (Bucket4j, client IP from the existing header logic) on tip
  order creation, capture, /judges/session, /demo/session and resume. Size
  them well above honest use; make them env configurable with defaults in
  DEPLOYMENT.md.
- AI failure fallback: when the Anthropic call fails (spend limit or other
  4xx, 429, 5xx, timeout), the tip still completes, the panda gets PLAIN
  x1, and the tip is stored with aiStatus UNAVAILABLE (exposed on tip
  items for 07b). Spend limit and auth style errors open a breaker that
  skips AI calls for 10 minutes, then tries one call. No retry loop that
  multiplies calls. Digest generation follows the same breaker and keeps
  the last stored notes. GET /api/owner/me exposes aiResting: true while
  the breaker is open.
- Check that the tip page's PayPal buttons allow card guest checkout in
  sandbox (no funding source disabled that blocks it) and document the
  sandbox test card generator in README.md.

7. Logs (no personal data: IPs as HMAC with a random per process key, never
   codes, tokens, emails or message text)
- One INFO line each: sandbox_created (code label, owner id),
  sandbox_expired, resume_link_used, demo_session, ai_breaker_opened and
  closed.
- WARN: code_rejected and rate_limited (endpoint, hashed IP).
- At 00:10 Europe/London one daily_summary line from the database: per code
  label sandboxes created in the last 24 h, tips paid by owner kind, AI
  calls and AI failures.

8. Schema, docs, rules
- One Flyway migration (V8), nullable additions where the previous release
  might still run during deploy.
- Update CODEBASE.md, ARCHITECTURE.md (new decision: three access levels,
  and the end date), DEPLOYMENT.md (env vars, code generation, limits),
  README.md (public demo and card checkout).
- CLAUDE.md: commit only a few representative screenshots per brief;
  bulk verification shots stay outside the repo.

Tests: everything above, time driven with MutableClock and jobs triggered
from tests. Specifically: code normalisation and constant time compare,
expired code, sandbox creation transaction, 72 h and code expiry cap,
cleanup cascade, webhook for a deleted tip, device channel rejects sandbox
and demo devices, DEMO_TIP refund, read only mapping sweep, public demo
reseed, resume token single use and race, breaker behaviour, no AI before
capture, log lines contain no IP or code.
