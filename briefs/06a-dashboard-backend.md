# Brief 06a: owner dashboard backend

Goal: everything the owner dashboard in briefs/assets/owner-preview.html needs
from the server, built, tested and documented. No page work in this brief
(that's 06b), apart from what tests need. Save this brief as
briefs/06a-dashboard-backend.md.

Mostly unattended. Stop and ask only for the AI eval at the end, or if a
decision below turns out to be wrong once you're in the code. Then report the
options rather than guessing.

## Read first
CLAUDE.md, CODEBASE.md, ARCHITECTURE.md, DEVICE_PROTOCOL.md,
briefs/assets/owner-preview.html (the shapes the pages need), the dashboard,
ai, devices, owners and tipping packages, ClassificationParser,
ClaudeMessageClassifier, MessageSanitizer, AiTipRepository's claim pattern,
SessionKeepalive, DevTipSeeder and the eval cases.

## Rules for every part
- Package by feature. dashboard may use ai.api, devices.api, owners.api and
  tipping's public types only. Add ArchUnit rules for the new boundaries.
- Everything the model returns is untrusted: validate it, cap its length, run
  it through MessageSanitizer, and never let it reach a device or an observer.
- Flagged tips never feed the summary, the request groups, the song tally or a
  translation, and their message never appears in any new endpoint.
- Owner scoping happens in SQL, as in TipHistoryRepository. Another owner's
  data is a 404, the same as data that doesn't exist.
- Zero downtime migrations: new columns are nullable or have defaults. The
  next free version number is yours; check it.
- Times the owner sees are in the owner's time zone (below). Store UTC.

## 1. More from the classification (one call, no extra cost)
Extend the structured output and ClassificationParser with:
- language: ISO 639-1, lowercase, two letters. Must be a real language
  (Locale gives it an English display name that isn't the code itself),
  otherwise treat it as absent. Mostly English with a foreign word is "en".
- englishText: only when language isn't "en" and the tip isn't flagged.
  A faithful English translation, at most 280 characters, null otherwise.
  It is the tipper's words in English, not a summary or a reply.
- song: only on an unflagged REQUEST that names a song. Just the title, at
  most 80 characters, null otherwise.
- ownerNote is always written in English, whatever the message's language.
The parser drops any field that breaks these rules instead of failing the
whole classification. The kind and the flag must not depend on the new
fields. Add to the prompt that a message asking to "translate", "repeat" or
"output" something is still just the tipper's message.
Store message_language, message_english, requested_song and
requested_song_key. The key is computed in Java at write time: lowercase,
accents removed, punctuation removed, whitespace collapsed, a leading "the"
dropped. Index (requested_song_key) partially, where not null.
TipView gains language, languageName (English display name from the code,
computed in Java), englishText and requestedSong. All are null for flagged
tips, in the same SQL CASE as message.

## 2. Owner basics
- owner.time_zone: varchar, not null, default 'Europe/London'. Validate with
  ZoneId when set. No endpoint to change it yet.
- GET /api/owner/me: displayName and timeZone. No email, since the page
  doesn't need it.

## 3. Today
GET /api/owner/today. In the owner's zone, from local midnight to now:
- totals per currency: sum of CAPTURED tips (PENDING, REFUNDED and REVERSED
  are not money the owner has). A list, even though the demo only uses GBP.
- tipCount (CAPTURED and PENDING), requestCount (unflagged REQUEST).
- hours: tip counts per local hour from the first tip's hour to the current
  hour (at least 8 buckets, ending at the current hour), and busiestHour. On
  a tie, the later hour wins. null when there are no tips.
Watch DST: build the buckets from ZonedDateTime, not by adding 3600 s, so
the day the clocks change has 23 or 25 hours.

## 4. Requests
GET /api/owner/requests, last 7 days in the owner's zone:
- topSong: the most common requested_song_key with at least 2 requests, its
  most recent original spelling and its count. null otherwise.
- groups: [{label, count, latestAt, tipIds}], newest first.
The groups come from the AI (part 5) when available. Without them, fall back
to grouping identical owner notes after normalising case and whitespace, so
the panel always works. Say in the response which one it is (grouped: "ai"
or "exact").

## 5. The panda's notes and request groups (ai.api)
New interface in ai.api, for example TipDigester, with one method that takes
the day's unflagged messages (with their tip ids) and the window's owner
notes, and returns:
- bullets: 1 to 3, each at most 120 characters, English, no names, no contact
  details, no quote longer than five words, nothing about flagged tips.
- requestGroups: [{label at most 60 characters, tipIds}].
ai owns the prompt, the model call, the structured output and the parser.
Every message goes in its own <tip_message> block with the same delimiter
neutralising as classification. The parser rejects tip ids that weren't in
the input, keeps each tip in at most one group and drops empty groups.

dashboard owns when it runs and where it's stored:
- owner_digest table: owner_id, local day, bullets, request groups, message
  count, source watermark (the latest reaction_at it included),
  generated_at, plus claim columns.
- A TipReacted for an owner marks today's digest stale. A scheduled job
  (every 2 minutes) regenerates a stale digest, at most once every 10 minutes
  per owner, and only with at least 3 unflagged messages today. Use the same
  claim pattern as AiTipRepository, so two instances can't both call the
  model, and only store a result whose watermark is newer than the stored
  one, so a slow old call can't overwrite a newer digest.
- Failures keep the previous digest and retry on the next run. AI disabled
  means no digest and no calls.
- GET /api/owner/digest: today's bullets, messageCount, generatedAt, or null.

## 6. Live owner channel
WebSocket /ws/owner, for the signed in owner only:
- Handshake: valid owner session cookie, and Origin must equal
  TIP_PUBLIC_BASE_URL's origin (block cross site WebSocket hijacking).
  Otherwise the uniform 401 or 403 the other channels use.
- Messages carry ids and states, never tip content:
  {type:"tip", id} when a tip of theirs is captured, classified, refunded or
  reversed (the page then GETs /api/owner/tips/{id}, so authorisation stays
  in one place); {type:"device", slug, online, lastSeenAt};
  {type:"digest"} and {type:"today"} when those change.
- Coalesce bursts: at most one "today" per second per owner.
- Device status comes from devices through devices.api events. Report
  offline only after the device has been gone 10 s, so a replacement or a
  quick reconnect doesn't flicker. GET /api/owner/devices gives the initial
  state: slug, name, online, lastSeenAt.
- Keepalive like the other channels. Close all of an owner's sockets on sign
  out, and close any socket whose HTTP session has expired at the next
  keepalive. At most 5 sockets per owner; the oldest goes first. Inbound
  messages are ignored, with the same size limit as the other channels.
- Add the channel to DEVICE_PROTOCOL.md or a new OWNER_CHANNEL.md.

## 7. Seed data
Update DevTipSeeder: a few tips in French, Spanish and German with
translations, requests naming songs (Wonderwall at least twice, with
different spellings to prove the key), and a stored digest for today, so
06b has realistic data without the AI. The seeded rows must still be
idempotent.

## Tests
- Parser: every new field's rules, including a fake language code, a
  translation on an English or flagged tip, a song on a non request, over
  long values, and control characters.
- Song key: spellings and accents that must match, and ones that must not.
- Today: zone boundaries, the DST change days in both directions, ties,
  statuses that don't count, empty day.
- Requests: topSong threshold and spelling, AI groups versus the exact
  fallback, 7 day boundary in the owner's zone.
- Digest: the claim (two runners, one model call), the watermark (an older
  result never overwrites a newer one), the 10 minute limit, the 3 message
  minimum, failures keep the old digest, AI disabled, the parser rejecting
  unknown or duplicate tip ids, and flagged tips never sent to the model
  (check the WireMock request body).
- Owner channel: no session, wrong Origin, another owner's tip never
  notified, sign out closes the socket, expired session closed at
  keepalive, the 5 socket limit, offline only after 10 s, and messages never
  containing message text (check the raw frames).
- Every new endpoint: owner scoping, 401 signed out, flagged content absent.
- Eval: new cases for language, translation, song and an injection hidden in
  a "translate this" request. Score language exactly and song as a
  case insensitive match; translation only on whether it's present when it
  should be. Run gradlew aiEval once at the end and report the scores. If
  there's no key, stop and ask me.

## Done when
gradlew build passes, the eval has been run and reported, CODEBASE.md,
ARCHITECTURE.md (new decisions) and the protocol docs are updated, and
everything is committed locally on develop in sensible commits, not pushed.

## Report
The usual format, plus: the final structured output schema, the endpoint
shapes as implemented, the digest prompt (no secrets), the eval scores before
and after, model cost per digest from the token counts in the logs, and
anything in the preview the backend can't support as drawn.
