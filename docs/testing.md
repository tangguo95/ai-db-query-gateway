# 测试与验收

## 本地自动验证

macOS：

```bash
./scripts/build.sh
```

Windows PowerShell：

```powershell
.\scripts\build.ps1
```

等价核心命令：

macOS/Linux shell：

```bash
./mvnw clean verify
```

Windows PowerShell：

```powershell
.\mvnw.cmd clean verify
```

它会：

1. 在 macOS 编译 Swift Keychain helper；Windows 使用内置 DPAPI，不需要原生 helper。
2. 下载固定 Node 22.14.0/npm 10.9.2。
3. 执行 `npm ci`、Vue/TypeScript 生产构建和 Vitest。
4. 执行后端策略、审计、仓储竞态、JDBC URL 与 MySQL 8.4 容器测试。
5. 执行 MCP 握手、Schema、并发、中断补偿取消和 HTTP 安全测试。
6. 生成网关服务 JAR、MCP JAR 和 Windows 托盘应用 app-image（Windows JDK 环境）。

只重跑模块：

```bash
./mvnw -pl gateway-server test -Dskip.npm -Dskip.installnodenpm
./mvnw -pl gateway-mcp test
```

Windows PowerShell 中请把带点的 Maven 参数整体加引号：

```powershell
.\mvnw.cmd -pl gateway-server '-Dskip.npm=true' '-Dskip.installnodenpm=true' test
.\mvnw.cmd -pl gateway-mcp test
```

MySQL 集成测试固定使用 `mysql:8.4.6`。本机 Docker 可用时会验证真实连接、SELECT、
数据库端拒绝只读账号 INSERT，并保留历史权限分类器的回归用例；运行时数据源接入
不再调用该权限分类器。Docker 不可用时容器测试会明确标记为跳过。

## 平台安全存储自检

macOS 使用虚构值在 helper 中依次 `put/get/delete`，并确认最终删除。不要用生产账号做
helper 测试。Java 侧还会验证 helper 不是符号链接、owner 为当前用户/root、组和其他用户
不可写，并在每次调用前核对启动时 SHA-256。

Windows 测试会真实调用当前用户 DPAPI，验证密文文件、读回、删除和篡改拒绝。密文保存在
运行目录下的 `secrets/`，文件名为账户摘要，不包含数据库地址、用户名或 Token。

## 本机烟测

非生产烟测可使用临时目录和内存 SecretStore：

```bash
SMOKE_DIR="$(mktemp -d)"
GATEWAY_DATA_DIR="${SMOKE_DIR}" \
GATEWAY_ALLOW_IN_MEMORY_SECRETS=true \
GATEWAY_PORT=18765 \
java -jar gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar
```

仅用于验证启动、静态页面、CSRF、setup/login、Token、Host/Origin 拒绝和审计链；内存
SecretStore 禁止连接生产库。完成后停止进程并删除刚创建的明确临时目录。

Windows 可使用当前用户 DPAPI 直接烟测，不需要 `GATEWAY_ALLOW_IN_MEMORY_SECRETS=true`：

```powershell
$env:GATEWAY_DATA_DIR = Join-Path $env:TEMP 'ai-db-query-gateway-smoke'
$env:GATEWAY_PORT = '18765'
.\scripts\run.ps1
```

Windows 托盘应用可单独构建和启动：

```powershell
.\scripts\build-tray.ps1
.\scripts\run-tray.ps1
```

托盘应用通过 `/actuator/health` 验证运行状态，服务控制菜单复用
`scripts/windows-service.ps1`；jpackage 生成的 app-image 会把服务 JAR、脚本和 Java 运行时放在同一目录。

## 数据库集成验收

在真实生产接入前，至少用一次性非生产账号逐项验证：

- MySQL 8.4：验证真实连接、元数据、受控 SELECT 和只读事务。
- OceanBase MySQL：验证 OceanBase CE 对应版本、官方/兼容驱动、元数据和只读事务。
- OceanBase Oracle：验证一次性 Oracle 模式租户、元数据和 `SET TRANSACTION READ ONLY`；
  不要求账号能够访问权限视图。
- 双方言：普通 SELECT、类型化参数、CTE/UNION、系统 Schema 审批、超时、取消、
  1,000 行与 5 MiB/256 KiB 截断。

OceanBase Oracle 不适合用 mock 代替驱动兼容验收。若当前机器没有可用租户，应把该项
明确记录为“未执行”，不能仅凭单元测试宣称三种模式已经通过真实数据库验证。

## 安全验收清单

- 仓库、`gateway.db`、进程参数和日志不含数据库明文凭据或 Token。
- `bootstrap-token` 为 `0600`，初始化后不存在。
- 非查询 SQL 在业务库连接前失败。
- Token 不能访问未授权数据源或任何 `/api/datasources` 管理能力。
- CSRF、恶意 Host/Origin、无 Bearer AI 请求和超限请求被拒绝并有审计事件。
- 查询结果只在响应出现；审计页不显示完整 SQL、参数或结果。
- 修改任意历史审计 HMAC 后，校验失败且后续操作 fail-closed。
- 取消与执行终态不会互相覆盖；取消失败会隔离物理连接池。
- 非回环地址在未同时显式开启远程模式和 TLS 时拒绝启动。

## 当前测试边界

自动测试不能证明数据库厂商所有版本和代理层行为。上线前仍需在目标版本的非生产实例
执行上述集成验收，并优先换成数据库级只读账号。
