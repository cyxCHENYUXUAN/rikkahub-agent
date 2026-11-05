CREATE TABLE memory_documents (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE VIRTUAL TABLE memory_fts USING fts5(
    id UNINDEXED,
    title,
    body,
    tokenize = 'unicode61'
);
