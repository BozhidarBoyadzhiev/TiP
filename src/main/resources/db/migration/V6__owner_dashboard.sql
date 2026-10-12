-- Brief 06a. Every change adds a nullable column, a column with a default or a new table, so the previous
-- release keeps working while this migration runs.

-- What the classification also read from the message, written by ai. message_english and requested_song are
-- model output, sanitised and capped in Java. requested_song_key is computed in Java from requested_song.
ALTER TABLE tip
    ADD COLUMN message_language   varchar(2),
    ADD COLUMN message_english    varchar(280),
    ADD COLUMN requested_song     varchar(80),
    ADD COLUMN requested_song_key varchar(80);

CREATE INDEX tip_requested_song_key_idx ON tip (requested_song_key) WHERE requested_song_key IS NOT NULL;

-- The owner's time zone, checked with java.time.ZoneId whenever it is set.
ALTER TABLE owner
    ADD COLUMN time_zone varchar(64) NOT NULL DEFAULT 'Europe/London';

-- When a device last connected or disconnected, written by devices. Null until it has connected once.
ALTER TABLE device
    ADD COLUMN last_seen_at timestamptz;

-- The panda's notes for one owner and one local day, written by dashboard. bullets and request_groups are
-- null until the first digest is stored. source_watermark is the latest reaction_at the stored digest
-- read; a result is only stored when its watermark is newer. stale is set by every reaction of the owner
-- and cleared by a claim; claimed_at is the claim of the job generating it right now.
CREATE TABLE owner_digest (
    owner_id         uuid        NOT NULL REFERENCES owner (id),
    local_day        date        NOT NULL,
    bullets          jsonb,
    request_groups   jsonb,
    message_count    int         NOT NULL DEFAULT 0,
    source_watermark timestamptz,
    generated_at     timestamptz,
    stale            boolean     NOT NULL DEFAULT false,
    claimed_at       timestamptz,
    PRIMARY KEY (owner_id, local_day)
);

CREATE INDEX owner_digest_stale_idx ON owner_digest (local_day) WHERE stale;
