UPDATE data_source_config
SET last_test_message = '连接检查通过，查询操作由网关统一执行只读控制',
    updated_at = CURRENT_TIMESTAMP
WHERE last_test_message = '连接检查通过：未检查数据库账号权限，仅由网关强制只读';
