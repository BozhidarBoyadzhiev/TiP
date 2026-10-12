# Brief 06c: reveal and overrule hidden messages

Read CLAUDE.md, CODEBASE.md, ARCHITECTURE.md and DEVICE_PROTOCOL.md first.
Save this brief as briefs/06c-reveal-and-overrule.md.

## Goal
The owner can read a hidden message on demand and overrule the AI ("Not
abusive"), which turns it into a normal tip. "Hide again" undoes it. The
standing decision changes from "flagged messages are hidden from the owner" to
"flagged messages are hidden by default; the owner can reveal and overrule".
Public pages, observers and devices are unaffected.

## Backend
1. Store the model's full read for flagged tips. At classification, keep kind,
   owner note, language, englishText and song for flagged messages too, from
   the same single call. If validation or the write path drops them when
   flagged, stop dropping them at write time; gate exposure at read time.
   Model output stays untrusted: same validation, caps and sanitizing.
   The device reaction for a flagged message stays PLAIN x1 and is never
   replayed or changed. Don't change kind definitions or tune to the eval;
   the eval must still pass, flagged cases' kind is not scored.
   Legacy flagged tips with no stored read: on overrule they show as Plain
   with no note, translation or song. No new AI call anywhere in this brief.

2. Schema (Flyway V7): owner_verdict (NULL or NOT_ABUSIVE) and
   owner_verdict_at, on whichever table owns the flag. The AI's flag and
   reason are never modified. Effective hidden = ai flagged AND owner_verdict
   IS NULL. Define it in one place; every owner facing query (tips, filters,
   today, requests, digest input, hidden panel, counts) uses it. Add a test
   or ArchUnit rule that catches owner output reading the raw flag.

3. Reveal: GET /api/owner/tips/{id}/message returns {"text": ...} only for an
   AI flagged tip of the signed in owner; otherwise the identical 404.
   Cache-Control: no-store. While a tip is effectively hidden its text never
   appears in /api/owner/tips, /ws/owner or any other payload.

4. Verdict: PUT /api/owner/tips/{id}/verdict {"hidden": false|true}, CSRF
   header, idempotent. One conditional UPDATE scoped by owner and ai flagged;
   identical 404 if not the owner's or not AI flagged. Returns the updated tip
   item. Concurrent requests end in a consistent state.

5. Live: publish the existing ids only tip changed event on /ws/owner so
   other tabs refetch.

6. Digest and requests: an overrule makes the tip input for the next digest,
   so the watermark must notice verdict changes, not only new tips (cadence
   unchanged). "Hide again" must drop the current digest and request groups
   immediately, bypassing the 10 minute cadence, so the message can't linger
   in the notes. The next request regenerates under the normal rules (null
   below 3 messages). Overruled messages go through the digester's existing
   injection hardening like any other message.

7. Nothing new reaches devices or observers. Refunds unchanged.

## Front end (reuse existing components, no new preview)
- Hidden panel row: reason as now; "Show message" fetches and shows the text
  inline as text (not kept after reload); then "Not abusive" and the existing
  refund.
- Overruled tip: a normal row in the main list with its kind and a small
  "Unhidden by you" tag; its detail has "Hide again". The hidden count and
  Hidden filter follow the effective verdict.
- Reveal and verdict update in place with focus kept, like refund; errors use
  the refund error pattern.

## Tests
- Owner scoping and identical 404s for both endpoints.
- Hidden text absent from /api/owner/tips and /ws/owner payloads while hidden.
- Overrule then undo round trip across tips, today, requests and hidden panel.
- Undo drops the digest immediately (MutableClock, job triggered from test).
- Concurrent verdict PUTs.
- Legacy flagged tip overrule shows as Plain.
- Eval still passes; OwnerPageTest guards still hold.

## Docs
CODEBASE.md, ARCHITECTURE.md (the decision change), README.md.
Screenshots at 390 and 1280 in briefs/assets/screenshots/06c/: hidden row revealed, overruled
tip in the list and in its detail.
