CREATE TABLE kubernetes_config (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    secret_ref TEXT NOT NULL UNIQUE,
    context_name TEXT NOT NULL,
    namespaces TEXT NOT NULL,
    allow_container_query INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL
);
