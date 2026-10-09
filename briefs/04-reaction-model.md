# Brief 04: New reaction model

## Overview

The product is changing what the panda reacts to. "Positive versus negative" was the weak part of the idea: almost nobody pays to insult someone, and the few who do are trolls, for whom a dramatic red strobe is a reward. The panda now reacts to the **kind** of message, and abuse gets no special reaction at all.

| Kind | Meaning | Example |
|---|---|---|
| `WARM` | Appreciation, kindness, being moved | "You made my day" |
| `PLAYFUL` | Banter, jokes, teasing, light hearted challenges | "Bet you can't play Wonderwall" |
| `REQUEST` | Asks for something, or gives feedback the owner can act on | "Play some Oasis", "a bit too loud by the café" |
| `PLAIN` | No message, a plain thanks, or anything else | "thanks" |

Rules:

- A flagged message (abusive, hateful, sexual, threatening, or containing personal data) always plays `PLAIN`, whatever its kind. The payment is acknowledged and the troll gets nothing to film. The message stays hidden from public views and the owner can refund, as before.
- For `REQUEST` messages that are not flagged, the AI also writes a short neutral **owner note** (max 100 characters), for example "Request: something by Oasis" or "Feedback: too loud near the café". The teammate's dashboard will list these.
- Negative is gone as a reaction. Rude but not abusive messages ("your music is boring") are `PLAIN`, or `PLAYFUL` if clearly banter.

This brief also writes one pending protocol decision into the contract: **the device acks every reaction it receives, including repeats it does not play again.**

The lead authorizes changes to these shared contracts in this brief: the `TipReacted` event, the `reaction` values in the database, `docs/DEVICE_PROTOCOL.md`, and the project description in every doc.

Start a fresh session. Read `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/DEVICE_PROTOCOL.md` and `CODEBASE.md` first. Small logical commits, each green.

## Part 1: Schema (`V4__reaction_model.sql`)

- `tip`: add `message_kind` varchar(10) null with a check on `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`, and `owner_note` varchar(100) null.
- `tip.reaction` and `reaction_delivery.reaction`: new check constraint with `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`. Migrate existing rows first: `POSITIVE` becomes `WARM`, `NEUTRAL` and `NEGATIVE` become `PLAIN`. Set `message_kind` to the same value for existing classified tips.
- Migration test covering the data mapping on a database at V3 with rows in all three old values.

## Part 2: AI

- Result schema becomes:
  ```json
  { "kind": "WARM | PLAYFUL | REQUEST | PLAIN", "flagged": false, "flagReason": "string or null", "ownerNote": "string or null" }
  ```
- Rewrite the system prompt around the table above. Keep it plain and specific, with one short example per kind and the flag rules. Spell out: rude but not abusive is `PLAIN`; obvious banter is `PLAYFUL`; feedback the owner could act on counts as `REQUEST`; `ownerNote` only for `REQUEST`, neutral wording, no quotes from the message beyond what is needed, never personal data. The message stays wrapped in `<tip_message>` tags as untrusted data.
- Java validation: `ownerNote` must be null unless `kind` is `REQUEST`, max 100 characters after trimming, control characters removed. An invalid result counts as a failed attempt, as today.
- Mapping when storing: `message_kind` = model kind. `reaction` = `PLAIN` if flagged, else the kind. `owner_note` = null if flagged, else the model's note.
- Empty message and `TIP_AI_ENABLED=false`: `message_kind` null, `reaction` `PLAIN`, `ai_status` `SKIPPED`. After three failures: `reaction` `PLAIN`, `ai_status` `FAILED`.
- `TipReacted` carries the new reaction values. Nothing else changes in the claim flow.

## Part 3: Device protocol

Update `docs/DEVICE_PROTOCOL.md`:

- `reaction` values are `WARM`, `PLAYFUL`, `REQUEST`, `PLAIN`, on both channels.
- Delivery rules gain: "The device acks every reaction it receives, including repeats it does not play again. A lost ack otherwise leads to three needless resends."
- Replace the LED pattern table:

| Reaction | Pattern | Total length |
|---|---|---|
| `WARM` | Heartbeat. Fade 0 to 255 over 400 ms, 255 to 0 over 600 ms, pause 200 ms. Repeat 3 times. | 3.6 s |
| `PLAYFUL` | Giggle. Six quick flicks, each rising over 60 ms and falling over 90 ms with a 70 ms gap, peaks 255, 140, 230, 110, 255, 170. Pause 250 ms, then the six flicks again. | about 2.9 s |
| `REQUEST` | Noted. Fade 0 to 255 over 300 ms, hold 500 ms, fade to 0 over 300 ms, pause 300 ms. Repeat once. | 2.5 s |
| `PLAIN` | One solid blink. Full on 700 ms, then off. | 0.7 s |

The status patterns stay as they are. Make sure the three long patterns stay distinguishable on a single colour LED: smooth and slow, quick and jittery, long flat holds.

## Part 4: Server side delivery

`reaction_delivery`, the dispatcher, the device channel and the observer channel accept and send the new values. Observers still get only `type`, `reaction` and `at`. A flagged tip's observer message is indistinguishable from any other `PLAIN`.

## Part 5: Virtual panda and fake device

- Virtual panda plays the four new patterns with the exact timings. Colours: `WARM` soft pink, `PLAYFUL` warm yellow, `REQUEST` calm blue, `PLAIN` soft white. Captions: "Warm message", "Playful message", "Request noted", "Tip received". Reduced motion keeps a steady light in the reaction's colour for the pattern's length.
- Fake device prints the new patterns and acks repeats.

## Part 6: Tip page

Message box label "Leave a message (optional)" and placeholder "Say something nice, make a request, or just tip." Keep the 280 limit and counter.

## Part 7: Docs

Update every place that describes the idea, so nothing still talks about positive and negative:

- `CLAUDE.md` intro paragraph.
- `docs/ARCHITECTURE.md`: "What it is" (include the "don't feed the trolls" reasoning in one or two sentences), the main flow, the threat model row on abusive messages (abuse gets the plain blink, no reward), data model columns.
- `docs/DEVICE_PROTOCOL.md` as in Part 3.
- `docs/TEAMMATE_START.md`: the project paragraph, and mention that the dashboard will list owner notes (requests and feedback) in addition to tips, summaries and flagged messages.
- `README.md` and `CODEBASE.md`.

## Tests (required)

1. Migration: old values map correctly; new check constraints reject `POSITIVE` and `NEGATIVE`.
2. AI: each of the four kinds stored with the right `reaction`; `REQUEST` stores the note; other kinds store no note.
3. Flagged `PLAYFUL` or `REQUEST` message: `reaction` `PLAIN`, `message_kind` kept, `owner_note` null, `flagged` true.
4. Invalid results: note on a non `REQUEST` kind, note over 100 characters, unknown kind. Each counts as a failed attempt.
5. Empty message, AI disabled and three failures all give `PLAIN`.
6. Prompt shape: request body contains the four kinds and the `<tip_message>` wrapper.
7. Device and observer channels deliver each new value; a flagged tip reaches observers as plain `PLAIN`.
8. Fake device acks a repeated tip id and does not play it again.
9. Virtual panda page and tip page tests updated for captions and copy.

## Do not

- No firmware. That is Brief 05.
- No dashboard work. The teammate builds the owner note list on top of `owner_note`.
- Do not push.

## Human actions to list

At least: restart the app so V4 runs; send four test tips (warm, playful, a request, something abusive) and check `message_kind`, `reaction`, `flagged` and `owner_note` in the database; watch the virtual panda and fake device play each pattern.

## Report

Format in `CLAUDE.md`.