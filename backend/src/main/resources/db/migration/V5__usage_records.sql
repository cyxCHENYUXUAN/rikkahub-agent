CREATE TABLE usage_records (
    id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    record_index INTEGER NOT NULL CHECK (record_index >= 1),
    turn INTEGER NOT NULL CHECK (turn >= 0),
    step INTEGER NOT NULL CHECK (step >= 0),
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    purpose TEXT NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cache_read_tokens INTEGER NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    cache_write_tokens INTEGER NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0),
    reasoning_tokens INTEGER NOT NULL DEFAULT 0 CHECK (reasoning_tokens >= 0),
    cost_micros INTEGER NOT NULL DEFAULT 0 CHECK (cost_micros >= 0),
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id, seq) REFERENCES session_events(session_id, seq),
    UNIQUE (session_id, seq, record_index)
);
CREATE INDEX idx_usage_session ON usage_records(session_id, created_at);
