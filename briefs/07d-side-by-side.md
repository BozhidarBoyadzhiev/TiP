# Brief 07d: side by side view for judge sandboxes

## Why
A judge with one screen has to juggle tabs to see a tip go through: the tip
page, the panda reacting and the dashboard. Side by side puts all three on
one screen, as three columns, behind a switch. It is optional and judge
only. Nothing that exists today is removed or changes when it is off.

Approved preview: briefs/assets/07d-side-by-side/ (index.html is the
wrapper, split-proposed.css holds the proposed CSS, mock.js is a fake
backend for the preview only and must not be copied). The screenshots in
the same folder show off, on, and after a tip.

## Scope
- Sandbox sessions only (`kind` SANDBOX). Real owners and the public demo
  never see the switch and cannot open the page.
- Desktop only: the switch shows at min-width 1100px. Below that the page
  falls back to the normal dashboard.
- The switch does not remember its state. Off on every new visit.

## What to build
1. `GET /owner/live` (OwnerPageController, owner chain): `static/live.html`
   with nonce, `no-store`, served to a sandbox session only. A real owner
   or the public demo gets the same 404 as an unknown path; signed out
   behaves like `/owner`. The page holds three columns with captions
   ("1 Leave a tip", "2 Watch the panda react", "3 See it on your
   dashboard"): the tip page in a phone shaped frame (390px), the panda
   page (flexible), the dashboard (460px). `live.js` loads
   `/api/owner/devices` (as owner.js does), then sets the iframe sources
   to `/t/{slug}?embed`, `/p/{slug}?embed` and `/owner?embed`. No server
   data goes into the markup. Below 1100px (matchMedia, also on resize)
   it goes to `/owner`.
2. The switch: in the dashboard's sandbox strip, after "Continue on
   another device", a checkbox with `role="switch"` labelled "Side by
   side" (styles in the preview's split-proposed.css). Shown only for a
   sandbox and only at min-width 1100px. Turning it on goes to
   `/owner/live`. Inside the live page (dashboard loaded with `?embed`
   in a frame) it shows checked, and turning it off sends the top window
   to `/owner`.
3. `?embed` is cosmetic only and changes nothing on the server:
   - Tip page: the success screen shows "Tip again" (reloads the tip
     page) instead of "Watch the panda react", and the sample dashboard
     foot is hidden.
   - Panda page: the hint reads "Send a tip on the left and watch this
     one react."
   - Dashboard: "Tip your panda" and "Watch it react" are hidden.
4. Navigation out of the dashboard frame must leave the whole view, not
   just the frame: sign out (form `target="_top"` when embedded),
   `toLogin` and the sandbox expired redirect use `window.top` when
   embedded. Check the resume sheet dialog still works inside the frame.
5. Framing: only `/t/**`, `/p/**` and `/owner` may be framed, and only by
   our own origin: CSP `frame-ancestors 'self'` and
   `X-Frame-Options: SAMEORIGIN` on those responses. Every other page,
   `/owner/live` included, keeps today's headers (not frameable). Keep
   this a per path rule in SecurityDefaults, not a global change.
6. PayPal inside the frame: prove first that the PayPal buttons and card
   guest checkout work in the framed tip page in the sandbox (no
   `sandbox` attribute on the iframes). If PayPal refuses, the tip
   column instead shows a short card with a button that opens the tip
   page in a popup window (`window.open`, about 420 x 760), and the other
   two columns stay as they are. Report which one you shipped and why.
7. Strict nonce CSP, textContent only, no inline handlers. New static
   files get new names; nothing long cached changes.

## Tests
- `/owner/live`: 200 for a sandbox; the 404 of an unknown path for a real
  owner and for the public demo; signed out like `/owner`.
- Framing headers: `frame-ancestors 'self'` and SAMEORIGIN on `/t/{slug}`,
  `/p/{slug}` and `/owner`; unchanged on `/owner/live`, `/judges`,
  `/owner/login`, the 404 page and the API.
- The live page's markup contains no slug or other owner data.
- Existing page tests still pass; PandaArtTest covers any page that draws
  the panda.

## Rules
Work on develop, commit locally, never push. Every commit builds on its
own (`./gradlew clean build`, Docker Desktop running). Update CODEBASE.md
(endpoints, security chains, dashboard, tip and panda pages). Put two or
three screenshots in briefs/assets/screenshots/07d/. End with the
CLAUDE.md report format.
