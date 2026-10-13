# TiP agent rules

TiP (Tip integrated Panda) is an NFC tipping device built for the PayPal AI Hackathon. A cardboard panda holds a coin with an NFC tag inside. A phone tap opens a tip page, the tipper pays with PayPal and can leave a message, Claude reads what kind of message it is (warm, playful, a request, or plain), and the panda plays a light pattern for that kind. Abusive messages are flagged and get the plain blink, so trolls get no reaction. Owners get a dashboard with their tips, AI summaries, notes on requests and feedback, and moderation.

Before any task, read `docs/ARCHITECTURE.md`, `docs/DEVICE_PROTOCOL.md` and `CODEBASE.md`.

## Who decides

Briefs come from the lead, a Claude chat acting as tech lead. The humans relay them to you. Implement the brief as written.

- If the brief is unclear, contradicts these docs, or needs a decision with real trade offs, stop and put it under "Open questions" in your report. Do not guess on anything that touches money, security or a shared contract.
- Small decisions the brief does not cover (naming, helper structure, test layout) are yours. List them under "Decisions I made".
- Never change a shared contract unless the brief says so. Shared contracts are: the REST API, the device protocol, the database schema of another feature, and the API classes of another feature package.

## Stack

- Java 21, Spring Boot (latest stable release), Gradle with Kotlin DSL and the wrapper
- PostgreSQL, Flyway migrations, Spring Data JPA
- PayPal Orders v2 in sandbox. Use the official PayPal Server SDK for Java where it covers the call, plain `RestClient` where it does not. Use the APIMatic Context Plugin to look up PayPal API details instead of guessing.
- Anthropic Java SDK, model `claude-haiku-5-5`
- JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock
- Spotless with palantir-java-format, ArchUnit
- Firmware: PlatformIO, Arduino framework, ESP32
- Web pages: plain HTML, CSS and JavaScript served by Spring Boot from `src/main/resources/static`. No build step, no framework.

## Code rules

- Package by feature under `dev.tip`: `tipping`, `paypal`, `devices`, `ai`, `dashboard`, `owners`, `shared`. A feature uses another feature only through classes in that feature's `api` subpackage, or through Spring application events. ArchUnit enforces this.
- Constructor injection only. Records for DTOs, commands, events and value objects.
- Money is a `long` in minor units plus an ISO 4217 currency code. Never `double` or `float`.
- Time is `Instant` in UTC from an injected `Clock`. Never call `Instant.now()` directly.
- Validate every input at the edge with Bean Validation. Never trust amounts, ids or states sent by a browser or a device.
- No secrets in code, tests or committed files. Configuration comes from environment variables. When you add one, add it to `.env.example` with a comment.
- Log with SLF4J. Never log secrets, device secrets, full PayPal payloads or tip message text at INFO or above.
- No dead code, no commented out code, no TODO without a GitHub issue number.
- Every Flyway migration must keep the previous release working against the new schema, because Render's zero downtime deploy runs the new instance's migration while the old instance still serves traffic. Add columns nullable or with a default. Never rename or drop a column, table or constraint in the same release that stops using it; do that in a later release.
- An after-commit event listener that opens a new transaction must run on an async executor. Run synchronously, it holds the committed transaction's connection while taking a second one, and exhausts the pool under load.
- Domain failures throw a feature specific exception that one `@RestControllerAdvice` maps to RFC 9457 problem details. No stack traces reach a client.
- Anything a user typed is rendered with `textContent` or escaped. Never `innerHTML` with user data.
- Web pages: plain, accessible, phone first. Only third party script allowed is the PayPal JS SDK.

## Tests

- Every behaviour you add gets a test. Prefer tests through the HTTP layer (MockMvc or a real port) against PostgreSQL from Testcontainers.
- PayPal and Anthropic are always stubbed with WireMock. Tests never call a real external service.
- Cover the failure paths the brief lists, not only the happy path.
- `./gradlew build` must pass (compile, Spotless, tests, ArchUnit) before you report done. If Docker is not running, say so in the report. Never skip or disable a test to get green.
- New screenshots go in `briefs/assets/screenshots/<brief>/`, for example `briefs/assets/screenshots/06c/06c-hidden-revealed-390.png`. Commit only a few representative screenshots per brief; bulk verification shots stay outside the repo (the scratchpad or another local folder). Design previews, icons and other assets stay in `briefs/assets/`.

## Git

- Lead's agent works on `develop`.
- Commit locally in small logical commits. Never push, never force push, never rewrite history, never touch `main`.
- Unpushed local commits may be reworded only with explicit lead approval; pushed history is never rewritten.
- Commit message: short subject line, blank line, body grouped by area with `-` bullets. Include the root cause for bug fixes.
- On Windows PowerShell, write multi line messages to `msg.txt` from a here-string without a BOM (`[IO.File]::WriteAllText($p, $msg, [Text.UTF8Encoding]::new($false))`; `Set-Content -Encoding utf8` adds one), commit with `git commit -F msg.txt`, then delete `msg.txt`. Never use `git commit -m` for multi line messages.
- Never chain a check and a commit with ;. Run the check, confirm it passed ($LASTEXITCODE -eq 0), then commit in a separate command.

## Finishing a task

1. `./gradlew build` is green. For firmware changes, `pio run` and `pio test -e native` are green too.
2. Update `CODEBASE.md`. It is a map of what exists, where it lives and how it connects. Not a diary. A change that makes `CODEBASE.md` inaccurate updates it in the same piece of work, even when the instruction says "no other changes".
3. Update `README.md` if setup or run steps changed.
4. Print your report as your final message, in exactly this format:

```
### Report
**Brief:** <id and title>
**Summary:** 2 to 4 sentences.
**Changes by area:**
- ...
**Tests:** what was added, total count, all passing yes/no
**Decisions I made:** one line of reasoning each, or "none"
**Deviations from the brief:** or "none"
**Open questions for the lead:** or "none"
**Human actions needed:** secrets, dashboard settings, anything only a person can do, or "none"
```
