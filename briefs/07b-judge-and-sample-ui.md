# Brief 07b: judge access, sandbox and sample dashboard UI

Implement the approved design in briefs/assets/07b-preview.html. Front end
follows the preview exactly; the preview's innerHTML and Google Fonts are
preview only: production renders every string with textContent, uses the
self hosted Fredoka from brand.css, keeps the strict nonce CSP and makes no
third party requests.

## API
- /api/owner/me adds kind (OWNER, SANDBOX, PUBLIC_DEMO), readOnly and
  expiresAt (null unless SANDBOX). Tests for each kind.
- Owner tip DTOs expose demo and aiStatus if they do not already.
- Resume link for "Continue on another device": SANDBOX only (others get
  the identical 404). Returns the one time 10 minute URL, its expiresAt and
  a QR module matrix generated on the server with io.nayuki:qrcodegen (MIT,
  pin the version, note it for the third party notices), ECC medium.
  Cache-Control no-store. Rate limit minting per sandbox (for example 5 per
  10 minutes). The page draws the matrix on a canvas.
- Server side guard: /ws/owner refuses a PUBLIC_DEMO session with the
  usual {type:"closing", reason} first, so a read only page cannot hold a
  live socket even if the client is wrong.

## Pages
- /judges code page, states from the preview: empty, typed, opening,
  wrong code (one message for wrong and expired codes), too many tries
  (429), sandbox ended (arrive from SANDBOX_EXPIRED or close reason
  sandbox_expired), link used (invalid resume token). CSRF token handled
  the same way as the owner login page. Input stripped of spaces and
  dashes and upper cased; confirm the server normalises Crockford too.
- Sandbox dashboard: strip with expiry in the owner's zone, Tip your panda
  (judge device tip page), Continue on another device sheet with QR, link,
  Copy, countdown and "Make a new one". Status line links Watch it react
  (judge device virtual panda page). Demo badge on seeded rows, no refund
  button on demo rows: the detail shows "Demo tip. Send a real tip to try
  refunds." with Tip your panda. Server keeps 409 DEMO_TIP.
- aiStatus UNAVAILABLE: "Not read" badge and the detail note. aiResting
  from /me shows the resting strip.
- Public sample dashboard: strip, "Sample data · not live", Looking at A
  sample owner, Tip the real panda. No /ws/owner, no polling. Write
  buttons shown but disabled with the lock and "Read only". Show message
  works (reveal is a read; confirm the DEMO_READ_ONLY filter allows it and
  the sweep test agrees). Hide again only collapses on the client.
- Tip card foot: add "Curious what the owner sees? Look at a sample
  dashboard." linking to the sample dashboard.

## Tests and finish
Server tests for every new rule above, the existing rule and sweep tests
still pass, ArchUnit green. A few screenshots in
briefs/assets/screenshots/07b/ (phone and laptop). CODEBASE.md updated.
Report in the CLAUDE.md format.