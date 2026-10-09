CREATE TABLE device (
    id           uuid         PRIMARY KEY,
    slug         varchar(40)  NOT NULL,
    display_name varchar(80)  NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT device_slug_key UNIQUE (slug),
    CONSTRAINT device_slug_format CHECK (slug ~ '^[a-z0-9-]{3,40}$')
);

CREATE TABLE tip (
    id                uuid         PRIMARY KEY,
    device_id         uuid         NOT NULL REFERENCES device (id),
    amount_minor      bigint       NOT NULL,
    currency          char(3)      NOT NULL,
    message           varchar(280),
    status            varchar(16)  NOT NULL,
    paypal_order_id   varchar(64),
    paypal_capture_id varchar(64),
    reaction          varchar(16),
    flagged           boolean      NOT NULL DEFAULT false,
    ai_status         varchar(16)  NOT NULL DEFAULT 'PENDING',
    created_at        timestamptz  NOT NULL,
    captured_at       timestamptz,
    CONSTRAINT tip_paypal_order_id_key UNIQUE (paypal_order_id),
    CONSTRAINT tip_paypal_capture_id_key UNIQUE (paypal_capture_id),
    CONSTRAINT tip_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT tip_currency_length CHECK (char_length(currency) = 3),
    CONSTRAINT tip_status_valid CHECK (status IN ('CREATED', 'PENDING', 'CAPTURED', 'FAILED', 'REFUNDED', 'ABANDONED')),
    CONSTRAINT tip_reaction_valid CHECK (reaction IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE'))
);

CREATE INDEX tip_status_created_at_idx ON tip (status, created_at);
CREATE INDEX tip_device_id_created_at_idx ON tip (device_id, created_at);

CREATE TABLE paypal_webhook_event (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    varchar(64)  NOT NULL,
    event_type  varchar(100) NOT NULL,
    resource_id varchar(64),
    received_at timestamptz  NOT NULL,
    CONSTRAINT paypal_webhook_event_event_id_key UNIQUE (event_id)
);
