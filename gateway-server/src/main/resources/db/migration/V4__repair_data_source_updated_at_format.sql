UPDATE data_source_config
SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE last_test_message = '连接检查通过，查询操作由网关统一执行只读控制'
  AND updated_at NOT LIKE '%T%';
