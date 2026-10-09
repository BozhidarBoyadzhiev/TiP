-- The panda now reacts to the kind of message instead of its tone. Old reactions map onto the new
-- ones: POSITIVE becomes WARM, NEUTRAL and NEGATIVE become PLAIN, because abuse gets no reaction of
-- its own any more.
ALTER TABLE tip DROP CONSTRAINT tip_reaction_valid;
ALTER TABLE reaction_delivery DROP CONSTRAINT reaction_delivery_reaction_valid;

UPDATE tip
SET reaction = CASE reaction WHEN 'POSITIVE' THEN 'WARM' ELSE 'PLAIN' END
WHERE reaction IS NOT NULL;

UPDATE reaction_delivery
SET reaction = CASE reaction WHEN 'POSITIVE' THEN 'WARM' ELSE 'PLAIN' END;

-- message_kind is what the model said the message is; reaction is what the panda plays, PLAIN when the
-- message was flagged. owner_note is a short neutral note for the owner, only on unflagged requests.
ALTER TABLE tip
    ADD COLUMN message_kind varchar(10),
    ADD COLUMN owner_note   varchar(100),
    ADD CONSTRAINT tip_reaction_valid CHECK (reaction IN ('WARM', 'PLAYFUL', 'REQUEST', 'PLAIN')),
    ADD CONSTRAINT tip_message_kind_valid CHECK (message_kind IN ('WARM', 'PLAYFUL', 'REQUEST', 'PLAIN'));

-- Tips the model classified keep their kind; skipped and failed tips were never read, so they have none.
UPDATE tip SET message_kind = reaction WHERE ai_status = 'DONE' AND reaction IS NOT NULL;

ALTER TABLE reaction_delivery
    ADD CONSTRAINT reaction_delivery_reaction_valid CHECK (reaction IN ('WARM', 'PLAYFUL', 'REQUEST', 'PLAIN'));
