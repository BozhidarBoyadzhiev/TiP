# Brief 07e: judge payment polish

Four small items from the owner's Render test of 07d. One commit per item,
each building on its own.

## 1. Readable PayPal card form (all tip pages)
PayPal's inline "Debit or Credit Card" form renders in PayPal's iframe with
a transparent background and dark text, so on our dark card the "Billing
address" heading, the terms paragraph and the close X are unreadable. We
cannot style inside PayPal's iframe. Fix it from our side: put
`#paypal-buttons` on a light paper panel (`--paper` background, padding
about 14px, radius matching the card, subtle edge), so both the gold
buttons and the expanded card form sit on a light surface. Applies to
every tip page, not only `?embed`. Check by eye at 390px and 1280px, with
the buttons only and with the card form open, in the real PayPal sandbox.
Screenshots of both states in briefs/assets/screenshots/07e/.

## 2. Test payment details box (sandbox only)
Judges need the sandbox card and buyer account at hand.

- Config, read from the environment, never in the repo or in tests:
  `TIP_JUDGE_CARD_NUMBER`, `TIP_JUDGE_CARD_EXPIRY`, `TIP_JUDGE_CARD_CVC`,
  `TIP_JUDGE_PAYPAL_EMAIL`, `TIP_JUDGE_PAYPAL_PASSWORD`. All optional.
  Tests use obviously fake values (not card shaped numbers, so gitleaks
  stays quiet). Document them in DEPLOYMENT.md and `.env.example` with
  empty values.
- `GET /api/owner/test-payment`: sandbox session only, `no-store`.
  Returns `{card: {number, expiry, cvc} or null, paypal: {email,
  password} or null, billing: {name, postcode, mobile, email}}`. `card`
  is null unless all three card values are set; `paypal` likewise.
  `billing` is fixed, harmless sample data in code: name "Test Judge",
  postcode "SW1A 1AA", mobile "07700 900123", email "judge@example.com".
  A real owner and the public demo get the 404 of an unknown path.
  Never log the values.
- Dashboard: a "Test payment details" button in the sandbox strip (also
  inside side by side, where the dashboard is column 3). It opens a
  dialog in the style of the resume sheet: sections "Card", "Billing
  details" and "PayPal account", each value with a Copy button
  (`navigator.clipboard.writeText`, with a short "Copied" state, and the
  value selectable as a fallback). A short line on top: "These are
  PayPal sandbox test details. No real money moves." Escape and a click
  outside close it, focus stays inside. Hide the button when both `card`
  and `paypal` are null. Values go in with textContent only.

## 3. Resizable columns in side by side
In `/owner/live`, the two gaps between columns become drag handles
(`role="separator"`, `aria-orientation="vertical"`, focusable; arrow keys
move 16px; double click resets). Minimum widths: tip 340px, panda 280px,
dashboard 400px. While dragging, lay a transparent cover over the iframes
so they don't swallow the pointer. Nothing is remembered; a reload starts
from the default widths. Keep it in live.js and live.css.

## 4. Card button sometimes needs a second click
On Render, "Debit or Credit Card" in the framed tip page did nothing on the
first click and opened on the second. Investigate before changing
anything: reproduce in the real PayPal sandbox inside and outside side by
side, on a fresh load and after picking an amount; check the console and
whether our `onClick` rejects (for example `validMinor()` not ready) or
PayPal is still loading the card form. Fix it if the cause is ours. If it
is PayPal's own behaviour, change nothing and say so in the report with
what you saw. Time box this to about an hour.

## Rules
Work on develop, commit locally, never push. Every commit builds on its
own (`./gradlew clean build`, Docker Desktop running). Strict nonce CSP,
textContent only, no inline handlers; changed static assets get new names
where they are long cached. Update CODEBASE.md. End with the CLAUDE.md
report format, including the env vars the owner must set on Render.