ALTER TABLE data_source_config
    ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 1;
