CREATE TABLE owner (
    id            uuid         PRIMARY KEY,
    email         varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    display_name  varchar(80)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    CONSTRAINT owner_email_key UNIQUE (email),
    CONSTRAINT owner_email_lowercase CHECK (email = lower(email))
);

-- Devices created before owners existed go to a placeholder owner that nobody can sign in as: its
-- password hash matches no password. The demo seeder takes the demo device over on its next start.
-- Deleting the devices instead would also delete their tips, which hold real sandbox payments.
INSERT INTO owner (id, email, password_hash, display_name, created_at)
SELECT '00000000-0000-0000-0000-000000000000', 'unclaimed@tip.invalid', '{bcrypt}!', 'Unclaimed devices', now()
WHERE EXISTS (SELECT 1 FROM device);

ALTER TABLE device
    ADD COLUMN owner_id    uuid REFERENCES owner (id),
    ADD COLUMN secret_hash bytea;

-- A random hash nobody knows the secret for, so an existing device cannot authenticate until its
-- secret is set. gen_random_uuid() uses PostgreSQL's strong random source.
UPDATE device
SET owner_id    = '00000000-0000-0000-0000-000000000000',
    secret_hash = decode(replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''), 'hex');

ALTER TABLE device
    ALTER COLUMN owner_id SET NOT NULL,
    ALTER COLUMN secret_hash SET NOT NULL,
    ADD CONSTRAINT device_secret_hash_length CHECK (octet_length(secret_hash) = 32);

CREATE INDEX device_owner_id_idx ON device (owner_id);

ALTER TABLE tip
    DROP CONSTRAINT tip_status_valid,
    ADD CONSTRAINT tip_status_valid
        CHECK (status IN ('CREATED', 'PENDING', 'CAPTURED', 'FAILED', 'REFUNDED', 'REVERSED', 'ABANDONED')),
    ADD COLUMN ai_claimed_at timestamptz,
    ADD COLUMN ai_attempts   int          NOT NULL DEFAULT 0,
    ADD COLUMN flag_reason   varchar(200),
    ADD COLUMN reaction_at   timestamptz,
    ADD CONSTRAINT tip_ai_status_valid CHECK (ai_status IN ('PENDING', 'PROCESSING', 'DONE', 'SKIPPED', 'FAILED'));

-- The AI recovery job looks for captured tips whose classification has not finished.
CREATE INDEX tip_ai_unfinished_idx ON tip (captured_at)
    WHERE status = 'CAPTURED' AND ai_status IN ('PENDING', 'PROCESSING');
