# AI DB Query Gateway

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[中文说明](README_ZH.md)

AI DB Query Gateway 是一个本地优先、带审计能力的 AI 数据库查询与 Linux 服务器管理网关。
数据库侧支持 MySQL、DRDS 和 OceanBase 只读查询；服务器侧支持通过 SSH 执行受控命令。
网页管理连接与授权，本地 MCP STDIO 适配器提供八个数据库工具和两个服务器工具。

项目面向单台 macOS 或 Windows 电脑，数据库凭据、SSH 密码和私钥由网关保管，避免每次
与 AI 对话时重复粘贴认证信息。

## What it does

- Supports MySQL, legacy DRDS MySQL compatibility mode (Connector/J 5.1), OceanBase MySQL mode, and OceanBase Oracle mode through a connector SPI.
- Keeps database credentials and audit encryption keys in macOS Keychain or Windows current-user
  DPAPI-protected ciphertext files; SQLite stores metadata, scope information, and the audit chain
  rather than plaintext credentials.
- Lets an administrator manage data sources, approvals, scoped API tokens, and audit events
  from the web console.
- MCP 提供八个数据库工具，以及 `list_servers`、`execute_server_command` 两个服务器工具。
  服务器支持直连或单级 SSH 跳板机，密码或私钥认证；默认查询模式，管理员可开启完整权限。
  使用方法见 [Linux 服务器管理](README_ZH.md#linux-服务器管理)。
- MCP 不暴露保存的连接信息、认证材料、Keychain 引用或管理接口；远程命令输出由 SSH 账号的可访问范围决定。
- Applies an AST-based SQL policy before opening a business-database connection. Only one
  `SELECT`, `UNION`, or non-recursive CTE whose final statement is a query is accepted.
- 数据库查询拒绝写操作、DDL、事务和会话控制、多语句、锁、文件操作、数据库链接、递归 CTE 及未支持函数；此限制不等同于服务器的完整权限模式。
- Security settings list the default functions and manage additional functions per data source.
  Argument-free MATERIALIZE, INLINE, MERGE, and NO_MERGE hints can be enabled per database type.
  Hints are disabled by default; changes persist immediately and are audited.
- 数据库查询限制行数、响应大小、字段大小、超时和并发，支持一次性审批、取消和回滚；服务器命令另有执行限制。
- Never writes query results to SQLite, audit records, ordinary logs, CSV files, or Excel files.
  For administrator review, the service keeps at most 20 recent results in process memory for
  up to 15 minutes; they disappear on restart or expiry.

## Linux 服务器管理

AI 可以通过 MCP 自行编写并执行 Linux 命令，无需预设运维操作。网关保管 SSH 认证材料，
支持直接连接或经过单级 SSH 跳板机；目标服务器和跳板机可分别使用密码或私钥认证，
支持加密私钥口令。当前不进行 SSH 主机指纹校验，也不要求手动填写指纹。

### 配置与使用

1. 在网页“Linux 服务器”中新增连接，填写显示名称、主机、端口、账号及密码或私钥；需要时配置跳板机。
2. 点击“测试连接”，通过后才能点击“确认保存”。修改连接信息后需重新测试；后端保存时也会再次校验，失败不会新增或覆盖原配置。
3. 保存成功后自动启用，默认查询模式。在“访问令牌”中选择允许访问的服务器，可以创建仅有服务器权限的令牌；已有数据库令牌不会自动获得服务器权限。
4. 在已连接网关的 MCP 客户端中，让 AI 调用 `list_servers` 获取服务器 ID，再调用 `execute_server_command` 执行命令并填写用途。

例如，可以对 AI 说：“列出可访问的服务器，在测试服务器执行 `df -h`，查看磁盘使用情况。”
对应的命令工具参数为：

```json
{
  "serverId": "<服务器 ID>",
  "command": "ps aux | head -n 20",
  "purpose": "查看进程运行状态",
  "timeoutSeconds": 15
}
```

### 查询模式与完整权限

| 能力 | 查询模式（默认） | 完整权限 |
| --- | --- | --- |
| 查看状态、进程、端口和日志 | 允许常见查询命令的有限参数及管道 | 允许 |
| 修改文件、启停服务、执行脚本 | 拒绝 | 允许，受 SSH 账号权限约束 |
| `sudo`、重定向、复合 Shell 语句 | 拒绝 | 允许，受 SSH 账号权限约束 |

完整权限由网页管理员为每台服务器单独开启，影响所有获该服务器授权的令牌，不会自动
获得 root 权限。保存连接配置会恢复查询模式。查询模式属于应用层命令限制，不是 Linux
账号隔离，也不会自动隐藏文件和日志中的敏感信息。

列表中的“停用”会暂停访问；“删除”需确认，会清除连接配置、保存的凭据及令牌中的对应授权，
保留审计记录，不会删除远程服务器的文件或停止服务。

### 执行限制

- MCP 新增 `list_servers`、`execute_server_command`，与原有八个数据库工具共用同一适配器。
- 每次命令使用独立的非交互 SSH 会话，不保留 `cd` 或环境变量；首版不提供交互式终端、SFTP、多级跳板或动态验证码登录。
- 等待命令默认 15 秒、最大 30 秒；含两跳连接的总预算为 38 秒。输出合计最多 256 KiB，全局并发 4、单服务器并发 1。
- 命令以加密载荷写入审计，同时记录用途和状态，输出不持久化。接口不返回保存的密码或私钥，但远程文件、日志和命令输出仍可能包含敏感信息。
- 超时、输出截断或连接中断不代表远程进程已停止，也不会回滚；结果不明时先核对远程状态，不自动重试有副作用的命令。

升级后需重启网关并重新连接 MCP，以加载新增工具。详细参数与状态语义见 [MCP 文档](docs/mcp.md#linux-服务器命令)。

## 数据库只读边界

The gateway deliberately does **not** inspect database account privileges. A successful
connection is marked as `COMPATIBILITY` and every query is forced through the gateway's
read-only execution path. This is an application-layer control, not a database-level
permission guarantee. For production use, pair it with a database account that has only
the required `SELECT` permissions.

## Architecture

```text
Browser console ── session + CSRF ──┐
                                    v
AI client ── STDIO ── MCP ── token ──> Spring Boot gateway
                                      │
                                      ├─ authentication and token scope
                                      ├─ SQL AST policy and approval workflow
                                      ├─ SQLite control plane and chained HMAC audit
                                      ├─ macOS Keychain / Windows DPAPI secret references
                                      ├─ bounded JDBC pools ──> MySQL / DRDS / OceanBase
                                      └─ SSH 命令执行 ──> Linux（直连 / 单级跳板机）
```

The service listens on `127.0.0.1:8765` by default. It is not a database TCP proxy and it
does not provide remote HTTP MCP. Non-loopback binding requires explicit remote mode and
Spring TLS configuration.

## Requirements

- macOS with Keychain access, or Windows 10/11 with current-user DPAPI
- Java 21
- Xcode Command Line Tools (macOS only, to build the Swift Keychain helper)
- Docker Desktop is optional and useful for MySQL integration tests
- Internet access on the first build so Maven and the pinned Node/npm runtime can be downloaded

The build downloads Node `22.14.0` and npm `10.9.2` into an ignored project directory; a
system-wide Node installation is not required.

## Quick start

### macOS

```bash
git clone https://github.com/tangguo95/ai-db-query-gateway.git
cd ai-db-query-gateway
./scripts/build.sh
./scripts/launchd.sh start
```

### Windows PowerShell

```powershell
git clone https://github.com/tangguo95/ai-db-query-gateway.git
Set-Location ai-db-query-gateway
.\scripts\build.ps1
.\scripts\windows-service.ps1 start
.\scripts\run-tray.ps1
```

Open <http://127.0.0.1:8765>. On the first launch the service creates a one-time
`bootstrap-token` file at:

```text
macOS:   ~/Library/Application Support/AI DB Query Gateway/bootstrap-token
Windows: %LOCALAPPDATA%\AI DB Query Gateway\bootstrap-token
```

Set `GATEWAY_DATA_DIR` to override the runtime directory on either platform.

Read the token locally and enter it in the setup page to choose the local administrator
password. The token is deleted after successful setup and the password is stored only as an
Argon2id hash.

The default runtime directory is:

```text
macOS:   ~/Library/Application Support/AI DB Query Gateway/
Windows: %LOCALAPPDATA%\AI DB Query Gateway\
```

It contains the SQLite control database and bootstrap state. Database credentials and audit keys
are stored separately in the platform secure store; they are not put in the repository, process
arguments, environment variables, or ordinary logs.

The launchd command is a manually loaded user service, not a login shortcut. It stays running
after the terminal is closed and restarts after an unexpected crash, but it is not loaded after
macOS login or reboot. Use these commands when needed:

```bash
./scripts/launchd.sh status
./scripts/launchd.sh stop
```

For foreground troubleshooting, use `./scripts/run.sh` instead. The foreground command is tied
to its terminal and will stop when that terminal session ends.

On Windows, use `.\scripts\run.ps1` for foreground troubleshooting and
`.\scripts\windows-service.ps1 {status|stop|restart}` for the background process.

### Windows notification-area manager

`.\scripts\build.ps1` also builds a standalone Windows app image. To launch the tray app:

```powershell
.\scripts\run-tray.ps1
```

It refreshes the local gateway health every five seconds and provides start, stop, restart,
the Web console, and the service log directory from its notification-area menu. The generated
executable is under `gateway-tray\build\app-image\AI DB Query Gateway Tray\` and includes its
own Java runtime and gateway server artifact.

### macOS menu bar manager

Build and open the optional menu bar utility:

```bash
./native/statusbar/build.sh
open "native/statusbar/build/AI DB Query Gateway.app"
```

The utility exposes the launchd service status, start/stop/restart actions, the web console, and
service logs. It does not enable login startup and does not handle database credentials.

## Connect an AI client with MCP

1. 在网页中新增并测试数据源或 Linux 服务器。
2. 创建短期令牌，只勾选客户端需要的数据源和服务器，并确认结果可能发送给 AI 的提示。
3. Store the token in the local platform secure store. On macOS:

   ```bash
   ./scripts/configure-mcp-token.sh
   ```

   On Windows PowerShell:

   ```powershell
   .\scripts\configure-mcp-token.ps1
   ```

4. Start the MCP adapter in a separate terminal or from the client configuration:

   ```bash
   ./scripts/run-mcp.sh
   ```

   On Windows PowerShell:

   ```powershell
   .\scripts\run-mcp.ps1
   ```

For clients that manage MCP processes themselves, use the generated JAR and keep the token in
the client's private secret configuration:

```json
{
  "command": "java",
  "args": ["-jar", "/absolute/path/to/gateway-mcp/target/gateway-mcp.jar"],
  "env": {
    "AI_DB_GATEWAY_URL": "http://127.0.0.1:8765",
    "AI_DB_GATEWAY_TOKEN": "<scoped-token>"
  }
}
```

Never commit that configuration or paste a live token into an issue, chat, shell history, or
README. 令牌只显示一次，之后可以调整数据源和服务器范围，无需重新生成令牌或修改 MCP 配置。

## Typical AI workflow

Ask the AI client to list available data sources, inspect the target schema/table, and run a
parameterized `SELECT` with a short purpose, for example:

```text
使用“订单查询库”，先查看 order_info 的字段；然后按订单号 ? 查询该订单的状态，
用途是核对工单 123，最多返回 20 行。不要执行任何写操作。
```

Risky requests (for example `SELECT *`, system schemas, multiple schemas, more than three
tables, or more than 200 rows) are returned as pending approval by default. The administrator
can turn on **免审批执行** in 网页的“安全设置”；this only skips the one-time web approval
for high-risk AI requests. The AST read-only policy, data-source scope, timeout, concurrency,
row limit and response-size limit remain enforced. The switch applies to newly submitted
requests; existing pending requests keep their current state.

## Limits and security notes

The default policy allows 200 rows and caps a request at 1,000 rows, 32 KiB SQL, 5 MiB
response bytes, 256 KiB per field, 5–30 second data-source timeouts, two concurrent queries
per data source, four globally, and 30 requests per token per minute. These values are
server-side limits; clients cannot raise them.

Read [SECURITY.md](SECURITY.md) before connecting a production database. It documents the
same-user macOS/Windows shell boundary, the limits of local chained-HMAC audit protection, cancellation
semantics, TLS choices, and the risk that an AI provider may receive raw query results.

## Project layout

```text
gateway-server/          Spring Boot API、策略、JDBC / SSH 执行、审计和静态资源托管
gateway-mcp/              MCP 2024-11-05 STDIO adapter without database drivers
gateway-tray/             Windows notification-area app and jpackage packaging input
frontend/                 Vue 3 + TypeScript web console
native/macos-keychain/    Swift Security.framework helper
native/statusbar/         macOS menu bar gateway manager
docs/                     REST, MCP, architecture, and testing documentation
scripts/                  Reproducible build, run, and token setup scripts (shell and PowerShell)
```

## Development and verification

```bash
./mvnw clean verify
```

On Windows PowerShell, use `.\mvnw.cmd clean verify`.

The verification build runs Java, MCP, frontend, and tray tests, type-checks the Vue application,
builds the static assets, and packages the gateway, MCP, and tray JARs. On Windows,
`scripts/build.ps1` also creates the standalone tray app-image. Docker-backed MySQL tests run when Docker
is available. OceanBase connector compatibility should be checked against a non-production
tenant before use.

More details are in [docs/testing.md](docs/testing.md), [docs/api.md](docs/api.md),
[docs/mcp.md](docs/mcp.md), and [docs/architecture.md](docs/architecture.md).

## Contributing

Please keep changes local-first and fail-closed: do not add credential fields to AI responses,
do not log SQL or parameters, and add regression tests for every policy change. Use a local or
containerized test database, never a production credential. See [SECURITY.md](SECURITY.md) for
responsible vulnerability reporting.

## AI-assisted development

AI tools may be used to propose code or documentation, but every change must be reviewed by a
human, tested locally, and checked for credential leakage and SQL-policy regressions before it
is merged.

## License

Released under the [MIT License](LICENSE).

## Contact

Open an issue for reproducible bugs or feature requests. For security reports, follow the
private-reporting guidance in [SECURITY.md](SECURITY.md). Project profile: <https://github.com/tangguo95>.
