# AI 数据库查询网关

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![许可证：MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md)

这是一个本地优先、带审计能力的 AI 数据库查询、Linux 服务器管理与 Kubernetes 只读查询网关。数据库侧支持
MySQL、DRDS 和 OceanBase 只读查询；服务器侧通过 SSH 执行受控命令。管理员在网页中
管理连接和授权，本地 MCP STDIO 适配器向 AI 客户端提供固定的数据库与服务器工具。

当前版本面向单台 macOS 或 Windows 电脑，把数据库凭据、SSH 密码或私钥、查询策略、
服务器权限、审批和审计收敛到本机，避免在每次 AI 会话中粘贴连接凭据。

使用、修改或二次开发本项目前，请阅读[免责声明](#免责声明)。

## 功能概览

- 本机免登录：打开网页直接进入控制台，使用固定的本地管理员身份；MCP 仍使用独立的作用域令牌。
- 支持 MySQL、DRDS（MySQL 兼容，Connector/J 5.1）、OceanBase MySQL 模式和 OceanBase Oracle 模式，并通过连接器 SPI 预留扩展点。
- 数据库凭据、SSH 密码或私钥及审计加密密钥保存在 macOS Keychain 或 Windows 当前用户 DPAPI 保护的本地密文文件；
  SQLite 保存元数据、作用域和审计链，不保存明文凭据。
- 网页端管理数据源、Linux 服务器、审批、作用域令牌和审计轨迹。
- MCP 提供八个数据库工具、两个服务器工具及四个 Kubernetes 工具。数据库工具包括：列出数据源、查看 Schema/表、查看表结构、执行查询、查看查询
  请求、执行已批准查询和取消查询。
- MCP 不暴露保存的连接信息、认证材料、Keychain 引用或管理接口；远程命令输出由 SSH 账号的可访问范围决定。
- 在获取业务库连接前使用 AST 执行 SQL 策略，只允许单条 `SELECT`、`UNION` 或最终为查询的
  非递归 CTE。
- 数据库查询拒绝写操作、DDL、事务和会话控制语句、普通注释、多语句、锁查询、SELECT INTO/OUTFILE、数据库链接及递归 CTE。
- 查询中的所有内置函数、自定义函数及带 Schema 或包名的函数均不再按名称限制，无需配置函数白名单。
  函数能否执行及其行为由目标数据库和账号权限决定，网关不再按函数名判断副作用。
- 查询中的所有 `/*+ ... */` 优化器 Hint 均不再按名称或参数白名单限制，包括 USE_NL、LEADING、INDEX、MATERIALIZE、PARALLEL 及组合和嵌套参数，无需按数据库类型开启。
  提示内容原样传递，是否支持及如何生效由目标数据库决定；网关仍检查提示之外的 SQL 结构和绑定参数。
- 安全设置中可查看并搜索常用函数参考，并查看函数和 Hint 的放行范围。
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
4. 点击服务器的“使用笔记”，记录工程名称、日志路径和常用查看方式；可独立保存，无需重新测试连接，也不改变服务器权限。
5. 在已连接网关的 MCP 客户端中，让 AI 调用 `list_servers` 获取服务器 ID 和 `notes` 笔记，再调用 `execute_server_command` 执行命令并填写用途。新会话读取笔记后，可直接按工程名称查找日志，无需重复提供目录。

每台服务器支持最多 8000 字的多行笔记，留空保存可清除。笔记随当前令牌获授权且已启用的服务器返回给 AI，
请勿填写密码、私钥或 Token；它仅提供路径和查看方式参考，命令仍受原有查询模式或完整权限限制。

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

## Kubernetes 只读查询

网页“Kubernetes”可导入 kubeconfig 文件或粘贴内容，选择 context，并填写允许查询的命名空间
（逗号分隔；`*` 表示全部）。测试连接后保存，再在“访问令牌”中勾选集群；旧令牌不会自动获得集群权限。
同一个 MCP 服务新增 `list_kubernetes_clusters`、`get_kubernetes_resources`、
`get_kubernetes_pod_logs`、`query_kubernetes_container` 四个工具。

- 支持 Pod、Deployment、StatefulSet、DaemonSet、Service、ConfigMap、Ingress、Job、PVC、事件等常见资源的 YAML；列表默认 50 条，最多 100 条，使用返回的继续标识手动翻页。
- Pod 日志通过只读日志 API 获取，无需进入容器；默认最近 1 小时、500 行，最多 24 小时、2000 行和 256 KiB，不持续跟随。
- 容器内查询默认关闭，可在集群配置中单独开启。采用单次非交互 exec，只接受 `ls`、`cat`、`head`、`tail`、`ps`、`df`、`id`、`uname`、`pwd` 的有限参数，命令按参数数组传递，不经 Shell。读取文件要求绝对路径，不开放 `/dev`、`/proc`、`/sys`。
- 不提供 apply、delete、patch、扩缩容、重启、复制文件、交互式终端、代理或任意 API 路径。当前资源列表不包含 Secret 和自定义资源（CRD），也没有完整权限开关。
- 仅支持 HTTPS 及 kubeconfig 中的内嵌 Token/客户端证书认证；不执行 `exec`、`auth-provider` 插件，不读取本机证书或 Token 文件，不使用代理配置。规范化后的配置上限 24 KiB，只保留选定 context，修改其他 context 时需重新导入。

需在运行网关的电脑上已有与目标集群兼容的 `kubectl`。默认查找 Docker Desktop、Homebrew 和
PATH 中的客户端；也可通过 `--gateway.kubernetes.kubectl-path=/absolute/path/to/kubectl` 指定。
凭据持久化在 Keychain/DPAPI，调用时使用仅当前用户可读的临时文件，并在调用结束后清理。
执行用途、目标和参数进入审计，查询结果不持久化；资源 YAML 和日志本身仍可能包含敏感信息。

网关同时限制集群授权、命名空间、每集群每秒一次查询、单集群并发 1、全局并发 2、最长 20 秒等待及
1 MiB 输出，不自动重试。超时会结束本机 kubectl，但无法保证已创建的容器进程停止。
推荐使用仅有必要 get/list 及 pods/log 权限的 kubeconfig；容器 exec 需要额外授权。
Kubernetes 的 exec 权限不能限制具体命令，因此容器内只读属于网关的应用层限制，依赖容器中工具的行为，
不保证绝对无副作用，也不保证平台不会记录或触发告警。详见 [Kubernetes RBAC 文档](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)。

## 数据库只读边界

网关有意不检查数据库账号权限。连接成功的数据源会标记为 `COMPATIBILITY`，每条查询都
必须经过网关的只读执行链。这属于应用层控制，不等同于数据库权限层面的绝对只读。连接
生产库时仍建议使用只授予必要 `SELECT` 权限的数据库账号。

## 架构

```text
网页控制台 ── 本机身份 + CSRF ──┐
                              v
AI 客户端 ── STDIO ── MCP ──令牌──> Spring Boot 网关
                                  │
                                  ├─ 本机身份和令牌作用域
                                  ├─ SQL AST 策略和风险审批
                                  ├─ SQLite 控制面与链式 HMAC 审计
                                  ├─ macOS Keychain / Windows DPAPI 凭据引用
                                  ├─ 有界 JDBC 连接池 ──> MySQL / DRDS / OceanBase
                                  ├─ SSH 命令执行 ──> Linux（直连 / 单级跳板机）
                                  └─ kubectl 只读查询 / 受限 exec ──> Kubernetes
```

服务默认监听 `127.0.0.1:8765`，不是数据库 TCP 代理，也不提供远程 HTTP MCP。非回环监听及远程模式会被拒绝。

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

打开 <http://127.0.0.1:8765> 即可进入控制台，使用固定的“本地管理员”身份，无需初始化密码或登录。
首次访问自动建立 CSRF cookie；写请求仍受 CSRF、Host/Origin 校验和审计保护。
MCP 仍需作用域令牌，网页免登录不会扩大已有令牌的数据库或服务器授权。
本机免登录意味着信任能访问本机控制台的程序，不用于远程部署，也不支持远程监听。
旧的 `/login`、`/setup` 页面地址会自动跳转控制台；升级沿用现有连接、令牌和审计数据。

默认运行目录为：

```text
macOS:   ~/Library/Application Support/AI DB Query Gateway/
Windows: %LOCALAPPDATA%\AI DB Query Gateway\
```

也可以通过 `GATEWAY_DATA_DIR` 指定运行目录。

其中包含 SQLite 控制库、连接配置和审计元数据。数据库凭据与审计密钥独立保存在平台安全存储中，不会
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

如果桌面客户端启动时找不到 Java，请将 `command` 改为本机 Java 21 可执行文件的绝对路径。

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

下面的验证命令会重新生成 `target/` 中的 JAR。网关已运行时，请先停止服务再执行，
完成后重新启动，避免覆盖正在使用的 JAR 导致页面或类加载异常。macOS 使用
`./scripts/launchd.sh stop` / `start`，Windows 使用 `.\scripts\windows-service.ps1 stop` / `start`。
仅需编译和运行测试、不重新打包时，可使用 `./mvnw test`（Windows：`.\mvnw.cmd test`）。

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

## 免责声明

本项目按“现状”提供，不就其适销性、特定用途适用性、非侵权性或持续可用性作出任何明示或暗示担保。
查询模式、权限控制和审计等功能不构成对操作安全、数据完整性或运行结果的保证。

使用者应自行评估本项目及其衍生版本是否适用于实际环境，并负责必要的测试、数据备份与恢复准备、
权限配置和操作确认。使用者应仅在获得合法授权的数据库和服务器上使用本项目。

对于因使用本项目，或基于本项目进行修改、二次开发、集成、部署、分发及使用相关衍生版本而产生
或与之相关的数据库数据丢失、损坏、泄露、服务器故障、业务中断、经济损失等，在适用法律允许的
最大范围内，相关风险和损失由使用者自行承担，项目作者、版权持有人及贡献者不承担相应赔偿责任。

本声明是对 [MIT 许可证](LICENSE) 中无担保及责任限制条款的说明，不改变许可证的授权范围，
也不排除或限制适用法律不得排除或限制的责任。

## 许可证

本项目采用 [MIT License](LICENSE) 开源。

## 联系方式

可通过 Issue 提交可复现的 Bug 或功能建议。安全问题请按照 [SECURITY.md](SECURITY.md) 的私下
报告说明处理。项目主页：<https://github.com/tangguo95>。
