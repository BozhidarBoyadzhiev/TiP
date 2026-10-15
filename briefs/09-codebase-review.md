# Brief 09: Full codebase review and fixes

## Why
TiP is feature complete for the hackathon. The repo goes public in early
November and production freezes after submission (12 Nov). Before that we
want one careful pass over everything: find what is broken, unsafe, fragile
or wrong, and fix what matters. Take as much time as you need. Depth beats
speed here.

This is a review and fix brief, not a feature brief. Do not add features,
do not redesign pages, do not change behaviour that works as intended.

## How the work is split
This is too big for one session. Work in passes. The findings file is the
thread that connects them.

1. **Pass 1 (this session): audit, then fix Critical.**
   Read the whole codebase first (backend, static pages, firmware, build,
   CI, config, migrations, tests). Write every finding to
   `briefs/reviews/09-findings.md` before fixing anything. Then fix the
   Critical findings, one commit per finding or per tight group.
2. **Pass 2 and later (fresh sessions):** the owner pastes this brief plus
   "continue from 09-findings.md". Pick up the next severity level, fix,
   update the file. Stop when everything High is done and Medium is either
   done or marked for the lead.
3. If you run low on context mid pass, stop at a clean commit, update the
   findings file with exactly where you are, and report. Do not start a
   fix you cannot finish.

## Severity
- **Critical:** money can be lost or wrong, a payment can be captured
  twice or not recorded, someone can read or change another owner's data,
  a secret leaks, auth can be bypassed, the server can be crashed or run up
  a bill by an anonymous user, or the demo breaks for judges.
- **High:** a real bug a judge or tipper could hit, a race that corrupts
  state, a failure mode with no recovery, a missing test around money or
  auth, anything that would embarrass us in a public repo.
- **Medium:** correctness or robustness issues unlikely to be hit,
  misleading code, weak tests, small inefficiencies.
- **Low:** style, naming, dead code, comments.

Fix Critical and High. Fix Medium when it is small and safe. List Low only.
When in doubt, mark it "lead decision" with a short reason and move on.

## Findings file format
One entry per finding:

```
### 09-F012 [High] Webhook and capture race can double record a tip
Where: dev.tip.paypal.WebhookHandler, TipCaptureService.capture
What: ...
Why it matters: ...
Fix: ... (or "lead decision: ...")
Status: open | fixed in <short sha> | lead decision | won't fix (reason)
```

## What to look at
Go through every area. These are the questions that matter most, not a
complete list.

**Payments (dev.tip.paypal, dev.tip.tipping)**
- Is the amount always decided by the server, never trusted from the
  client? Currency fixed? BigDecimal everywhere, correct scale and rounding?
- Capture: idempotent under retry, double click, two tabs, and a webhook
  arriving before or during capture? What does the database guarantee
  (unique constraints, row locks, isolation) versus what the code assumes?
- Webhooks: signature verified on every event before any state change?
  Replay of an old event safe? Unknown event types ignored safely?
  Out of order events handled?
- Refunds: can one be issued twice? Partial states after a failure?
- What happens when PayPal times out or returns 5xx at each step? Is any
  tip left captured at PayPal but missing in our database, or the reverse?
  Is there a way to reconcile?
- Is the PayPal request id / idempotency key used where PayPal supports it?

**AI (dev.tip.ai)**
- Message always passed as data, never as instructions? Output validated
  against the schema and treated as untrusted everywhere it is used
  (database, pages, device)?
- Timeouts, retries and their bounds. Can one tip cause many model calls?
  Can an anonymous user drive spend (digest, translation, requests)?
- Any path where an AI failure blocks or delays a payment or a device
  reaction beyond the fallback?
- Are message contents or model output logged where they should not be?
- Do not change kind definitions or the eval to make numbers look better.

**Devices and WebSocket (dev.tip.devices)**
- Device authentication on connect and on every message. Can one device
  impersonate another or receive another device's events?
- Message size limits, malformed JSON, slow consumers, reconnect storms.
- Reaction delivery: never replayed, never changed, consistent with the
  "flagged gets PLAIN x1" rule.
- The closing message behind Render's proxy still sent before close.

