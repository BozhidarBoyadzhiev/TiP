# Brief 08: one panda look on every page

Run this after 07b is merged into develop, in a fresh agent session.

## Goal
Every drawing of the mascot becomes the same giant panda: black ears, white
angular cardboard head, black eye patches, black paws. Today the pages use a
brown cardboard bear. This brief replaces the art only. No behaviour, layout,
timing, colour token or API changes.

## Assets (approved by the owner)
All in briefs/assets/08-panda/:
- panda-look-preview.html: every variant on one page. Open it first.
- panda-sprite.svg: the shared symbols. Same names and boxes as the bear:
  head (132 by 74, bottom edge sits behind the card), eyes-open, eyes-shut,
  paws (132 by 22), plus two new ones: paws-eyes (paws over the eyes, for the
  hidden messages panel) and face (64 by 64, for icons).
- panda-sitting.svg: the full sitting panda for the panda page.
- favicon.svg, icon-maskable.svg, icon-32.png, icon-180.png, icon-192.png,
  icon-512.png, og-image.png (1200 by 630).

## What to change
1. Find every instance first and list them in the report. Search the static
   resources for: the symbol ids head, eyes-open, eyes-shut, paws; classes
   peek, paws, eyes, pupil, lid, flag-head, resting; the word bear in markup,
   CSS, JS, aria labels, alt text, tests and docs; every icon and the link
   preview image. Pages include at least: tip page and its success screen,
   panda page, owner login, dashboard (notes, hidden messages, resting),
   sample dashboard, judge code page, sandbox strip and resume sheet, 404.
2. Replace each page's symbol definitions with the ones in panda-sprite.svg,
   exactly. Keep inline definitions per page as today; do not switch to an
   external sprite reference.
3. Inline eye markup that does not use the symbols (the judge code page
   pupils that follow the cursor, the blinking lids): redraw it to match
   eyes-open. Pupils are ellipses rx 5.5 ry 7 at (49,50) and (83,50) with a
   white highlight r 2.2 at (+2.4,-3.2). Lids are rect x 42 and 76, y 41,
   width 14, height 18, rx 6, fill #242326 (patch colour, no longer head
   colour). Keep ids, classes and the existing animations.
4. The hidden messages panel: replace the inline bear paws over the eyes
   with use href="#paws-eyes".
5. Panda page: replace the bear drawing with panda-sitting.svg. Keep the
   page's existing ids for the coin glow, light and sparkle, and its
   existing reaction script, colours and timings. If the page's idle
   animation uses different class names from the new drawing (upper, eye,
   head, sheen), keep whichever the page's CSS and tests expect and map the
   new groups onto them. Do not take the demo buttons, the Google Fonts
   link or the reaction colours from the owner's original preview file.
   aria-label: "Cardboard giant panda sitting and holding a gold TiP coin".
6. Icons and link preview: overwrite the existing files with the new ones,
   keeping the existing file names, paths and served sizes. If a size the
   pages reference has no file here, render it from icon-maskable.svg (or
   favicon.svg for a transparent one) and say so in the report.
7. Words: bear becomes panda in alt text, aria labels, comments, test names
   and CODEBASE.md. Leave history alone.

## Rules
- Strict nonce CSP stays as it is. No inline style attributes, no new
  origins, textContent only. Fredoka from the self hosted files.
- The art must stay identical across pages. Add a test that reads
  panda-sprite.svg from briefs/assets/08-panda/ (or a copy under test
  resources if the build can't see briefs/) and checks that every page's
  inline symbol definitions match it after whitespace normalisation. A page
  that drifts fails the build.
- Existing tests keep passing. Rename bear in test names; do not weaken
  any assertion.
- Every commit builds on its own. Commit locally on develop, never push.

## Checks
- ./gradlew clean build green, with Docker running.
- Screenshots at 1280 and 390 wide into briefs/assets/screenshots/08/: tip
  page, panda page playing one reaction, dashboard with notes and hidden
  messages, judge code page, 404. Compare each against
  panda-look-preview.html and say in the report where they differ.
- grep for bear across src/ returns nothing.

End with the CLAUDE.md report format.
