# REST API

服务默认基址为 `http://127.0.0.1:8765`。网页接口使用管理员 Session + CSRF；
`/api/ai/**` 只接受作用域 Bearer Token，并忽略网页 Session 的管理权限。

## 网页管理接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/setup/status` | 初始化状态并建立 CSRF cookie |
| `POST` | `/api/setup` | 消费一次性 bootstrap token |
| `POST` | `/api/auth/login` | 管理员登录 |
| `POST` | `/api/auth/logout` | 注销并失效 Session |
| `GET` | `/api/dashboard` | 安全状态汇总 |
| `GET/POST` | `/api/datasources` | 列表/新增数据源 |
| `GET/PUT/DELETE` | `/api/datasources/{id}` | 查看/更新/软删除 |
| `POST` | `/api/datasources/{id}/test` | 连接复检（不读取账号授权） |
| `GET` | `/api/datasources/{id}/metadata/schemas` | Schema 元数据 |
| `GET` | `/api/datasources/{id}/metadata/tables` | 表/视图元数据 |
| `GET` | `/api/datasources/{id}/metadata/columns` | 列元数据 |
| `POST` | `/api/queries/preview` | 服务端 AST 预检，不创建请求、不连接业务库 |
| `POST` | `/api/queries` | 提交并按策略执行/待审批 |
| `GET` | `/api/queries/{queryId}` | 查看状态 |
| `GET` | `/api/queries/{queryId}/result` | 管理员查看近期执行结果（仅进程内短时缓存） |
| `POST` | `/api/queries/{queryId}/approve` | 一次性批准 AI 风险查询 |
| `POST` | `/api/queries/{queryId}/execute` | 消费批准 |
| `POST` | `/api/queries/{queryId}/cancel` | 取消 |
| `GET/POST` | `/api/tokens` | 列表/签发最小作用域 Token |
| `PUT` | `/api/tokens/{id}/scope` | 调整有效 Token 的数据源范围，原 Token 不变 |
| `DELETE` | `/api/tokens/{id}` | 吊销 Token |
| `GET` | `/api/audits` | 分页查看审计轨迹，可按事件前缀、状态和 queryId 筛选；`eventType=APPROVAL` 仅看审批相关记录 |

网页写请求必须带从 `XSRF-TOKEN` cookie 读取的 `X-XSRF-TOKEN` header。响应统一
`Cache-Control: no-store`，错误体包含稳定的 `code`，不会返回底层 JDBC 异常。

## AI 接口

| 方法 | 路径 | MCP 工具 |
| --- | --- | --- |
| `GET` | `/api/ai/data-sources` | `list_data_sources` |
| `GET` | `/api/ai/data-sources/{id}/schemas` | `list_schemas` |
| `GET` | `/api/ai/data-sources/{id}/schemas/{schema}/tables` | `list_tables` |
| `GET` | `/api/ai/data-sources/{id}/schemas/{schema}/tables/{table}` | `describe_table` |
| `POST` | `/api/ai/queries` | `execute_read_query` |
| `GET` | `/api/ai/queries/{queryId}` | `get_query_request` |
| `POST` | `/api/ai/queries/{queryId}/execute` | `execute_approved_query` |
| `POST` | `/api/ai/queries/{queryId}/cancel` | `cancel_query` |

AI 数据源列表只含 ID、显示名、数据库类型、只读等级和启用状态。

## 查询契约

```json
{
  "dataSourceId": "uuid",
  "sql": "SELECT order_id, status FROM order_info WHERE order_id = ?",
  "parameters": [
    { "type": "STRING", "value": "A10001" }
  ],
  "purpose": "核对工单 #123 的订单状态",
  "maxRows": 200
}
```

参数类型：`STRING/VARCHAR`、`INTEGER/INT`、`LONG/BIGINT`、`DECIMAL/NUMERIC`、
`DOUBLE`、`BOOLEAN`、`DATE`、`TIME`、`TIMESTAMP`、`NULL`。日期时间使用 JDBC
标准文本格式，数值可用字符串传递以避免 JavaScript 精度丢失。

网页工作台在真正提交前调用 `/api/queries/preview`。预检响应包含服务端 AST 识别的
Schema、表、风险原因、数据源只读等级、参数数量、SQL 指纹和实际行数上限；它不会
写入 `query_request`，也不会获取业务数据库连接。真正执行仍会完整重跑同一安全策略，
因此预检不是可复用的绕过凭据。

执行结果位于响应的 `result`：

```json
{
  "queryId": "uuid",
  "status": "EXECUTED",
  "effectiveMaxRows": 200,
  "riskReasons": [],
  "result": {
    "columns": [
      { "label": "order_id", "name": "order_id", "jdbcType": 12, "typeName": "VARCHAR", "nullable": false }
    ],
    "rows": [["A10001"]],
    "truncated": false,
    "durationMs": 18,
    "rowCount": 1,
    "byteCount": 10
  }
}
```

AI 风险查询先返回 `PENDING_APPROVAL`，不含结果。管理员批准后，原 Token 在五分钟内
调用 execute；批准只能消费一次。

网页管理员还可以通过 `GET /api/queries/{queryId}/result` 从审计轨迹回看最近执行结果。
该接口只读取内存中的短时缓存（最多 20 条、15 分钟），不会从 SQLite 或审计记录恢复结果；
缓存不存在时响应仍会返回查询状态和“结果暂不可查看”标识。

## 不存在的能力

没有任意 JDBC URL、原始 HTTP 转发、SQL 脚本批处理、写操作、导出、凭据读取、审计
删除或 AI 数据源管理接口。
