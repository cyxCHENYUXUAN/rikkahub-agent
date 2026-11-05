-- Engine writes aggregates and durable work in one short transaction.
CREATE TABLE orchestration_entities (
    kind TEXT NOT NULL CHECK(kind IN ('trigger','thread','eval','mandate','ticket','assignment','reply')),
    id TEXT NOT NULL,
    data_json TEXT NOT NULL CHECK(json_valid(data_json)),
    created_at TEXT NOT NULL,
    PRIMARY KEY(kind,id)
);
CREATE INDEX idx_orchestration_oldest ON orchestration_entities(kind,created_at,id);

CREATE TABLE effects (
    id TEXT PRIMARY KEY,
    session_id TEXT REFERENCES agent_sessions(id),
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DONE','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    retry_safe INTEGER NOT NULL DEFAULT 0 CHECK(retry_safe IN (0,1)),
    available_at TEXT NOT NULL,
    claim_token TEXT,
    lease_until TEXT,
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((status='RUNNING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'RUNNING' AND claim_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_effects_due ON effects(status,available_at,created_at);
CREATE TABLE outbox (
    id TEXT PRIMARY KEY,
    session_id TEXT REFERENCES agent_sessions(id),
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DONE','DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    retry_safe INTEGER NOT NULL DEFAULT 0 CHECK(retry_safe IN (0,1)),
    available_at TEXT NOT NULL,
    claim_token TEXT,
    lease_until TEXT,
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CHECK((status='RUNNING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'RUNNING' AND claim_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_outbox_due ON outbox(status,available_at,created_at);
CREATE TABLE orchestration_sources (
    id TEXT PRIMARY KEY,
    thread_key TEXT NOT NULL,
    text TEXT NOT NULL,
    interval_seconds INTEGER NOT NULL CHECK(interval_seconds BETWEEN 60 AND 31536000),
    next_at TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
    created_at TEXT NOT NULL
);
