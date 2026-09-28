CREATE TABLE server_config (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    secret_ref TEXT NOT NULL UNIQUE,
    enabled INTEGER NOT NULL DEFAULT 0,
    full_access INTEGER NOT NULL DEFAULT 0,
    last_test_message TEXT,
    updated_at TEXT NOT NULL
);
