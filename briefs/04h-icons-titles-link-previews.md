# Brief 04h: Tab icon, page titles and link previews

Work on `develop`. Commit locally, never push. End with the report format in `CLAUDE.md`, including "Teammate activity". This brief can run unattended: when something is unclear, decide, record the decision in the report, and carry on.

## Why

The pages have no tab icon, generic titles, and no preview card when a link is shared in Discord, Instagram, WhatsApp or iMessage. The finished assets are in `briefs/assets/`:

- `favicon.svg`, `favicon.ico` (16, 32 and 48 px), `apple-touch-icon.png` (180 px), `icon-512.png`: the gold TiP coin. The SVG has the letters as paths, so it needs no font.
- `og-image.png` (1200 × 630, the link preview card)

Use them as they are. Don't redraw them.

## Scope rules

- Static files, the HTML heads, the page controllers, and `SecurityConfig` for the new public paths.
- Nothing in the `dashboard` package. No migrations.

## 1. Files and paths

- `favicon.ico` at `/favicon.ico` (browsers ask for it there whatever the HTML says), the others under `/icons/`, the preview image at `/images/og-image.png`.
- All public `GET` paths in `SecurityConfig`.
- Cache: `public, max-age=2592000` like the woff2 fonts. If an asset ever changes, it gets a new file name.
- Today `/favicon.ico` falls into the 404 page or a 403. Make sure it now returns the icon with `image/x-icon`.

## 2. Every page's head

On the tip, panda, 404 and owner login pages:

```html
<link rel="icon" href="/favicon.ico" sizes="48x48">
<link rel="icon" href="/icons/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/icons/apple-touch-icon.png">
<meta name="theme-color" content="#120c09">
```

Titles are fixed, with the brand first:

| Page | Title |
|---|---|
| Tip page | `TiP \| Leave a tip` |
| Panda page | `TiP \| Panda` |
| 404 | `TiP \| Not found` |
| Owner login | `TiP \| Owner login` |

`panda.js` currently doesn't touch `document.title`; keep it that way, and make sure `tip.js` doesn't change it either.

## 3. Link preview tags

On the tip and panda pages only (not the 404 or login), set in the server rendered HTML, because crawlers don't run JavaScript:

- `og:type` `website`, `og:site_name` `TiP`, `og:title` the same as the page title, `og:description`:
  - tip page: `Tap the coin, leave a note, and watch the panda react.`
  - panda page: `A cardboard panda that lights up for every tip.`
- `og:url`: the page's canonical URL built from `TIP_PUBLIC_BASE_URL` and the slug, never from the request's `Host` header (a forged host would otherwise end up in the card).
- `og:image`: absolute URL from `TIP_PUBLIC_BASE_URL`, plus `og:image:width` 1200, `og:image:height` 630, `og:image:type` `image/png` and `og:image:alt` `A cardboard panda holding a gold TiP coin`.
- `twitter:card` `summary_large_image` (Discord and others read it).

### Nothing from the request or the database in the head

The titles and descriptions are fixed text, so no device name goes into the head. The only dynamic parts are `TIP_PUBLIC_BASE_URL` (configuration) and the slug, which has already passed the slug pattern and the device check. Build `og:url` only after that check.

## 4. Tests

- `/favicon.ico`, the icons and the preview image are served with the right content types and the 30 day cache, without a session.
- Each page has the icon links, `theme-color` and its title. The 404 and login pages have no `og:` tags.
- Tip and panda pages: the `og:` tags are present, `og:url` and `og:image` start with `TIP_PUBLIC_BASE_URL` even when the request carries a different `Host` header.
- A request with a forged `Host` header and a script tag in the query: neither appears anywhere in the page.
- `./gradlew build` passes, Spotless clean.

## 5. Check

- In a browser: the tab shows the panda icon on all four pages and the titles are right.
- After deploying is the real test, so write in the report the exact steps for the owner: paste the Render tip URL into Discord with `?v=1` at the end (Discord caches cards per URL), and run it through a preview checker such as opengraph.xyz.

## Out of scope

Dashboard, firmware, any change to page design.

## Report

Use the `CLAUDE.md` format. Include how `og:url` and `og:image` are built, and every decision you made on your own.