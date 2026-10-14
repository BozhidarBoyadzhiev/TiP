# Brief 07c: resume link survives link previews

## Why
The resume link from the sandbox resume sheet works from the QR but fails
when shared through Discord: the page says the link was already used or
has run out. Cause: chat apps (Discord, Slack, WhatsApp, Teams) and mail
scanners (Outlook Safe Links) fetch a pasted URL to build a preview. The
resume endpoint spends the single use token on GET, so the preview bot
spends it before the person taps. Spring also routes HEAD to GET mappings,
so HEAD spends it too.

## What to build
1. GET of the resume URL must be safe. It serves a static page and
   changes no state, whatever its path or query. HEAD likewise.
2. The token moves from the query string to the URL fragment:
   `<TIP_PUBLIC_BASE_URL>/resume#t=<token>` (keep the existing path if it
   differs; match what the resume sheet uses today). Fragments are never
   sent to the server, so crawlers, Render and Cloudflare logs, and the
   Referer header never see the token. The resume sheet link and the
   server made QR both use the new form.
3. The resume page (TiP design, same card and panda peek as /judges)
   shows "Continue to your sandbox" with one button. On load the script
   reads `t` from the fragment and removes it from the address bar with
   `history.replaceState`. On tap it POSTs the token in the JSON body to
   the existing (or a new) resume API endpoint. Do not POST on load:
   some scanners run JS.
4. The POST spends the token exactly as GET does today (same validity
   rules, same single use, same session setup), then the page navigates
   to the sandbox dashboard. On any failure the page shows the existing
   "already used or has run out" message and the code entry from /judges
   (link to /judges is fine). Failure responses stay identical for
   unknown, used and expired tokens.
5. If the page loads with no token in the fragment, show the same failure
   state. Ignore any token in the query string (no fallback).
6. The POST is rate limited like the other auth endpoints (reuse the
   existing Bucket4j setup). Strict nonce CSP, textContent only, no
   inline handlers, as everywhere else.
7. Link preview metadata on the resume page stays generic (the same as
   /judges): nothing sandbox specific.

## Tests
- GET and HEAD of the resume URL, with and without a token in the query,
  return the page and leave the token unspent (a POST afterwards works).
- POST with a valid token creates the session once; a second POST with
  the same token fails with the identical failure response.
- Expired and unknown tokens fail identically.
- The resume sheet link and the QR payload contain `#t=` and no token in
  the query string.
- The rate limit applies to the POST.
- Existing 07b tests updated, not deleted, where they assumed GET spends
  the token.

## Rules
Work on develop, commit locally, never push. Every commit builds on its
own (`./gradlew clean build`, Docker Desktop running). Update CODEBASE.md
in the same work where it describes the resume flow. Static assets that
change get new file names. End with the CLAUDE.md report format.