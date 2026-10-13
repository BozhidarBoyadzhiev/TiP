-- Brief 07a: judge sandboxes, the public demo and abuse protection. The previous release keeps working while
-- this runs: new columns are nullable or have a default, the relaxed NOT NULLs and widened checks accept every
-- row it writes, and the cascading foreign keys only matter to the cleanup job it does not have.

-- Owner kinds. REAL owners sign in with email and password, as before. A SANDBOX owner is created by an
-- access code, has no email or password, stores the code's label and expires; a PUBLIC_DEMO owner is the one
-- shared read only demo. Written only by owners.
ALTER TABLE owner
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN kind       varchar(16) NOT NULL DEFAULT 'REAL',
    ADD COLUMN code_label varchar(32),
    ADD COLUMN expires_at timestamptz,
    ADD CONSTRAINT owner_kind_valid CHECK (kind IN ('REAL', 'SANDBOX', 'PUBLIC_DEMO')),
    ADD CONSTRAINT owner_real_has_login CHECK (kind <> 'REAL' OR (email IS NOT NULL AND password_hash IS NOT NULL)),
    ADD CONSTRAINT owner_sandbox_expires CHECK (kind <> 'SANDBOX' OR expires_at IS NOT NULL);

CREATE UNIQUE INDEX owner_public_demo_key ON owner (kind) WHERE kind = 'PUBLIC_DEMO';
CREATE INDEX owner_sandbox_expires_at_idx ON owner (expires_at) WHERE kind = 'SANDBOX';

-- Seeded sample tips (no PayPal ids, never refunded through PayPal), and AI_STATUS UNAVAILABLE: the model could
-- not be reached, so the tip reacted PLAIN once without a read.
ALTER TABLE tip
    ADD COLUMN demo boolean NOT NULL DEFAULT false,
    DROP CONSTRAINT tip_ai_status_valid,
    ADD CONSTRAINT tip_ai_status_valid
        CHECK (ai_status IN ('PENDING', 'PROCESSING', 'DONE', 'SKIPPED', 'FAILED', 'UNAVAILABLE'));

-- Deleting an owner deletes their devices, and a device its tips, deliveries and digests, so the sandbox
-- cleanup job removes an expired sandbox with one DELETE on owner. Only that job deletes owners.
ALTER TABLE device
    DROP CONSTRAINT device_owner_id_fkey,
    ADD CONSTRAINT device_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES owner (id) ON DELETE CASCADE;
ALTER TABLE tip
    DROP CONSTRAINT tip_device_id_fkey,
    ADD CONSTRAINT tip_device_id_fkey FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE;
ALTER TABLE reaction_delivery
    DROP CONSTRAINT reaction_delivery_device_id_fkey,
    ADD CONSTRAINT reaction_delivery_device_id_fkey FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE;
ALTER TABLE owner_digest
    DROP CONSTRAINT owner_digest_owner_id_fkey,
    ADD CONSTRAINT owner_digest_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES owner (id) ON DELETE CASCADE;

-- "Continue on another device" links of sandbox owners, written by owners. Only the SHA-256 of the 128 bit
-- token is stored. A token is used once: used_at is set by the one UPDATE that consumes it.
CREATE TABLE resume_token (
    token_hash bytea       PRIMARY KEY,
    owner_id   uuid        NOT NULL REFERENCES owner (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    CONSTRAINT resume_token_hash_length CHECK (octet_length(token_hash) = 32)
);

CREATE INDEX resume_token_owner_id_idx ON resume_token (owner_id);

-- Model calls per UTC hour, written by ai after every call, for the daily summary log line.
CREATE TABLE ai_usage (
    hour     timestamptz PRIMARY KEY,
    calls    int         NOT NULL DEFAULT 0,
    failures int         NOT NULL DEFAULT 0
);
