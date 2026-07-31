CREATE TABLE app_setting (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE data_source_config (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    database_type TEXT NOT NULL,
    secret_ref TEXT NOT NULL UNIQUE,
    read_only_status TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    allow_compatibility INTEGER NOT NULL DEFAULT 0,
    query_timeout_seconds INTEGER NOT NULL DEFAULT 10,
    last_tested_at TEXT,
    last_test_message TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE api_token (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    data_source_scope TEXT NOT NULL,
    permissions TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_used_at TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE query_request (
    id TEXT PRIMARY KEY,
    actor TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    data_source_id TEXT NOT NULL,
    sql_cipher TEXT NOT NULL,
    parameters_cipher TEXT NOT NULL,
    sql_fingerprint TEXT NOT NULL,
    purpose TEXT NOT NULL,
    requested_max_rows INTEGER NOT NULL,
    effective_max_rows INTEGER NOT NULL,
    status TEXT NOT NULL,
    risk_reasons TEXT NOT NULL,
    approval_expires_at TEXT,
    approved_at TEXT,
    consumed_at TEXT,
    error_code TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(data_source_id) REFERENCES data_source_config(id)
);

CREATE INDEX idx_query_request_status_created
    ON query_request(status, created_at DESC);

CREATE TABLE audit_event (
    sequence_no INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    occurred_at TEXT NOT NULL,
    actor TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    event_type TEXT NOT NULL,
    data_source_id TEXT,
    query_id TEXT,
    purpose TEXT,
    sql_fingerprint TEXT,
    encrypted_payload TEXT NOT NULL,
    status TEXT NOT NULL,
    duration_ms INTEGER,
    row_count INTEGER,
    byte_count INTEGER,
    error_code TEXT,
    previous_hmac TEXT NOT NULL,
    record_hmac TEXT NOT NULL
);

CREATE INDEX idx_audit_event_occurred_at
    ON audit_event(occurred_at DESC);
CREATE INDEX idx_audit_event_query
    ON audit_event(query_id);
