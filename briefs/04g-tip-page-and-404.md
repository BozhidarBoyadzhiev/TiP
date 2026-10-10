# Brief 04g: Tip page, success screen and 404 page

Work on `develop`. Commit locally, never push. End with the report format in `CLAUDE.md`, including "Teammate activity". This brief can run unattended: when something is unclear, decide, record the decision in the report, and carry on.

## Why

The tip page at `/t/{slug}` gets the same look as the new virtual panda, and the app gets a real 404 page. The approved designs are standalone previews:

- `briefs/assets/tip-preview.html`
- `briefs/assets/404-preview.html`
- `briefs/assets/panda-preview.html` (already built in 04f, for reference)

The previews load Fredoka from Google Fonts, use hardcoded amounts, have a "presets" bar and grey stand ins for the PayPal buttons. None of that ships.

## Scope rules

- Front end, plus the server code the 404 page and caching need.
- Nothing in the `dashboard` package. No migrations. The owner login page is out of scope.
- **The payment flow stays exactly as it is**: `createOrder` posts to `/api/public/devices/{slug}/tips`, `onApprove` captures, and the error handling for `INSTRUMENT_DECLINED`, `TIP_CLOSED` and rate limits keeps its behaviour. Only how the page looks and how the amount is picked change.

## 1. Shared styles

Create `static/css/brand.css` with what the pages share: the colour tokens, the Fredoka font stack (importing or linking `fonts/fonts.css`), the TiP brand with its unfold (the 04f version: measured widths, `flex-shrink: 0`, margins for the word gaps, `@media (hover: hover)` for hover), the paper note, and the peeking panda with its paws.

Move the panda page onto `brand.css` with **no visual change**. Take screenshots of `/p/demo-panda` at 360 and 1280 px before and after and compare them. Put the brand's JS (letters, measuring, tap toggle) in one shared `static/js/brand.js` used by both pages.

## 2. Tip page

Rebuild `tip.html`, `tip.css` and `tip.js` from `tip-preview.html`: the centred card, the brand, the amount presets, the own amount stepper, the kraft paper note with the peeking panda, the "You're tipping" line, and the PayPal buttons.

### Data comes from the device config

Everything the preview hardcodes comes from `/api/public/devices/{slug}`: presets, minimum, maximum, currency, display name.

- Format money with `Intl.NumberFormat` and the device's currency. Presets that are whole amounts show without decimals (`£5`), others with two (`£2.50`). The hint shows the real bounds.
- The device name line ("Tipping …") follows the same rule as the panda page: hidden when the name is "TiP" or "Tip integrated Panda", ignoring case and spaces, and always set with `textContent`.

### Presets layout

Rows of three. The last row stretches to fill the width: 4 presets give three plus one full width, 5 give three plus two halves, 2 give two halves, 1 gives one full width. The preview's CSS (`flex: 1 1 calc((100% - 20px) / 3)`) does this. Check 1 to 6 presets.

### Own amount

- Whole unit steps with − and +. From a preset, + goes to the next whole amount above it and − to the one below. Clamped to the bounds, and the button at a bound is disabled.
- Typing is allowed (`inputmode="decimal"`). Accept `7`, `7.5`, `7.50` and a comma as the decimal mark. Reject more than two decimals and anything else.
- **Convert to minor units with integer arithmetic on the string**, never with floating point (`parseFloat("4.35") * 100` is not 435). Add a small unit test for the parser if there is a place for JS tests; if there isn't, test it through the browser check and say so.
- Focusing it starts from the current preset's value. Leaving it empty returns to the last preset.
- Active, it has the gold ring and the "✓ Your amount" chip, and no preset is pressed. Invalid, it turns the warning colour and the hint says why.

### Paying

