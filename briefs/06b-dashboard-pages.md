# Brief 06b: owner login and dashboard pages

Goal: build the owner login and dashboard pages from
briefs/assets/owner-preview.html on top of the 06a APIs, so the owner can
sign in on a phone and use everything the preview shows, live. Save this brief
as briefs/06b-dashboard-pages.md.

Mostly unattended. Stop and ask only if the preview asks for something the API
can't give, or a rule below conflicts with what you find.

## Read first
CLAUDE.md, CODEBASE.md, docs/OWNER_CHANNEL.md, the preview, brand.css,
brand.js, fonts.css, tip.html/tip.css/tip.js (patterns and CSP), the current
owner-login.html, owner.css, owner-login.js, OwnerPageController, and the
dashboard, owners and tipping refund endpoints.

## Design
Match the preview closely at 390 px and 1280 px: layout, colours, type,
spacing, the peeking panda on the login card and the notes, the panda covering
its eyes on hidden messages, the busiest hour card with bars, chips, rows,
detail, refund confirm, toast, empty states. Use brand.css tokens and add
owner styles to owner.css (rewrite it; the old --accent/--surface version goes).
Fonts come from /fonts/fonts.css, never Google. Leave out the "in Spanish" tag
on request groups. The preview's sample data and its preview toolbar are not
part of the product.

## Security rules (these override the preview's code)
- Strict nonce CSP as on every page: no inline scripts, no style attributes in
  HTML, no 'unsafe-inline'. Values that must be set per element (bar heights)
  are set from JS with element.style.setProperty, which CSP allows.
- Every value from the server goes into the page with textContent or
  createTextNode. owner.js never uses innerHTML, outerHTML,
  insertAdjacentHTML or document.write. Build elements with createElement.
  Message text, translations, owner notes, digest bullets, group labels, song
  titles and the display name are all untrusted.
- Only same origin requests. No third party at all on these pages.
- Every state changing request sends the CSRF token: refund and sign out.
- Any 401 from the API sends the browser to /owner/login.

## Pages and serving
- GET /owner/login: rebuilt login page. Same form post and owner-login.js
  behaviour (LOGIN_FAILED, RATE_LIMITED, ?signedOut), new look.
- GET /owner: owner.html with nonce and CSRF token, title "TiP | Dashboard",
  the same icons and theme colour as the other pages, no link preview tags,
  Cache-Control no-store. owner.js does the rest.

## Data
- On load: /api/owner/me, /today, /digest, /requests, /devices, and
  /tips?size=20. "Show older tips" loads the next page. Show a small loading
  state, and a retry note if a request fails.
- Filters: All; Warm, Playful, Requests, Plain map to kind=... with
  flagged=false; Hidden maps to flagged=true. Changing filter reloads page 0.
- Hidden messages panel: /tips?flagged=true&size=5.
- Money: amountMinor with Intl.NumberFormat for the currency, never floating
  point arithmetic on amounts beyond the final division for display.
- Times in the owner's time zone from /me, with Intl: "just now", "8 min ago",
  "2 h ago", then "Yesterday", then a short date.
- Busiest hour card from today.hours and busiestHour: bar heights relative to
  the largest count, the busiest bar gold, first and last hour labels, an
  aria-label that says the hour and count. Hidden when busiestHour is null.
- Notes card from /digest. When it's null, show the kraft card with a short
  line: "The panda will write notes once a few people leave messages today."
- Requests panel from /requests: topSong card only when not null, groups
  newest first.
- Tip rows: kind icon, kind name, languageName tag when set, status badge,
  amount, message (or "No message", or "Message hidden" plus flagReason for
  flagged), relative time. "Show in English" when englishText is set, labelled
  "Translated by AI from <language>". Detail: time, what the panda played from
  reaction and repeats (hide the line when repeats is null), status, the owner
  note labelled "AI note from the message", and refund for CAPTURED tips.

## Refund
Button, then an inline confirm (no browser dialogs), then a disabled working
state. 200: "Refunded". 202: "Refund sent, waiting for PayPal". 409: refresh
the tip and say it can't be refunded any more. 502 or network error: an error
note with Try again. Never send twice for one click. Update the row and today's
numbers from the server after any outcome.

## Live
- Open /ws/owner after the first load. The "live" tag shows only while it's
  open.
- tip{id}: GET /api/owner/tips/{id}. A new tip goes on top with the arrive
  animation and the toast, but only if it matches the current filter; an
  existing one updates in place. A 404 removes it.
- today: refetch /today. digest: refetch /digest. device: update the status
  line ("Panda is online" or "Panda is offline · last seen 4 min ago").
- closing signed_out or session_expired: go to /owner/login. closing replaced:
  show "The dashboard is open in another tab" and don't reconnect until the
  page is focused again.
- Reconnect with backoff 1 to 60 s with jitter. While disconnected, refetch
  /today and page 0 every 30 s so the page never goes stale.
- Coalesce bursts: at most one refetch of each kind in flight at a time.

## Accessibility
Real buttons, aria-expanded on rows and the translate link, aria-pressed on
chips, the toast in a polite live region, visible focus, 44 px touch targets,
and prefers-reduced-motion turns off the arrive animation, blinks and the
toast slide.

## Small server fix
The device channel sends hello before it registers the session. Register
first, then send hello, so hello always means "you're registered". Adjust the
tests that relied on the old order.

## Tests
- Page tests in the style of PandaPageTest and TipPageTest for both pages:
  CSP header with nonce and no 'unsafe-inline', no inline styles or scripts in
  the HTML, no outside origins in the HTML, owner.css, owner.js or fonts,
  icons, title, no preview tags, no-store, CSRF token present, /owner
  redirects to login when signed out.
- A test that fails if owner.js contains innerHTML, outerHTML,
  insertAdjacentHTML or document.write.
- The hello ordering.
- Then check it in a real browser: run the app with the dev profile and look at
  both pages at 390 and 1280 wide (Playwright or Chrome), including a live tip
  from the fake device or a sandbox tip, a refund, the translate link and the
  empty state of a new owner. Save screenshots to briefs/assets/screenshots/06b/06b-*.png and
  list any differences from the preview in the report.

## Done when
gradlew build passes, the screenshots are saved, CODEBASE.md and README.md are
updated, and everything is committed locally on develop, not pushed.

## Report
The usual format, plus the screenshots list, differences from the preview and
why, and anything that felt wrong to use on a phone.
