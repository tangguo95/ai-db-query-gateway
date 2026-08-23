ALTER TABLE data_source_config
    ADD COLUMN connection_timeout_seconds INTEGER NOT NULL DEFAULT 5;

-- 该 RDS 生产链路经过 VPN 后认证握手约需 30 秒，直接提高连接等待时间，避免用户再次进入页面配置。
UPDATE data_source_config
SET connection_timeout_seconds = 45,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE name = '公众生产rds数据库produoc_uoc'
  AND deleted = 0;
