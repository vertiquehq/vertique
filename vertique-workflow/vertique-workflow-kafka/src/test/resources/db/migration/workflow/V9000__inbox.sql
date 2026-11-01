-- The inbox table a signal source writes, as the inbox module creates it. A node that runs a source installs that
-- module with its migration; these proofs use a table of the same shape and an inbox service over it.
CREATE TABLE inbox (
    message_id   VARCHAR(255) NOT NULL,
    source       VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, source)
);

CREATE INDEX idx_inbox_processed_at ON inbox (processed_at);
