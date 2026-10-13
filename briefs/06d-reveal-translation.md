# Brief 06d: translation on reveal, notes ruling at any zoom

Read CLAUDE.md and CODEBASE.md first. Save this brief as
briefs/06d-reveal-translation.md. Small brief, one session.

1. Reveal with translation. GET /api/owner/tips/{id}/message currently returns
   only {"text"}. Also return language, languageName and englishText when
   stored (same fields and rules as tip items; englishText only for non
   English). Same owner scoping, identical 404, no-store. The revealed block
   in the hidden panel and in a hidden tip's detail gets the same
   "Show in English" toggle as normal rows, rendered as text. Nothing changes
   while the message is still hidden and unrevealed: no language or
   translation in /api/owner/tips or /ws/owner for hidden tips (keep the
   existing test that proves the text is absent and extend it to englishText).
   Tests: reveal of a flagged non English tip returns englishText; reveal of
   an English one has none; another owner's tip is the identical 404.

2. Notes card ruling. On the owner's own browser (Windows, Chrome, likely
   with page zoom not 100 percent) the ruled lines cut through the empty
   notes text in production. Make the ruling hold at 80, 90, 100, 110, 125
   and 150 percent zoom and before and after the web font loads: one
   line-height and ruling value in the same unit, every text element inside
   the card (empty text, rewriting text, bullets, signature) uses it, no
   element with its own margins that shifts the rhythm. Check all three card
   states (notes, empty, rewriting) at those zoom levels at 390 and 1280 and
   put the screenshots in briefs/assets/screenshots/06d/.

3. Fix the deprecated API use in ModerationDigestTest that the compiler warns
   about.

Update CODEBASE.md and OWNER_CHANNEL.md or ARCHITECTURE.md if they describe
the reveal response.
