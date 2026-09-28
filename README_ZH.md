# AI 数据库查询网关

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![许可证：MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md)

这是一个本地优先、带审计能力的 AI 数据库查询与 Linux 服务器管理网关。数据库侧支持
MySQL、DRDS 和 OceanBase 只读查询；服务器侧通过 SSH 执行受控命令。管理员在网页中
管理连接和授权，本地 MCP STDIO 适配器向 AI 客户端提供固定的数据库与服务器工具。

当前版本面向单台 macOS 或 Windows 电脑，把数据库凭据、SSH 密码或私钥、查询策略、
服务器权限、审批和审计收敛到本机，避免在每次 AI 会话中粘贴连接凭据。

## 功能概览

- 支持 MySQL、DRDS（MySQL 兼容，Connector/J 5.1）、OceanBase MySQL 模式和 OceanBase Oracle 模式，并通过连接器 SPI 预留扩展点。
- 数据库凭据、SSH 密码或私钥及审计加密密钥保存在 macOS Keychain 或 Windows 当前用户 DPAPI 保护的本地密文文件；
  SQLite 保存元数据、作用域和审计链，不保存明文凭据。
- 网页端管理数据源、Linux 服务器、审批、作用域令牌和审计轨迹。
- MCP 提供八个数据库工具及两个服务器工具。数据库工具包括：列出数据源、查看 Schema/表、查看表结构、执行查询、查看查询
  请求、执行已批准查询和取消查询。
- MCP 不暴露保存的连接信息、认证材料、Keychain 引用或管理接口；远程命令输出由 SSH 账号的可访问范围决定。
- 在获取业务库连接前使用 AST 执行 SQL 策略，只允许单条 `SELECT`、`UNION` 或最终为查询的
  非递归 CTE。
- 数据库查询拒绝写操作、DDL、事务和会话控制、普通注释、未允许的 Hint、多语句、锁、文件操作、数据库链接、
  递归 CTE 和未支持函数。
- 安全设置中可查看并搜索默认函数，按数据源添加或移除专用函数；按数据库类型管理无参数
  MATERIALIZE、INLINE、MERGE、NO_MERGE Hint。Hint 默认未启用，保存立即生效，修改写入审计。
- 数据库查询限制行数、响应大小、字段大小、超时和并发，支持一次性审批、取消和回滚；服务器命令另有执行限制。
- 查询结果不写入 SQLite、审计、普通日志，也不提供 CSV/Excel 导出；管理员可在查询执行后
  短时间内从审计轨迹回看最近结果。该回看仅保存在进程内，最多保留 20 条、15 分钟，重启或
  到期后自动清除。

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

网关有意不检查数据库账号权限。连接成功的数据源会标记为 `COMPATIBILITY`，每条查询都
必须经过网关的只读执行链。这属于应用层控制，不等同于数据库权限层面的绝对只读。连接
生产库时仍建议使用只授予必要 `SELECT` 权限的数据库账号。

## 架构

```text
网页控制台 ── Session + CSRF ──┐
                              v
AI 客户端 ── STDIO ── MCP ──令牌──> Spring Boot 网关
                                  │
                                  ├─ 登录认证和令牌作用域
                                  ├─ SQL AST 策略和风险审批
                                  ├─ SQLite 控制面与链式 HMAC 审计
                                  ├─ macOS Keychain / Windows DPAPI 凭据引用
                                  ├─ 有界 JDBC 连接池 ──> MySQL / DRDS / OceanBase
                                  └─ SSH 命令执行 ──> Linux（直连 / 单级跳板机）
```

服务默认监听 `127.0.0.1:8765`，不是数据库 TCP 代理，也不提供远程 HTTP MCP。非回环监听
必须显式开启远程模式并配置 Spring TLS。

## 环境要求

- macOS（可访问 Keychain）或 Windows 10/11（当前用户 DPAPI）
- Java 21
- Xcode Command Line Tools（仅 macOS 编译 Swift Keychain helper）
- Docker Desktop（可选，用于 MySQL 集成测试）
- 首次构建时可访问 Maven Central、Node.js 和 npm registry

构建会把固定版本的 Node `22.14.0` 和 npm `10.9.2` 下载到工程的忽略目录，不要求系统预装
Node。

## 快速开始

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

打开 <http://127.0.0.1:8765>。第一次启动时，服务会创建一次性文件：

macOS 路径为 `~/Library/Application Support/AI DB Query Gateway/bootstrap-token`；Windows
路径为 `%LOCALAPPDATA%\AI DB Query Gateway\bootstrap-token`。也可以通过
`GATEWAY_DATA_DIR` 指定运行目录。

只在本机读取该文件，将令牌输入初始化页面并设置本地管理员密码。初始化成功后文件会删除，
密码只保存为 Argon2id 摘要。

默认运行目录为：

```text
macOS:   ~/Library/Application Support/AI DB Query Gateway/
Windows: %LOCALAPPDATA%\AI DB Query Gateway\
```

其中包含 SQLite 控制库和初始化状态。数据库凭据与审计密钥独立保存在平台安全存储中，不会
进入仓库、进程参数、环境变量或普通日志。

`launchd` 这里是手动加载的用户服务，不是登录快捷方式：关闭终端后服务仍会运行，异常退出会自动
重启；但它不会在登录 macOS 或重启电脑后自动加载。按需使用以下命令：

```bash
./scripts/launchd.sh status
./scripts/launchd.sh stop
./scripts/launchd.sh restart
```

如果需要前台排查日志，可以使用 `./scripts/run.sh`。前台命令依赖当前终端，终端会话结束后服务也会停止。

Windows 前台排查使用：

```powershell
.\scripts\run.ps1
```