- The "You're tipping" line always shows the amount that will be sent, or "Pick an amount to tip" when there is no valid one.
- When there is no valid amount, the PayPal buttons must really be disabled, not just greyed: they live in PayPal's iframe, so CSS can't stop a click. Use the SDK's `onInit` actions (`actions.disable()` and `actions.enable()`), and also check in `onClick` and return `actions.reject()` if the amount is invalid at that moment.
- `createOrder` sends the amount from the current selection. The server still validates everything; the page is only a convenience.
- PayPal button style: vertical layout, gold, matching the card's corner radius as closely as the SDK allows. The "Powered by PayPal" line is PayPal's and stays where PayPal puts it.

### The note

- `maxlength="280"` stays, matching `@Size(max = 280)`. The counter is bold and turns red above 250.
- The panda wakes, blushes and follows the caret as in the preview. It only reacts to focus and typing; nothing about the message is sent anywhere before payment.

### Success and errors

- After a successful capture, show a success state in the same card: the panda happy over the paper note, "Thank you!", the amount, and a gold button "Watch the panda react" linking to `/p/{slug}`. Keep the existing behaviour for a pending capture, styled the same way.
- Errors (declined card, tip closed, rate limited, network) show as a small paper note in the card with a friendly sentence and a way to try again. Never show raw server text.

## 3. The 404 page

Build `static/404.html` and its CSS from `404-preview.html`, using `brand.css`. Serve it with the request's CSP nonce like the other pages. The page is fully static: it must never echo the requested path, slug or any query value.

When it is used:

- `GET /t/{slug}` and `GET /p/{slug}` with an unknown slug return **status 404** with this page. Check the slug on the server through `DeviceDirectory` before serving the page, so the page never loads for a slug that doesn't exist.
- Any other `GET` from a browser (`Accept` prefers `text/html`) that would end in 404, or that the fallback security chain denies as an unknown path, returns 404 with this page. Answering 404 instead of 403 for an unknown path tells a visitor nothing more than 403 does.
- Everything else keeps its current behaviour: `/api/**`, `/webhooks/**`, `/ws/**` and `/actuator/**` always answer problem details JSON, non `GET` requests to unknown paths keep their 403 problem details, and the owner chain is untouched.

## 4. Caching

- Font files: `Cache-Control: public, max-age=2592000` (30 days). They never change.
- CSS and JS: leave as they are (revalidated with `Last-Modified`), since they change between deploys and their names aren't versioned.

## 5. Tests

- Tip page: the page and its assets have no outside origin except PayPal's SDK, which the CSP already allows; no inline `style` attributes; the brand button and its label exist; the CSP has no `'unsafe-inline'`.
- 404: unknown slug on `/t/` and `/p/` gives 404 HTML with a nonce in its CSP; a known slug still gives 200; `GET /nope` with `Accept: text/html` gives 404 HTML; `GET /nope` with `Accept: application/json` and `POST /nope` keep their current problem details; `/api/public/devices/nope` stays problem details 404. A request with a script tag in the path and the query never appears in the 404 body.
- A font response has the 30 day `Cache-Control`.
- Existing tests stay green. `./gradlew build` passes, Spotless clean.

## 6. Check it in a real browser

Run the app locally with the PayPal sandbox and do a real sandbox payment end to end on the new page. Check at 360 px and 1280 px:

- presets with 1 to 6 amounts (change the demo device's presets temporarily, and put them back),
- stepper, typing valid and invalid amounts, leaving it empty, and that the PayPal buttons are disabled while the amount is invalid,
- the note, the counter colour at 251 characters,
- success screen and its link to the panda page,
- a declined payment (the sandbox has test cards for that), the 404 page for an unknown slug and an unknown path,
- the panda page looks the same as before the `brand.css` move,
- no horizontal scroll at 360 px.

Say in the report how you checked each item.

## Out of scope

Owner login and dashboard styling, firmware.

## Report

Use the `CLAUDE.md` format. Include how the amount parser avoids floating point, how the PayPal buttons are disabled, which requests now get the 404 page and which don't, the panda page before and after comparison, and every decision you made on your own.