# AI DB Query Gateway

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[中文说明](README_ZH.md)

AI DB Query Gateway is a local-first, audited query gateway for using AI clients with
MySQL and OceanBase databases. A browser console stores data-source credentials locally,
the REST API applies the query policy, and a small MCP STDIO adapter exposes only the
approved read-query tools to Codex, Claude, or another local AI client.

The project is designed for a single macOS workstation. It replaces copying database
addresses and passwords into every AI conversation with one local security boundary.

![Login screen](docs/images/login.png)

## What it does

- Supports MySQL, OceanBase MySQL mode, and OceanBase Oracle mode through a connector SPI.
- Keeps database credentials and audit encryption keys in macOS Keychain; SQLite stores
  metadata, scope information, and the audit chain rather than plaintext credentials.
- Lets an administrator manage data sources, approvals, scoped API tokens, and audit events
  from the web console.
- Exposes eight fixed MCP tools: list data sources, inspect schemas/tables, describe a table,
  execute a read query, inspect a query request, execute an approved query, and cancel.
- Does not expose JDBC URLs, hosts, usernames, passwords, Keychain references, token
  management, or audit management to AI clients.
- Applies an AST-based SQL policy before opening a business-database connection. Only one
  `SELECT`, `UNION`, or non-recursive CTE whose final statement is a query is accepted.
- Rejects writes, DDL, transaction/session control, comments and hints, multi-statements,
  locks, file operations, database links, recursive CTEs, and unsupported functions.
- Enforces bounded rows, response bytes, field bytes, timeouts, concurrency, rate limits,
  one-time approvals, cancellation, and rollback.
- Never writes query results to SQLite, audit records, ordinary logs, CSV files, or Excel files.
  For administrator review, the service keeps at most 20 recent results in process memory for
  up to 15 minutes; they disappear on restart or expiry.

## Read-only boundary

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
                                      ├─ macOS Keychain secret references
                                      └─ bounded JDBC pools ──> MySQL / OceanBase
```

The service listens on `127.0.0.1:8765` by default. It is not a database TCP proxy and it
does not provide remote HTTP MCP. Non-loopback binding requires explicit remote mode and
Spring TLS configuration.

## Requirements

- macOS with Keychain access
- Java 21
- Xcode Command Line Tools (to build the Swift Keychain helper)
- Docker Desktop is optional and useful for MySQL integration tests
- Internet access on the first build so Maven and the pinned Node/npm runtime can be downloaded

The build downloads Node `22.14.0` and npm `10.9.2` into an ignored project directory; a
system-wide Node installation is not required.

## Quick start

```bash
git clone https://github.com/tangguo95/ai-db-query-gateway.git
cd ai-db-query-gateway
./scripts/build.sh
./scripts/launchd.sh start
```

Open <http://127.0.0.1:8765>. On the first launch the service creates a one-time
`bootstrap-token` file at:

```text
~/Library/Application Support/AI DB Query Gateway/bootstrap-token
```

Read the token locally and enter it in the setup page to choose the local administrator
password. The token is deleted after successful setup and the password is stored only as an
Argon2id hash.

The default runtime directory is:

```text
~/Library/Application Support/AI DB Query Gateway/
```

It contains the SQLite control database and bootstrap state. Database credentials and audit
keys are stored separately in Keychain under the application service; they are not put in the
repository, process arguments, environment variables, or ordinary logs.

The launchd command is a manually loaded user service, not a login shortcut. It stays running
after the terminal is closed and restarts after an unexpected crash, but it is not loaded after
macOS login or reboot. Use these commands when needed:

```bash
./scripts/launchd.sh status
./scripts/launchd.sh stop
```

For foreground troubleshooting, use `./scripts/run.sh` instead. The foreground command is tied
to its terminal and will stop when that terminal session ends.

### macOS menu bar manager

Build and open the optional menu bar utility:

```bash
./native/statusbar/build.sh
open "native/statusbar/build/AI DB Query Gateway.app"
```

The utility exposes the launchd service status, start/stop/restart actions, the web console, and
service logs. It does not enable login startup and does not handle database credentials.

## Connect an AI client with MCP

1. Add and test a data source in the web console.
2. Create a short-lived token and select only the data sources the client needs. Confirm the
   result-sharing warning before saving it.
3. Store the token in the local macOS Keychain helper:

   ```bash
   ./scripts/configure-mcp-token.sh
   ```

4. Start the MCP adapter in a separate terminal or from the client configuration:

   ```bash
   ./scripts/run-mcp.sh
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
README. The token is shown only once in the web console. Its data-source scope can later be
updated without issuing a new token, so an existing MCP setup does not need to change.

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
same-user macOS shell boundary, the limits of local chained-HMAC audit protection, cancellation
semantics, TLS choices, and the risk that an AI provider may receive raw query results.

## Project layout

```text
gateway-server/          Spring Boot API, policy, JDBC execution, audit, and static hosting
gateway-mcp/              MCP 2024-11-05 STDIO adapter without database drivers
frontend/                 Vue 3 + TypeScript web console
native/macos-keychain/    Swift Security.framework helper
native/statusbar/         macOS menu bar gateway manager
docs/                     REST, MCP, architecture, and testing documentation
scripts/                  Reproducible build, run, and token setup scripts
```

## Development and verification

```bash
./mvnw clean verify
```

The verification build runs Java, MCP, and frontend tests, type-checks the Vue application,
builds the static assets, and packages the two JARs. Docker-backed MySQL tests run when Docker
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
