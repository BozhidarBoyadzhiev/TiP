-- Delivery of each tip's reaction to its device. Owned by the devices feature, so it is a table of its
-- own rather than more columns on tip. One row per tip: the primary key makes a duplicate TipReacted a
-- no op. No foreign key to tip, because devices does not own that table.
CREATE TABLE reaction_delivery (
    tip_id       uuid        PRIMARY KEY,
    device_id    uuid        NOT NULL REFERENCES device (id),
    reaction     varchar(10) NOT NULL,
    reacted_at   timestamptz NOT NULL,
    sent_count   int         NOT NULL DEFAULT 0,
    last_sent_at timestamptz,
    acked_at     timestamptz,
    CONSTRAINT reaction_delivery_reaction_valid CHECK (reaction IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE'))
);

-- Replay on connect and the resend job only look at unacknowledged reactions of one device.
CREATE INDEX reaction_delivery_unacked_idx ON reaction_delivery (device_id, reacted_at)
    WHERE acked_at IS NULL;

-- V2 created a placeholder owner for devices that existed before owners. Once the demo seeder has taken
-- those devices over it owns nothing, so it goes.
DELETE FROM owner
WHERE id = '00000000-0000-0000-0000-000000000000'
  AND NOT EXISTS (SELECT 1 FROM device WHERE owner_id = '00000000-0000-0000-0000-000000000000');
