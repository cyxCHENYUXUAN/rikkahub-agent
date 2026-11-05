-- Session-level tool/approval policy override. NULL means "inherit the assistant".
-- The effective policy is resolved at each turn start and frozen for that turn.
ALTER TABLE conversations ADD COLUMN tool_permission_mode TEXT
    CHECK (tool_permission_mode IS NULL OR tool_permission_mode IN
        ('READ_ONLY', 'WORKSPACE_WRITE', 'FULL_ACCESS'));
ALTER TABLE conversations ADD COLUMN approval_policy TEXT
    CHECK (approval_policy IS NULL OR approval_policy IN ('ASK', 'NEVER'));
ALTER TABLE conversations ADD COLUMN tool_rules_json TEXT
    CHECK (tool_rules_json IS NULL OR json_valid(tool_rules_json));
