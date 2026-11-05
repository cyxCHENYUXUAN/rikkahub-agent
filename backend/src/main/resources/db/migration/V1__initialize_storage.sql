-- Product/Agent tables will be introduced by later versioned migrations.
-- No sample records or provider credentials are inserted.
CREATE TABLE app_settings (
    setting_key TEXT NOT NULL PRIMARY KEY,
    value_json TEXT NOT NULL CHECK (json_valid(value_json)),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