**Auth, owners and access codes (dev.tip.owners, dev.tip.dashboard)**
- Constant time comparison for codes and tokens. Session fixation (the
  resume sign in changes the session id; is that true for every sign in?).
- CSRF on every state changing endpoint.
- Owner scoping done in SQL on every query, identical 404 for "not yours"
  and "does not exist". Check every repository method, not a sample.
- Public demo gets 403 DEMO_READ_ONLY on every write.
- Rate limits (Bucket4j) on every endpoint an anonymous user can hit.
  Client IP taken from CF-Connecting-IP only as configured; can the header
  be spoofed to dodge limits in any deployment we document?
- Access code expiry enforced server side.

**Web layer and static pages**
- CSP nonce on every page, no inline handlers, textContent only, no
  innerHTML with data. Framing rules exactly as decided (only /t/**, /p/**
  and /owner framed, by 'self'; frame-src 'self' only on /owner/live).
- Error responses: no stack traces, no internal ids or SQL in messages.
- Cache headers on pages with private data.
- Static asset renames when content changes.

**Data (Flyway, JPA, JdbcClient)**
- Migrations V1 to V8: constraints that the code relies on actually exist
  (unique, not null, foreign keys, checks). Indexes for the queries we run.
- Transactions: boundaries correct, no external calls (PayPal, Anthropic)
  inside a database transaction that holds locks.
- N+1 queries, unbounded queries, missing pagination.

**Config and secrets**
- Every required env var fails fast at startup when missing. Defaults fail
  closed. No secret in code, tests, logs, error messages or the repo
  history (run gitleaks over the full history and report, do not rewrite
  pushed history).
- Judge test credentials only ever come from env vars.

**Firmware (firmware/)**
- Buffer sizes, string handling, JSON document sizes, NDEF record length
  limits. Anything that can overflow on a long slug or base URL.
- Watchdog, reconnect backoff, task priorities still as documented.
- No secrets committed (Wi-Fi, device tokens). Check how they are provided.
- If a firmware fix is needed, make it and run the native tests, but do
  **not** flash. Say in the report that it needs flashing and the owner
  will close his serial monitor first.

**Tests and CI**
- Gaps around money, auth, scoping and concurrency matter most. Add tests
  for every Critical and High fix (a test that fails before the fix).
- Flaky tests are fixed, never re-run.
- ArchUnit rules still match the package layout.
- CI jobs build, secrets-scan, firmware all still meaningful.

**Public repo readiness**
- Licences of everything we ship (Fredoka font licence file present, any
  copied code credited). No third party trademarks or logos in assets.
- TODOs, dead code, commented out code, leftover debug endpoints.
- CODEBASE.md accurate after your changes.
- Do **not** edit README files. The owner is reviewing them himself.
  If you find something wrong in a README, put it in the findings file.

## Standing decisions (do not change; flag if you think one is wrong)
- Dark warm palette, no blue. "One family, two moods."
- No third party requests except PayPal's SDK.
- Model output untrusted everywhere.
- Owner scoping in SQL, identical 404. Public demo 403 DEMO_READ_ONLY on
  writes, including the resume link.
- Flagged messages hidden by default; device reaction never changed or
  replayed. Public sample never shows the real panda's messages.
- Single use tokens are never spent by GET.
- NFC fails independently of everything else. PN532 tag emulation only.
- No admin panel.
- Don't tune kind definitions to the eval.

## Rules
- Work on develop. Commit locally, never push.
- Every commit builds and passes on its own (`./gradlew clean build`,
  Docker Desktop running).
- One concern per commit, message says what and why.
- Behaviour changes only where a finding requires it. If a fix changes
  something a judge or owner would notice, mark it in the report.
- Changes that make CODEBASE.md inaccurate update it in the same commit.
- PowerShell: check `$LASTEXITCODE`, never chain a check and a commit
  with `;`, commit message files without BOM.

## Report
End each pass with the CLAUDE.md report format, plus:
- counts by severity: found, fixed, open, lead decision
- the list of "lead decision" items with your recommendation
- anything that needs flashing, a Render env change, or a manual check
  by the owner, as short step by step instructions
- what the next pass should start with