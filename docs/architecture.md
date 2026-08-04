# 架构与状态机

```text
浏览器 ── Session + CSRF ──────────────┐
                                       v
AI 客户端 ── STDIO ── MCP ── Bearer ─> Spring Boot 网关
                                      │
                                      ├─ 认证、作用域、限流
                                      ├─ AST 策略与风险审批
                                      ├─ SQLite 控制面与 HMAC 审计链
                                      ├─ macOS Keychain secretRef
                                      └─ 有界 JDBC 池 ──> MySQL / OceanBase
```

Spring Boot 服务是唯一策略决定点和唯一数据库连接方。MCP 只是协议适配器，不包含 JDBC
驱动，不复刻安全规则，也不提供任意 REST 转发。

## 模块边界

- `api/security`：网页会话、CSRF、Bearer、Host/Origin、限流和 DTO。
- `datasource`：连接器 SPI、服务端 JDBC URL、Keychain 解引用、连接检查和元数据。
- `query`：AST、风险、审批状态、并发预算、执行、取消和结果截断。
- `audit`：敏感载荷加密、同步预写、链式 HMAC、启动/每日校验。
- `gateway-mcp`：MCP 2024-11-05 握手、固定工具 Schema、并发和补偿取消。
- `native/macos-keychain`：只接受 stdin JSON 的 Security.framework helper。

## 数据源状态

```text
新增/凭据变化
      │
      v
   UNKNOWN / ISOLATED
      │ 连接复检
      ├─ 连接成功 ─────────────────> COMPATIBILITY / ENABLED
      └─ 连接失败 ─────────────────> UNKNOWN / ISOLATED
```

启动后立即检查已启用数据源，此后每小时复检；每次复检在获取业务连接前先临时隔离，
检查成功后才恢复启用。凭据改变时先在 SQLite 中隔离，再更新 Keychain，避免把旧的
连接结论错误套用到新账号。运行时不查询数据库账号权限。

网页“安全设置”可以开启隔离数据源的自动连接复检。开启后后台约每分钟探测一次
`UNKNOWN` 数据源，连续失败采用内存退避，最长间隔 15 分钟；只获取连接和数据库元数据，
不会执行或重试生产 SQL。该开关默认关闭，设置写入本地 `app_setting`。

## 查询状态

```text
REQUESTED（审计先写）
   ├─ AST/参数/作用域拒绝 ─────────────────────> REJECTED
   ├─ 网页管理员普通执行 ─> APPROVED -> EXECUTING
   └─ AI 查询
       ├─ 无风险 ─────────> APPROVED -> EXECUTING
       └─ 有风险 ──审批开启──> PENDING_APPROVAL
                                ├─ 超时/拒绝 ───> EXPIRED / REJECTED
                                └─ 一次性批准 ─> APPROVED -> EXECUTING
                   \─审批关闭──> APPROVED -> EXECUTING（审计记录免审批放行）

EXECUTING ──> EXECUTED | FAILED | TIMED_OUT | CANCELLED
```

风险条件为系统 Schema、跨 Schema、超过三个表、`SELECT *`、外层无过滤条件的明细
查询或请求超过 200 行。批准记录已经包含加密 SQL/参数和实际限额，因此执行时不能
替换请求内容。

取消与完成通过 SQLite 条件更新竞争终态：只有 `EXECUTING` 可变成 `EXECUTED`；
`CANCELLED` 不会被迟到的成功结果覆盖。JDBC `Statement.cancel()` 失败时关闭整个
小型数据源池，防止物理连接继续在后台执行。

## 查询执行顺序

1. 校验会话/Token、数据源作用域、用途、参数数量和类型。
2. 同步写入 `QUERY_REQUESTED`；审计不可用即停止。
3. 按目标方言解析唯一 AST，提取表、Schema、函数和风险。
4. 取得全局/数据源信号量，再从每数据源最多两个连接的池中取连接。
5. 设置 JDBC read-only、`autoCommit=false` 和数据库 `SET TRANSACTION READ ONLY`。
6. 只调用 `PreparedStatement.executeQuery()`，设置超时、fetch size 和 max rows。
7. 流式读取文本/BLOB；字段、行数或响应预算触发截断与主动 cancel。
8. 无条件 rollback，原子写入终态，再记录耗时、行数、字节数和只读等级。

不自动重试生产查询。

## 持久化边界

SQLite 保存：

- 数据源显示信息、`secretRef`、只读状态和复检时间；
- Argon2id 管理员密码摘要；
- API Token 摘要、作用域和有效期；
- 加密查询申请、审批状态和审计链。

Keychain 保存：

- 完整数据库连接资料；
- 审计 AES-256-GCM 密钥；
- 审计 HMAC 密钥。

永不保存查询结果。完整 SQL/参数虽然会作为加密查询申请和加密审计载荷落盘，但不会
出现在明文列、普通日志或 MCP stderr。
