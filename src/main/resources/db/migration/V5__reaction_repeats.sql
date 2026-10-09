-- How many times the device repeats the reaction's unit, scaled by the tip amount (1 to 5). Stored with
-- the delivery so a replay after reconnect sends the value that was decided, never a recomputed one.
-- Existing rows get 1, the patterns as they were.
ALTER TABLE reaction_delivery
    ADD COLUMN repeats smallint NOT NULL DEFAULT 1,
    ADD CONSTRAINT reaction_delivery_repeats_valid CHECK (repeats BETWEEN 1 AND 5);
