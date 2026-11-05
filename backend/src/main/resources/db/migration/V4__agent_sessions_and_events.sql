CREATE TABLE agent_sessions (
    id TEXT NOT NULL PRIMARY KEY,
    conversation_id TEXT REFERENCES conversations(id),
    parent_session_id TEXT REFERENCES agent_sessions(id),
    format_version INTEGER NOT NULL CHECK (format_version >= 1),
    cwd TEXT NOT NULL,
    header_metadata_json TEXT NOT NULL CHECK (json_valid(header_metadata_json)),
    seeded INTEGER NOT NULL DEFAULT 0 CHECK (seeded IN (0, 1)),
    inherited_event_count INTEGER NOT NULL DEFAULT 0 CHECK (inherited_event_count >= 0),
    origin TEXT,
    delegation_depth INTEGER NOT NULL DEFAULT 0 CHECK (delegation_depth >= 0),
    agent_preset TEXT,
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'RUNNING', 'IDLE', 'INTERRUPTED', 'ENDED')),
    last_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    open_turn INTEGER NOT NULL DEFAULT 0 CHECK (open_turn IN (0, 1)),
    current_turn INTEGER NOT NULL DEFAULT 0 CHECK (current_turn >= 0),
    current_step INTEGER NOT NULL DEFAULT 0 CHECK (current_step >= 0),
    turn_start_seq INTEGER NOT NULL DEFAULT 0 CHECK (turn_start_seq >= 0),
    last_attempt INTEGER NOT NULL DEFAULT 0 CHECK (last_attempt >= 0),
    config_snapshot_json TEXT CHECK (config_snapshot_json IS NULL OR json_valid(config_snapshot_json)),
    config_version INTEGER CHECK (config_version IS NULL OR config_version >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    ended_at TEXT,
    CHECK (parent_session_id IS NULL OR parent_session_id <> id),
    CHECK ((config_snapshot_json IS NULL AND config_version IS NULL)
        OR (config_snapshot_json IS NOT NULL AND config_version IS NOT NULL))
);
CREATE INDEX idx_sessions_conversation ON agent_sessions(conversation_id, status);

CREATE TABLE session_events (
    session_id TEXT NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL CHECK (seq >= 1),
    event_time TEXT NOT NULL,
    event_type TEXT NOT NULL,
    data_json TEXT NOT NULL CHECK (json_valid(data_json)),
    surface_op_json TEXT CHECK (surface_op_json IS NULL OR json_valid(surface_op_json)),
    surface_start_seq INTEGER,
    surface_end_seq INTEGER,
    source_event_seqs_json TEXT CHECK (source_event_seqs_json IS NULL OR json_valid(source_event_seqs_json)),
    ignorable INTEGER NOT NULL DEFAULT 0 CHECK (ignorable IN (0, 1)),
    PRIMARY KEY (session_id, seq),
    CHECK ((surface_op_json IS NULL AND surface_start_seq IS NULL AND surface_end_seq IS NULL
            AND source_event_seqs_json IS NULL)
        OR (surface_op_json IS NOT NULL AND source_event_seqs_json IS NOT NULL
            AND ((surface_start_seq IS NULL AND surface_end_seq IS NULL)
                OR (surface_start_seq IS NOT NULL AND surface_end_seq IS NOT NULL
                    AND surface_start_seq >= 1 AND surface_end_seq >= surface_start_seq))))
) WITHOUT ROWID;
CREATE INDEX idx_event_type ON session_events(session_id, event_type, seq DESC);
