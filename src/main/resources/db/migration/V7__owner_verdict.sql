-- Brief 06c. The owner can overrule the AI's flag. Both columns are nullable, so the previous release keeps
-- working while this migration runs: it never reads them and still hides every flagged message.

-- Written only by dashboard. owner_verdict is null (the AI's flag stands) or NOT_ABUSIVE (the owner read the
-- message and overruled the flag). owner_verdict_at is when the owner last changed it, in either direction.
-- flagged and flag_reason stay what the AI decided and are never changed by a verdict. A message is hidden
-- from its owner while flagged AND owner_verdict IS NULL.
ALTER TABLE tip
    ADD COLUMN owner_verdict    varchar(16),
    ADD COLUMN owner_verdict_at timestamptz,
    ADD CONSTRAINT tip_owner_verdict_valid CHECK (owner_verdict IN ('NOT_ABUSIVE'));