Windows 后台服务的状态、停止和重启：

```powershell
.\scripts\windows-service.ps1 status
.\scripts\windows-service.ps1 stop
.\scripts\windows-service.ps1 restart
```

### Windows 系统托盘应用

`.\scripts\build.ps1` 会同时构建独立的 Windows 托盘应用。启动它：

```powershell
.\scripts\run-tray.ps1
```

托盘应用每五秒刷新一次网关健康状态，并提供启动、停止、重启网关、打开 Web 控制台和打开日志目录
等操作。生成的可执行文件位于
`gateway-tray\build\app-image\AI DB Query Gateway Tray\`，目录内包含独立 Java 运行时和网关服务 JAR。

### macOS 菜单栏管理工具

构建并打开可选的顶部状态栏小工具：

```bash
./native/statusbar/build.sh
open "native/statusbar/build/AI DB Query Gateway.app"
```

小工具可以查看 launchd 服务状态、启动/停止/重启网关、打开管理页面和服务日志；不会开启登录自启动，
也不会处理数据库凭据。

## 使用 MCP 连接 AI

1. 在网页中新增并测试数据源或 Linux 服务器。
2. 创建短期令牌，只勾选客户端需要的数据源和服务器，并确认结果可能发送给 AI 的提示。
3. 在终端把令牌保存到本机安全存储。macOS 使用 Keychain：

   ```bash
   ./scripts/configure-mcp-token.sh
   ```

   Windows PowerShell 使用当前用户 DPAPI：

   ```powershell
   .\scripts\configure-mcp-token.ps1
   ```

4. 启动 MCP 适配器：

   ```bash
   ./scripts/run-mcp.sh
   ```

   Windows PowerShell：

   ```powershell
   .\scripts\run-mcp.ps1
   ```

如果客户端自行管理 MCP 子进程，可使用生成的 JAR，并把令牌放在客户端私有配置中：

```json
{
  "command": "java",
  "args": ["-jar", "/absolute/path/to/gateway-mcp/target/gateway-mcp.jar"],
  "env": {
    "AI_DB_GATEWAY_URL": "http://127.0.0.1:8765",
    "AI_DB_GATEWAY_TOKEN": "<作用域令牌>"
  }
}
```

不要把该配置提交到仓库，也不要把有效令牌粘贴到 Issue、聊天、命令行参数或 README。网页只
显示一次令牌；之后可以直接调整令牌的数据源和服务器范围，不需要重新生成令牌或重新配置 MCP。

## 常见查询方式

可以让 AI 先列出数据源，再查看目标 Schema/表结构，然后执行带参数的 `SELECT`，同时说明
查询用途和行数上限。例如：

```text
使用“订单查询库”，先查看 order_info 的字段；然后按订单号 ? 查询该订单的状态，
用途是核对工单 123，最多返回 20 行。不要执行任何写操作。
```

系统 Schema、跨 Schema、超过三个表、`SELECT *` 或超过 200 行等风险请求会进入待审批状态，
需要在网页中批准后才能一次性执行。

## 限制与安全说明

默认允许 200 行，硬上限 1,000 行；SQL 上限 32 KiB，响应上限 5 MiB，单字段上限 256 KiB；
数据源超时 5–30 秒；每个数据源最多并发 2 条、全局最多 4 条；每个令牌每分钟最多 30 次。
这些是服务端限制，客户端不能提高。

连接生产库前请阅读 [SECURITY.md](SECURITY.md)，其中说明了同一操作系统用户的 Shell 边界、
本地链式 HMAC 审计的能力范围、取消语义、TLS 选择，以及 AI 服务商可能接收到原始查询结果的
风险。

## 工程结构

```text
gateway-server/          Spring Boot API、策略、JDBC / SSH 执行、审计和静态资源托管
gateway-mcp/              不包含数据库驱动的 MCP 2024-11-05 STDIO 适配器
gateway-tray/             Windows 系统托盘应用和 jpackage 打包输入
frontend/                 Vue 3 + TypeScript 网页控制台
native/macos-keychain/    Swift Security.framework helper
native/statusbar/         macOS 菜单栏网关管理工具
docs/                     REST、MCP、架构和测试文档
scripts/                  可复现构建、启动和令牌配置脚本（含 macOS shell / Windows PowerShell）
```

## 开发与验证

```bash
./mvnw clean verify
```

Windows PowerShell 等价命令：

```powershell
.\mvnw.cmd clean verify
```

该命令会运行 Java、MCP、前端和托盘应用测试，执行 Vue 类型检查和生产构建，并打包网关、MCP
和托盘应用 JAR。Windows 的 `scripts\build.ps1` 还会生成独立的托盘 app-image。Docker 可用时
会运行 MySQL 测试；OceanBase 驱动兼容性应先在非生产租户验证。

更多信息请查看 [docs/testing.md](docs/testing.md)、[docs/api.md](docs/api.md)、
[docs/mcp.md](docs/mcp.md) 和 [docs/architecture.md](docs/architecture.md)。

## 贡献

请保持本项目本地优先并默认拒绝：不要向 AI 响应添加凭据字段，不要记录 SQL 或参数，策略变更
必须增加回归测试。只使用本地或容器测试库，禁止使用生产凭据。漏洞报告方式见 [SECURITY.md](SECURITY.md)。

## AI 辅助开发

可以使用 AI 工具提出代码或文档建议，但每项变更都必须经过人工审查、本地测试，并检查凭据泄露
和 SQL 策略回归后再合并。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。

## 联系方式

可通过 Issue 提交可复现的 Bug 或功能建议。安全问题请按照 [SECURITY.md](SECURITY.md) 的私下
报告说明处理。项目主页：<https://github.com/tangguo95>。
