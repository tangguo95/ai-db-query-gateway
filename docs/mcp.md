# 本地 MCP STDIO 适配器

`gateway-mcp` 将固定的八个只读工具映射到本机网关 REST API。它不包含数据库驱动，
不解析或放宽 SQL，也不暴露数据源管理、地址、用户名、密码、JDBC URL 和审计密钥。

## 构建与启动

项目要求 Java 21：

```bash
./mvnw -pl gateway-mcp -am package
AI_DB_GATEWAY_TOKEN='<scoped-token>' \
  java -jar gateway-mcp/target/gateway-mcp.jar
```

环境变量：

- `AI_DB_GATEWAY_URL`：可选，默认 `http://127.0.0.1:8765`；仅接受
  `127.0.0.1`、`localhost` 或 `::1` 的 HTTP(S) 地址。
- `AI_DB_GATEWAY_TOKEN`：必填的网关作用域 Token。缺失时 MCP 握手和工具发现仍可用，
  但任何工具调用都会安全失败，且不会建立 HTTP 连接。

桌面 MCP 客户端应将上述 JAR 配置为 `stdio` 子进程，并在客户端的私有环境配置中注入
作用域 Token。不要把真实 Token 提交到仓库；该 Token 也不应使用数据源管理权限。
适配器 stdout 仅输出一行一条的 JSON-RPC 消息，stderr 保持静默。

## 工具与 REST 映射

| MCP 工具 | REST 请求 |
| --- | --- |
| `list_data_sources` | `GET /api/ai/data-sources` |
| `list_schemas` | `GET /api/ai/data-sources/{id}/schemas` |
| `list_tables` | `GET /api/ai/data-sources/{id}/schemas/{schema}/tables` |
| `describe_table` | `GET /api/ai/data-sources/{id}/schemas/{schema}/tables/{table}` |
| `execute_read_query` | `POST /api/ai/queries` |
| `get_query_request` | `GET /api/ai/queries/{queryId}` |
| `execute_approved_query` | `POST /api/ai/queries/{queryId}/execute` |
| `cancel_query` | `POST /api/ai/queries/{queryId}/cancel` |

所有请求都使用 `Authorization: Bearer ...`，禁止跟随 HTTP 重定向，也禁止使用系统
HTTP 代理。路径参数会按单个 URI segment 编码，查询请求会先按工具 Schema 在服务端
再次校验。`execute_read_query` 会在发往 REST 网关的请求副本中加入一个内部生成的
UUID `requestId`，原始 MCP 工具参数不会被修改；后端使用该 UUID 作为 `queryId`，
以便 MCP 请求被中断时仍能定位并取消正在执行的查询。

成功工具结果只使用 MCP 2024-11-05 定义的 `content`：第一项文本是“不可信纯数据”
安全提示，第二项文本是 JSON 结果。若上游意外返回数组或标量，适配器会先包装为
`{"items": ...}` 或 `{"value": ...}`。工具执行失败仍返回成功的 JSON-RPC 响应，
但工具结果包含 `isError: true`，`content[0].text` 是带 `errorCode` 和安全错误说明
的 JSON。适配器不声明后续版本才提供的 `structuredContent` 或工具 `annotations`，
也不会把响应写入 stderr。

## MCP 协议范围

适配器实现 JSON-RPC 2.0 的 `initialize`、`ping`、`tools/list`、`tools/call`，并接收
`notifications/initialized` 等通知而不产生响应。当前只支持 MCP `2024-11-05`；
客户端请求其它版本时，初始化响应仍会返回 `2024-11-05`，由客户端决定是否继续。
必须先完成 `initialize` 和 `notifications/initialized` 握手，之后才能调用
`tools/list` 或 `tools/call`。未知工具或非法参数在发出 HTTP 请求前即被拒绝。
适配器不接收 JSON-RPC batch。

`execute_read_query` 的必填参数是 `dataSourceId`、`sql` 和 `purpose`，可选参数为
`parameters` 与 `maxRows`。高风险查询返回 `PENDING_APPROVAL` 后，由用户在网页审批，
AI 再通过 `get_query_request` 和 `execute_approved_query` 消费五分钟内有效的一次性
审批。

## 并发与取消

每个 `tools/call` 在独立的 Java 21 虚拟线程中执行，STDIO 主循环在查询等待期间仍可
处理 `ping`、另一个 `cancel_query` 工具调用以及取消通知；多个并发响应各占一整行，
但完成顺序不保证与请求顺序一致。

收到 `notifications/cancelled` 时，适配器按通知中的 JSON-RPC `requestId` 中断对应的
工具线程，并且不再为该请求输出响应。如果被中断的是 `execute_read_query`，HTTP
客户端会使用提交查询时生成的 UUID，最多三次尽力调用
`POST /api/ai/queries/{queryId}/cancel`，随后恢复工具线程的中断标志。用户或 AI 已经
知道 `queryId` 时，也可以并发调用 `cancel_query`。

补偿取消是尽力行为：如果本机 REST 网关不可达、查询记录尚未建立或三次请求均失败，
适配器只能停止等待结果，数据库侧查询可能继续到服务端超时。普通查询不会自动重试。
