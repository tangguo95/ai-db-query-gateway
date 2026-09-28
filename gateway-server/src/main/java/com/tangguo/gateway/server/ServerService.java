package com.tangguo.gateway.server;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.secret.SecretStore;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.server.ServerDtos.AccessRequest;
import com.tangguo.gateway.server.ServerDtos.Connection;
import com.tangguo.gateway.server.ServerDtos.ConnectionTestResult;
import com.tangguo.gateway.server.ServerDtos.Endpoint;
import com.tangguo.gateway.server.ServerDtos.ExecuteRequest;
import com.tangguo.gateway.server.ServerDtos.ExecuteResult;
import com.tangguo.gateway.server.ServerDtos.SaveRequest;
import com.tangguo.gateway.server.ServerDtos.ServerView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ServerService {
    private final JdbcTemplate jdbc;
    private final SecretStore secrets;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final ActorContext actor;
    private final SshExecutor ssh;
    private final ReadOnlyCommandPolicy policy;
    private final Semaphore capacity = new Semaphore(4);
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public ServerService(JdbcTemplate jdbc, SecretStore secrets, ObjectMapper mapper, AuditService audit,
                         ActorContext actor, SshExecutor ssh, ReadOnlyCommandPolicy policy) {
        this.jdbc = jdbc; this.secrets = secrets; this.mapper = mapper; this.audit = audit;
        this.actor = actor; this.ssh = ssh; this.policy = policy;
    }

    public List<ServerView> list() {
        return jdbc.query("SELECT * FROM server_config ORDER BY name", (rs, n) -> new ServerView(
                rs.getString("id"), rs.getString("name"), rs.getBoolean("enabled"), rs.getBoolean("full_access"),
                rs.getString("last_test_message"), rs.getString("updated_at")));
    }

    public ServerView get(String id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "SERVER_NOT_FOUND", "服务器不存在"));
    }

    public Connection configuration(String id) {
        Connection connection = storedConnection(id);
        if (connection == null) return new Connection(new Endpoint("", 22, "", "PASSWORD", null, null, null, ""), null);
        return new Connection(publicEndpoint(connection.target()), publicEndpoint(connection.jump()));
    }

    private Endpoint publicEndpoint(Endpoint value) {
        return value == null ? null : new Endpoint(value.host(), value.port(), value.username(), value.authType(),
                null, null, null, value.fingerprint());
    }

    /** 先使用本次提交的凭据验证连接，再保存；连接失败不新增记录或覆盖原配置。 */
    public ServerView save(String id, SaveRequest request) {
        boolean creating = id == null;
        if (creating) id = UUID.randomUUID().toString();
        lock(id);
        try {
            Connection updated = resolveConnection(creating ? null : id, request);
            event("SERVER_SAVE_REQUESTED", "REQUESTED", Map.of("serverId", id, "name", request.name()));
            verifyConnection(updated);
            String reference = "gateway.server." + id;
            if (creating) {
                secrets.put(reference, mapper.writeValueAsString(updated));
                try {
                    jdbc.update("INSERT INTO server_config(id,name,secret_ref,enabled,last_test_message,updated_at) VALUES(?,?,?,1,?,?)",
                            id, request.name(), reference, "SSH 连接及命令通道验证通过", Instant.now().toString());
                } catch (RuntimeException exception) {
                    // 元数据未创建成功时，不遗留本次新建的认证材料。
                    secrets.delete(reference);
                    throw exception;
                }
            } else {
                jdbc.update("UPDATE server_config SET name=?, enabled=0, full_access=0, last_test_message=NULL, updated_at=? WHERE id=?",
                        request.name(), Instant.now().toString(), id);
                secrets.put(reference, mapper.writeValueAsString(updated));
                jdbc.update("UPDATE server_config SET enabled=1,last_test_message=? WHERE id=?",
                        "SSH 连接及命令通道验证通过", id);
            }
            event("SERVER_SAVED", "SUCCESS", Map.of("serverId", id, "name", request.name()));
            return get(id);
        } finally { busy.remove(id); }
    }

    /** 配置框中的连接测试不保存配置或凭据，也不改变现有服务器的启用状态。 */
    public ConnectionTestResult testConfiguration(String id, SaveRequest request) {
        String operationId = id == null ? UUID.randomUUID().toString() : id;
        lock(operationId);
        try {
            Connection updated = resolveConnection(id, request);
            event("SERVER_TEST_REQUESTED", "REQUESTED", Map.of("serverId", operationId, "name", request.name()));
            verifyConnection(updated);
            event("SERVER_TESTED", "SUCCESS", Map.of("serverId", operationId, "name", request.name()));
            return new ConnectionTestResult(true, "连接测试通过，可以确认保存");
        } catch (GatewayException exception) {
            event("SERVER_TESTED", "FAILED", Map.of("serverId", operationId, "errorCode", exception.code()));
            throw exception;
        } finally { busy.remove(operationId); }
    }

    private Connection resolveConnection(String id, SaveRequest request) {
        Connection old = id == null ? null : storedConnection(id);
        Connection updated = request.connection() == null ? old : new Connection(
                merge(request.connection().target(), old == null ? null : old.target()),
                request.connection().jump() == null ? null : merge(request.connection().jump(), old == null ? null : old.jump()));
        if (updated == null) throw bad("请填写 SSH 连接信息");
        validate(updated.target());
        if (updated.jump() != null) validate(updated.jump());
        return updated;
    }

    private void verifyConnection(Connection connection) {
        if (!capacity.tryAcquire()) throw busyError();
        try {
            ExecuteResult result = ssh.execute(connection, "printf gateway-ssh-ok", 5, true);
            if (!"EXECUTED".equals(result.status()) || !"gateway-ssh-ok".equals(result.stdout())) {
                throw new GatewayException(HttpStatus.BAD_GATEWAY, "SSH_CONNECTION_TEST_FAILED",
                        "SSH 命令通道验证未通过，配置未保存。请检查账号是否允许执行命令。");
            }
        } finally { capacity.release(); }
    }

    public void delete(String id) {
        lock(id);
        try {
            ServerView server = get(id);
            String reference = jdbc.queryForObject("SELECT secret_ref FROM server_config WHERE id=?", String.class, id);
            event("SERVER_DELETE_REQUESTED", "REQUESTED", Map.of("serverId", id, "serverName", server.name()));
            // 先停用再删除凭据；中途失败时保留停用配置，允许管理员重试清理。
            jdbc.update("UPDATE server_config SET enabled=0 WHERE id=?", id);
            secrets.delete(reference);
            String permission = "server:execute:" + id;
            jdbc.update("""
                    UPDATE api_token SET permissions = (
                        SELECT json_group_array(value) FROM json_each(api_token.permissions) WHERE value <> ?
                    ) WHERE EXISTS (
                        SELECT 1 FROM json_each(api_token.permissions) WHERE value = ?
                    )
                    """, permission, permission);
            jdbc.update("DELETE FROM server_config WHERE id=?", id);
            event("SERVER_DELETED", "SUCCESS", Map.of("serverId", id, "serverName", server.name()));
        } finally { busy.remove(id); }
    }

    public ServerView access(String id, AccessRequest request) {
        lock(id);
        try {
            get(id);
            if (request.fullAccess() && !Boolean.TRUE.equals(request.confirmFullAccess())) throw bad("开启完整权限前必须确认允许 AI 执行任意命令");
            event("SERVER_ACCESS_CHANGE_REQUESTED", "REQUESTED", Map.of("serverId", id, "fullAccess", request.fullAccess()));
            jdbc.update("UPDATE server_config SET full_access=?, updated_at=? WHERE id=?",
                    request.fullAccess(), Instant.now().toString(), id);
            event("SERVER_ACCESS_CHANGED", "SUCCESS", Map.of("serverId", id, "fullAccess", request.fullAccess()));
            return get(id);
        } finally { busy.remove(id); }
    }

    public ServerView disable(String id) {
        lock(id);
        try {
            get(id);
            event("SERVER_DISABLE_REQUESTED", "REQUESTED", Map.of("serverId", id));
            jdbc.update("UPDATE server_config SET enabled=0, updated_at=? WHERE id=?", Instant.now().toString(), id);
            event("SERVER_DISABLED", "SUCCESS", Map.of("serverId", id));
            return get(id);
        } finally { busy.remove(id); }
    }

    public ServerView test(String id) {
        lock(id);
        boolean acquired = false;
        try {
            get(id);
            acquired = capacity.tryAcquire();
            if (!acquired) throw busyError();
            event("SERVER_TEST_REQUESTED", "REQUESTED", Map.of("serverId", id));
            jdbc.update("UPDATE server_config SET enabled=0 WHERE id=?", id);
            ExecuteResult result = ssh.execute(connection(id), "printf gateway-ssh-ok", 5, true);
            boolean ok = "EXECUTED".equals(result.status()) && "gateway-ssh-ok".equals(result.stdout());
            String message = ok ? "SSH 连接及命令通道验证通过" : result.message();
            jdbc.update("UPDATE server_config SET enabled=?, last_test_message=?, updated_at=? WHERE id=?",
                    ok, message, Instant.now().toString(), id);
            event("SERVER_TESTED", ok ? "SUCCESS" : "FAILED", Map.of("serverId", id));
            return get(id);
        } catch (GatewayException exception) {
            jdbc.update("UPDATE server_config SET enabled=0, last_test_message=?, updated_at=? WHERE id=?",
                    exception.getMessage(), Instant.now().toString(), id);
            event("SERVER_TESTED", "FAILED", Map.of("serverId", id, "errorCode", exception.code()));
            throw exception;
        } finally {
            if (acquired) capacity.release();
            busy.remove(id);
        }
    }

    public ExecuteResult execute(ExecuteRequest request) {
        String id = request.serverId();
        if (actor.isAi() && !actor.requireToken().permitsServer(id)) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "SERVER_SCOPE_DENIED", "令牌没有该服务器的访问权限");
        }
        lock(id);
        boolean acquired = false;
        boolean dispatched = false;
        String executionId = UUID.randomUUID().toString();
        try {
            ServerView server = get(id);
            if (!server.enabled()) throw bad("服务器未启用，请先通过 SSH 连接测试");
            event("SERVER_COMMAND_REQUESTED", "REQUESTED", Map.of("serverId", id, "serverName", server.name(),
                    "executionId", executionId, "command", request.command(), "purpose", request.purpose(), "fullAccess", server.fullAccess()));
            String command = policy.prepare(request.command(), server.fullAccess());
            acquired = capacity.tryAcquire();
            if (!acquired) throw busyError();
            dispatched = true;
            ExecuteResult result = ssh.execute(connection(id), command,
                    request.timeoutSeconds() == null ? 15 : request.timeoutSeconds(), false);
            event("SERVER_COMMAND_FINISHED", result.status(), Map.of("serverId", id, "executionId", executionId,
                    "durationMs", result.durationMs(), "truncated", result.truncated()));
            return new ExecuteResult(executionId, result.status(), result.exitCode(), result.stdout(), result.stderr(),
                    result.truncated(), result.durationMs(), result.message());
        } catch (GatewayException exception) {
            // 远程操作不能回滚；事后审计失败不能表述为“命令未执行”，也不能再次写失败的审计链。
            if (dispatched && exception.code().startsWith("AUDIT_")) {
                throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_AUDIT_FINALIZE_FAILED",
                        "远程命令已提交，但结果审计写入失败。请先核对远程状态，不要自动重试。");
            }
            event("SERVER_COMMAND_REJECTED", "FAILED", Map.of("serverId", id, "executionId", executionId, "errorCode", exception.code()));
            throw exception;
        } finally {
            if (acquired) capacity.release();
            busy.remove(id);
        }
    }

    private Connection connection(String id) {
        Connection connection = storedConnection(id);
        if (connection == null) throw bad("服务器凭据不可用，请重新填写并保存");
        return connection;
    }

    private Connection storedConnection(String id) {
        get(id);
        String reference = jdbc.queryForObject("SELECT secret_ref FROM server_config WHERE id=?", String.class, id);
        return secrets.get(reference).map(value -> mapper.readValue(value, Connection.class)).orElse(null);
    }

    private Endpoint merge(Endpoint next, Endpoint old) {
        if (next == null) throw bad("请填写目标服务器");
        boolean reuse = old != null && next.authType().equals(old.authType());
        return new Endpoint(next.host().trim(), next.port(), next.username().trim(), next.authType(),
                "PASSWORD".equals(next.authType()) ? secret(next.password(), reuse ? old.password() : null) : null,
                "PRIVATE_KEY".equals(next.authType()) ? secret(next.privateKey(), reuse ? old.privateKey() : null) : null,
                "PRIVATE_KEY".equals(next.authType()) ? (next.privateKey() != null && !next.privateKey().isBlank()
                        ? next.passphrase() : secret(next.passphrase(), reuse ? old.passphrase() : null)) : null,
                next.fingerprint() == null ? "" : next.fingerprint().trim());
    }

    private String secret(String next, String old) { return next == null || next.isEmpty() ? old : next; }
    private void validate(Endpoint endpoint) {
        if (!endpoint.host().matches("[a-zA-Z0-9.:%_-]+") || endpoint.host().startsWith("-")) throw bad("主机地址格式无效");
        if ("PASSWORD".equals(endpoint.authType()) && (endpoint.password() == null || endpoint.password().isEmpty())) throw bad("请填写 SSH 密码");
        if ("PRIVATE_KEY".equals(endpoint.authType()) && (endpoint.privateKey() == null || endpoint.privateKey().isBlank())) throw bad("请填写 SSH 私钥");
    }

    private void lock(String id) { if (!busy.add(id)) throw busyError(); }
    private GatewayException busyError() { return new GatewayException(HttpStatus.CONFLICT, "SERVER_BUSY", "服务器正在执行操作或已达并发上限，请稍后重试"); }
    private GatewayException bad(String message) { return new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_SERVER_REQUEST", message); }
    private void event(String type, String status, Map<String, Object> detail) {
        audit.record(new AuditCommand(actor.actor(), actor.actorType(), type, null, null,
                "服务器 " + detail.getOrDefault("serverName", detail.get("serverId"))
                        + (detail.containsKey("purpose") ? "：" + detail.get("purpose") : ""),
                null, detail, status, null, null, null, null));
    }
}
