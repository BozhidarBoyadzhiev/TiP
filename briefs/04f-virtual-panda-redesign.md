# Brief 04f: Virtual panda redesign

Work on `develop`. Commit locally, never push. End with the report format in `CLAUDE.md`, including "Teammate activity". This brief can run unattended: when something is unclear, decide, record the decision in the report, and carry on.

## Why

The virtual panda at `/p/{slug}` gets a new look that matches the physical cardboard panda: a seated cardboard bear holding a gold TiP coin, with the reaction light on the coin only. The approved design is in `briefs/assets/panda-preview.html`. It is a standalone preview: it loads Fredoka from Google Fonts and has four demo buttons. Neither of those ships.

## Scope rules

- Front end only: `static/panda.html`, `panda.css`, `panda.js`, a new shared font folder, and whatever server code the CSP needs.
- Nothing in the `dashboard` package. No migrations.
- **Do not change the protocol logic in `panda.js`**: observer connection, backoff, queue of 4, pattern timings, `base + (repeats - 1)` units, gaps, captions, connection status. Only the rendering target changes.

## 1. The bear

Copy the SVG from the preview into `panda.html` as is: geometry, gradients, grain filter, shadows, idle animations (breathing, blink, head tilt, coin sheen) and the `prefers-reduced-motion` rules. Move all CSS into `panda.css`.

Keep the page strict under the existing CSP:

- No inline `style` attributes and no `<style>` blocks without the nonce. The preview already sets the light colour through CSS rules on `#panda-coin-glow` and `#panda-coin-light`; keep it that way.
- Setting CSS custom properties from JS (`el.style.setProperty`) is CSSOM and allowed. Use that for `--light` and for the letter delays.

## 2. The light

The reaction now lights the coin only. There is no glow behind the bear.

- `panda.js` currently computes a light level per animation frame for the current pattern. Feed that level to three elements: `#panda-coin-glow` at full level, `#panda-coin-light` at 0.35 of the level, `#panda-coin-sparkle` at full level. Set `--light` on the SVG to the reaction's colour (`WARM` pink `#ff7fb0`, `PLAYFUL` yellow `#ffd54a`, `REQUEST` blue `#6fb6ff`, `PLAIN` soft white `#fff4e0`).
- With `prefers-reduced-motion`, keep today's behaviour: a steady light for the scaled pattern's length, on the coin elements, and no idle animations.
- Remove whatever element the old design lit.

## 3. The brand

Replace the page title with the TiP brand from the preview: a `<button>` inside the `<h1>`, `T` gold, `i` pink, `P` cream. On hover, keyboard focus, or tap (`aria-expanded` toggles), it unfolds into "Tip integrated Panda" with the staggered letter animation. `aria-label` stays "TiP, Tip integrated Panda". Reduced motion turns the transitions off. `<title>` becomes `TiP | Tip integrated Panda`.

The page currently shows the device's display name from `/api/public/devices/{slug}`. Keep it, as a small line under the bear, above the caption. Hide it when the name equals "TiP" or "Tip integrated Panda", ignoring case and surrounding spaces, so the demo panda doesn't say its name twice. Use `textContent`, never `innerHTML`: the display name comes from the database.

## 4. Self hosted font

No third party requests from our pages. Host Fredoka ourselves:

- Get the woff2 files for weights 400, 500, 600 and 700, Latin and Latin Extended subsets (for example from the `@fontsource/fredoka` npm package). Put them in `static/fonts/fredoka/` with the font's `OFL.txt`.
- One shared `static/fonts/fonts.css` with the `@font-face` rules (`font-display: swap`, `unicode-range` per subset), so the tip page can use the same file later.
- Check the CSP in `SecurityDefaults`. If `font-src` isn't covered by a `'self'` default, add `font-src 'self'`. Don't add any outside origin.
- Make sure `/fonts/**` is a public path in `SecurityConfig`, and that the files are served with a long cache lifetime if static resources already have one. If they don't, leave caching as it is and mention it in the report.

## 5. Tests

- Update `PandaPageTest` for the new markup: the coin light elements exist, the brand button and its label exist, the four reactions and captions still work.
- A test that `panda.html` and `fonts.css` reference no outside origin (no `http://` or `https://` URLs except the `xmlns` of the SVG).
- A test that a font file is served with `font/woff2` and passes the CSP, and that the page's CSP still has no `'unsafe-inline'`.
- `./gradlew build` passes, Spotless clean.

## 6. Check it in a real browser

Run the app locally, open `/p/demo-panda`, and with the fake device or a sandbox tip confirm: each kind lights the coin in its colour, repeats play the scaled length, the queue plays reactions in order, and reduced motion holds a steady coin light. Also confirm the brand unfolds on hover and on tap in a phone sized window, and that the page has no horizontal scroll at 360 px wide. This also closes the old open item "virtual panda scaled animation and queue: confirm once in a real browser". Say in the report how you checked each one.

## Out of scope

The tip page (next brief), the dashboard, firmware.

## Report

Use the `CLAUDE.md` format. Include the CSP change if any, where the font files came from with their licence, how `panda.js` maps the light level to the coin elements, and every decision you made on your own.