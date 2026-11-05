CREATE TABLE conversations (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    assistant_id TEXT REFERENCES assistants(id),
    current_node_id TEXT,
    session_id TEXT UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    metadata_version INTEGER NOT NULL DEFAULT 0 CHECK (metadata_version >= 0),
    last_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    archived_at TEXT
);
CREATE INDEX idx_conversations_updated ON conversations(status, updated_at DESC);

CREATE TABLE message_nodes (
    id TEXT NOT NULL PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    parent_id TEXT REFERENCES message_nodes(id),
    session_id TEXT NOT NULL REFERENCES agent_sessions(id),
    anchor_seq INTEGER NOT NULL CHECK (anchor_seq >= 1),
    source_seq INTEGER NOT NULL CHECK (source_seq >= anchor_seq),
    end_seq INTEGER CHECK (end_seq IS NULL OR end_seq >= anchor_seq),
    role TEXT NOT NULL CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'STREAMING', 'COMPLETE', 'INTERRUPTED', 'ERROR')),
    visible INTEGER NOT NULL DEFAULT 1 CHECK (visible IN (0, 1)),
    surface_position INTEGER NOT NULL CHECK (surface_position >= 1),
    hidden_at_seq INTEGER,
    created_at TEXT NOT NULL,
    UNIQUE (session_id, anchor_seq),
    UNIQUE (session_id, source_seq)
);
CREATE INDEX idx_message_nodes_parent ON message_nodes(conversation_id, parent_id);
CREATE INDEX idx_message_surface ON message_nodes(conversation_id, visible, surface_position);

CREATE TABLE messages (
    node_id TEXT NOT NULL PRIMARY KEY REFERENCES message_nodes(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL REFERENCES conversations(id),
    content_json TEXT NOT NULL CHECK (json_valid(content_json)),
    tool_calls_json TEXT CHECK (tool_calls_json IS NULL OR json_valid(tool_calls_json)),
    tool_call_id TEXT,
    usage_json TEXT CHECK (usage_json IS NULL OR json_valid(usage_json)),
    error_json TEXT CHECK (error_json IS NULL OR json_valid(error_json)),
    updated_at TEXT NOT NULL
);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, updated_at DESC);

CREATE TABLE inbox_items (
    id TEXT NOT NULL PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES agent_sessions(id),
    conversation_id TEXT REFERENCES conversations(id),
    target TEXT NOT NULL CHECK (target IN ('NEXT_TURN', 'NEXT_STEP')),
    payload_json TEXT NOT NULL CHECK (json_valid(payload_json)),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'CONSUMED', 'CANCELLED')),
    idempotency_key TEXT,
    claimed_at TEXT,
    lease_until TEXT,
    claim_token TEXT UNIQUE,
    lease_owner TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TEXT NOT NULL,
    consumed_at TEXT,
    UNIQUE (session_id, idempotency_key),
    CHECK ((status = 'IN_PROGRESS' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL
            AND lease_until IS NOT NULL AND lease_until > claimed_at AND lease_owner IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND claim_token IS NULL AND claimed_at IS NULL
            AND lease_until IS NULL AND lease_owner IS NULL)),
    CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL)
        OR (status <> 'CONSUMED' AND consumed_at IS NULL))
);
CREATE INDEX idx_inbox_claim ON inbox_items(session_id, status, created_at);
CREATE INDEX idx_inbox_target ON inbox_items(session_id, target, status, created_at);
CREATE INDEX idx_inbox_expired ON inbox_items(session_id, lease_until) WHERE status = 'IN_PROGRESS';
