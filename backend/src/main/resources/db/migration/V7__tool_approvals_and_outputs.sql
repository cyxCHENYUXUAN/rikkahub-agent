-- V7: durable approval projection and session-bound tool output storage (P3).
-- Existing V1-V6 tables are never rewritten; new projections join the session
-- commit transaction through SessionProjection and never write session events.

CREATE TABLE approvals (
    id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES agent_sessions(id),
    call_id TEXT NOT NULL DEFAULT '',
    tool_name TEXT NOT NULL DEFAULT '',
    reason TEXT,
    turn INTEGER NOT NULL DEFAULT 0 CHECK (turn >= 0),
    arguments_json TEXT NOT NULL DEFAULT '',
    arguments_digest TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING', 'allowed-once', 'rejected', 'cancelled', 'unavailable')),
    requested_decision TEXT CHECK (requested_decision IS NULL OR
        requested_decision IN ('allowed-once', 'rejected')),
    decision_note TEXT,
    asked_seq INTEGER NOT NULL DEFAULT 0 CHECK (asked_seq >= 0),
    decided_seq INTEGER CHECK (decided_seq IS NULL OR decided_seq > 0),
    expires_at TEXT,
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_approvals_session ON approvals(session_id, asked_seq);

CREATE TABLE tool_outputs (
    reference TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES agent_sessions(id),
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'READY')),
    charged_bytes INTEGER NOT NULL DEFAULT 0 CHECK (charged_bytes >= 0),
    retained_bytes INTEGER NOT NULL DEFAULT 0 CHECK (retained_bytes >= 0),
    source_bytes INTEGER NOT NULL CHECK (source_bytes >= -1),
    status TEXT NOT NULL DEFAULT 'COMPLETE',
    content_hash TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT ''
);

CREATE INDEX idx_tool_outputs_session ON tool_outputs(session_id, created_at);
