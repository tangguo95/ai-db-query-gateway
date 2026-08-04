package com.tangguo.gateway.datasource;

import com.tangguo.gateway.api.ApiDtos.ColumnView;
import com.tangguo.gateway.api.ApiDtos.DataSourceCreateRequest;
import com.tangguo.gateway.api.ApiDtos.DataSourceTestResult;
import com.tangguo.gateway.api.ApiDtos.DataSourceUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.DataSourceView;
import com.tangguo.gateway.api.ApiDtos.SchemaView;
import com.tangguo.gateway.api.ApiDtos.TableView;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.secret.ConnectionSecret;
import com.tangguo.gateway.secret.SecretStore;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class DataSourceService {
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "INFORMATION_SCHEMA",
            "MYSQL",
            "PERFORMANCE_SCHEMA",
            "SYS",
            "OCEANBASE",
            "DBA",
            "SYSTEM",
            "PUBLIC",
            "OUTLN",
            "XDB");

    private final DataSourceRepository repository;
    private final SecretStore secretStore;
    private final ObjectMapper objectMapper;
    private final DataSourceConnectionManager connections;
    private final ConnectorRegistry connectorRegistry;
    private final AuditService auditService;
    private final DataSourceRecoveryPolicyService recoveryPolicy;
    private final ConcurrentHashMap<String, ReentrantLock> sourceLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RecoveryAttempt> recoveryAttempts = new ConcurrentHashMap<>();

    public DataSourceService(
            DataSourceRepository repository,
            SecretStore secretStore,
            ObjectMapper objectMapper,
            DataSourceConnectionManager connections,
            ConnectorRegistry connectorRegistry,
            AuditService auditService,
            DataSourceRecoveryPolicyService recoveryPolicy) {
        this.repository = repository;
        this.secretStore = secretStore;
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.connectorRegistry = connectorRegistry;
        this.auditService = auditService;
        this.recoveryPolicy = recoveryPolicy;
    }

    public List<DataSourceView> list() {
        return repository.findAll().stream().map(this::view).toList();
    }

    public DataSourceView get(String id) {
        return view(repository.require(id));
    }

    public DataSourceConfig requireEnabled(String id) {
        DataSourceConfig config = repository.require(id);
        boolean acceptedStatus = config.readOnlyStatus() == ReadOnlyStatus.STRICT
                || config.readOnlyStatus() == ReadOnlyStatus.COMPATIBILITY;
        if (!config.enabled() || !acceptedStatus) {
            throw new GatewayException(HttpStatus.CONFLICT, "DATASOURCE_DISABLED", "数据源未通过只读安全检查");
        }
        return config;
    }

    public DataSourceView create(DataSourceCreateRequest request, String actor) {
        String id = UUID.randomUUID().toString();
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                "DATASOURCE_CREATE_REQUESTED",
                id,
                null,
                null,
                null,
                Map.of("name", request.name(), "databaseType", request.databaseType().name()),
                "REQUESTED",
                null,
                null,
                null,
                null));
        validateEndpoint(request.host(), request.port(), request.properties());
        String secretRef = "gateway.datasource." + UUID.randomUUID();
        ConnectionSecret secret = new ConnectionSecret(
                request.host().trim(),
                request.port(),
                request.database().trim(),
                request.username().trim(),
                request.password(),
                request.properties());
        putSecret(secretRef, secret);
        Instant now = Instant.now();
        DataSourceConfig config = new DataSourceConfig(
                id,
                request.name().trim(),
                request.databaseType(),
                secretRef,
                1,
                ReadOnlyStatus.UNKNOWN,
                false,
                request.allowCompatibility(),
                request.queryTimeoutSeconds() == null ? 10 : request.queryTimeoutSeconds(),
                null,
                null,
                now,
                now);
        try {
            repository.insert(config);
        } catch (DuplicateKeyException exception) {
            secretStore.delete(secretRef);
            throw new GatewayException(HttpStatus.CONFLICT, "DATASOURCE_NAME_EXISTS", "数据源名称已存在");
        } catch (RuntimeException exception) {
            secretStore.delete(secretRef);
            throw exception;
        }
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                "DATASOURCE_CREATED",
                id,
                null,
                null,
                null,
                Map.of("name", config.name(), "databaseType", config.databaseType().name()),
                "SUCCESS",
                null,
                null,
                null,
                null));
        return view(config);
    }

    public DataSourceView update(String id, DataSourceUpdateRequest request, String actor) {
        ReentrantLock lock = sourceLocks.computeIfAbsent(id, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return updateLocked(id, request, actor);
        } finally {
            lock.unlock();
        }
    }

    private DataSourceView updateLocked(String id, DataSourceUpdateRequest request, String actor) {
        DataSourceConfig current = repository.require(id);
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                "DATASOURCE_UPDATE_REQUESTED",
                id,
                null,
                null,
                null,
                Map.of("connectionChanged", request.host() != null
                        || request.port() != null
                        || request.database() != null
                        || request.username() != null
                        || request.password() != null
                        || request.properties() != null),
                "REQUESTED",
                null,
                null,
                null,
                null));
        boolean connectionChanged = request.host() != null
                || request.port() != null
                || request.database() != null
                || request.username() != null
                || request.password() != null
                || request.properties() != null;
        if (connectionChanged) {
            ConnectionSecret oldSecret = connections.secret(current);
            ConnectionSecret updatedSecret = new ConnectionSecret(
                    value(request.host(), oldSecret.host()),
                    request.port() == null ? oldSecret.port() : request.port(),
                    value(request.database(), oldSecret.database()),
                    value(request.username(), oldSecret.username()),
                    request.password() == null ? oldSecret.password() : request.password(),
                    request.properties() == null ? oldSecret.properties() : request.properties());
            validateEndpoint(updatedSecret.host(), updatedSecret.port(), updatedSecret.properties());
            // 先完整写入新的 Keychain 条目，再用 credential_version CAS 原子切换 secretRef
            // 并隔离。旧凭据发起的并发复检无法把结果回写到新版本。
            String newSecretRef = "gateway.datasource." + UUID.randomUUID();
            putSecret(newSecretRef, updatedSecret);
            boolean rotated = false;
            try {
                rotated = repository.rotateSecret(id, current.credentialVersion(), newSecretRef);
                if (!rotated) {
                    throw new GatewayException(
                            HttpStatus.CONFLICT,
                            "DATASOURCE_CONCURRENTLY_CHANGED",
                            "数据源已被其它操作修改，请刷新后重试");
                }
            } finally {
                if (!rotated) {
                    secretStore.delete(newSecretRef);
                }
            }
            connections.invalidate(id);
            secretStore.delete(current.secretRef());
            current = repository.require(id);
        }
        String name = value(request.name(), current.name()).trim();
        boolean allowCompatibility =
                request.allowCompatibility() == null ? current.allowCompatibility() : request.allowCompatibility();
        int timeout =
                request.queryTimeoutSeconds() == null ? current.queryTimeoutSeconds() : request.queryTimeoutSeconds();
        boolean requestedEnabled =
                !connectionChanged && (request.enabled() == null ? current.enabled() : request.enabled());
        if (!repository.updateBasics(
                id, current.credentialVersion(), name, allowCompatibility, timeout, requestedEnabled)) {
            throw new GatewayException(
                    HttpStatus.CONFLICT,
                    "DATASOURCE_CONCURRENTLY_CHANGED",
                    "数据源已被其它操作修改，请刷新后重试");
        }
        DataSourceConfig updated = repository.require(id);
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                connectionChanged ? "DATASOURCE_SECRET_UPDATED" : "DATASOURCE_UPDATED",
                id,
                null,
                null,
                null,
                Map.of(
                        "name",
                        name,
                        "allowCompatibility",
                        allowCompatibility,
                        "enabled",
                        updated.enabled()),
                "SUCCESS",
                null,
                null,
                null,
                null));
        return get(id);
    }

    public void delete(String id, String actor) {
        ReentrantLock lock = sourceLocks.computeIfAbsent(id, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            deleteLocked(id, actor);
        } finally {
            lock.unlock();
        }
    }

    private void deleteLocked(String id, String actor) {
        DataSourceConfig config = repository.require(id);
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                "DATASOURCE_DELETE_REQUESTED",
                id,
                null,
                null,
                null,
                Map.of("name", config.name()),
                "REQUESTED",
                null,
                null,
                null,
                null));
        repository.softDelete(id);
        connections.invalidate(id);
        secretStore.delete(config.secretRef());
        auditService.record(new AuditCommand(
                actor,
                ActorType.ADMIN,
                "DATASOURCE_DELETED",
                id,
                null,
                null,
                null,
                Map.of("name", config.name(), "databaseType", config.databaseType().name()),
                "SUCCESS",
                null,
                null,
                null,
                null));
    }

    /**
     * 数据源仅验证连接可用性，不读取数据库账号授权。成功后统一进入 COMPATIBILITY，
     * 明确表示查询安全只依赖网关 AST、executeQuery、只读事务与资源限制。
     */
    public DataSourceTestResult test(String id, String actor) {
        ReentrantLock lock = sourceLocks.computeIfAbsent(id, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            return testLocked(id, actor);
        } finally {
            lock.unlock();
        }
    }

    private DataSourceTestResult testLocked(String id, String actor) {
        DataSourceConfig config = repository.require(id);
        ActorType auditActorType = actor.startsWith("system:") ? ActorType.SYSTEM : ActorType.ADMIN;
        auditService.record(new AuditCommand(
                actor,
                auditActorType,
                "DATASOURCE_TEST_REQUESTED",
                id,
                null,
                null,
                null,
                Map.of(),
                "REQUESTED",
                null,
                null,
                null,
                null));
        if (!repository.beginInspection(id, config.credentialVersion())) {
            throw staleInspection(config, actor, auditActorType);
        }
        connections.invalidate(id);
        try (Connection connection = connections.connection(config)) {
            DatabaseMetaData metadata = connection.getMetaData();
            String databaseVersion = metadata.getDatabaseProductVersion();
            String metadataAccount = metadata.getUserName();
            String account = metadataAccount == null ? "" : metadataAccount;
            ReadOnlyStatus status = ReadOnlyStatus.COMPATIBILITY;
            List<String> findings =
                    new ArrayList<>(List.of("查询操作由网关统一执行只读控制"));
            if ("DISABLED".equals(connections.secret(config).properties().getOrDefault("tlsMode", "REQUIRED"))) {
                findings.add("TLS 已禁用，数据库链路为明文传输");
            }
            String message = "连接检查通过，查询操作由网关统一执行只读控制";
            if (!repository.updateTestResult(
                    id, config.credentialVersion(), status, message, Instant.now())) {
                connections.invalidate(id);
                throw staleInspection(config, actor, auditActorType);
            }
            boolean enabled = repository.require(id).enabled();
            auditService.record(new AuditCommand(
                    actor,
                    auditActorType,
                    "DATASOURCE_TESTED",
                    id,
                    null,
                    null,
                    null,
                    Map.of(
                            "readOnlyStatus",
                            status.name(),
                            "findings",
                            findings,
                            "account",
                            account),
                    "SUCCESS",
                    null,
                    null,
                    null,
                    null));
            return new DataSourceTestResult(
                    true,
                    status,
                    enabled,
                    databaseVersion,
                    account,
                    findings,
                    message);
        } catch (SQLException exception) {
            connections.invalidate(id);
            if (!repository.updateTestResult(
                    id,
                    config.credentialVersion(),
                    ReadOnlyStatus.UNKNOWN,
                    "数据库连接检查失败",
                    Instant.now())) {
                throw staleInspection(config, actor, auditActorType);
            }
            auditService.record(new AuditCommand(
                    actor,
                    auditActorType,
                    "DATASOURCE_TESTED",
                    id,
                    null,
                    null,
                    null,
                    Map.of(),
                    "FAILED",
                    null,
                    null,
                    null,
                    "DATABASE_CONNECTION_FAILED"));
            throw new GatewayException(
                    HttpStatus.BAD_GATEWAY,
                    "DATABASE_CONNECTION_FAILED",
                    "数据库连接检查失败",
                    exception);
        } catch (RuntimeException exception) {
            connections.invalidate(id);
            if (!repository.updateTestResult(
                    id,
                    config.credentialVersion(),
                    ReadOnlyStatus.UNKNOWN,
                    "连接复检未完成，数据源保持隔离",
                    Instant.now())) {
                throw staleInspection(config, actor, auditActorType);
            }
            auditService.record(new AuditCommand(
                    actor,
                    auditActorType,
                    "DATASOURCE_TESTED",
                    id,
                    null,
                    null,
                    null,
                    Map.of(),
                    "FAILED",
                    null,
                    null,
                    null,
                    "DATASOURCE_RECHECK_FAILED"));
            if (exception instanceof GatewayException gatewayException) {
                throw gatewayException;
            }
            throw new GatewayException(
                    HttpStatus.BAD_GATEWAY,
                    "DATASOURCE_RECHECK_FAILED",
                    "数据源连接复检未完成，已保持隔离",
                    exception);
        }
    }

    private GatewayException staleInspection(
            DataSourceConfig config, String actor, ActorType actorType) {
        auditService.record(new AuditCommand(
                actor,
                actorType,
                "DATASOURCE_TEST_STALE",
                config.id(),
                null,
                null,
                null,
                Map.of("credentialVersion", config.credentialVersion()),
                "REJECTED",
                null,
                null,
                null,
                "DATASOURCE_CREDENTIAL_CHANGED"));
        return new GatewayException(
                HttpStatus.CONFLICT,
                "DATASOURCE_CREDENTIAL_CHANGED",
                "复检期间连接凭据已变化，旧检查结果已丢弃");
    }

    @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
    public void recheckEnabledDataSources() {
        for (DataSourceConfig config : repository.findAll()) {
            if (!config.enabled()) {
                continue;
            }
            try {
                test(config.id(), "system:connection-recheck");
            } catch (RuntimeException ignored) {
                // test 已负责落审计并隔离失败数据源，定时任务继续检查其它数据源。
            }
        }
    }

    /**
     * 可选的连接恢复探测。只针对 UNKNOWN/已隔离数据源调用连接复检，不会执行或重试任何业务 SQL。
     * 失败采用内存退避，避免数据库不可用时每分钟持续建立连接并写入审计。
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void retryIsolatedDataSources() {
        if (!recoveryPolicy.autoRetryConnectionChecks()) {
            recoveryAttempts.clear();
            return;
        }
        Instant now = Instant.now();
        for (DataSourceConfig config : repository.findAll()) {
            if (config.enabled() || config.readOnlyStatus() != ReadOnlyStatus.UNKNOWN) {
                recoveryAttempts.remove(config.id());
                continue;
            }
            RecoveryAttempt attempt = recoveryAttempts.get(config.id());
            if (attempt != null && now.isBefore(attempt.nextAttemptAt())) {
                continue;
            }
            try {
                DataSourceTestResult result = test(config.id(), "system:connection-recovery");
                if (result.reachable() && result.enabled()) {
                    recoveryAttempts.remove(config.id());
                } else {
                    recordRecoveryFailure(config.id(), now);
                }
            } catch (RuntimeException ignored) {
                recordRecoveryFailure(config.id(), now);
            }
        }
    }

    private void recordRecoveryFailure(String dataSourceId, Instant now) {
        RecoveryAttempt previous = recoveryAttempts.get(dataSourceId);
        int failures = previous == null ? 1 : Math.min(previous.failures() + 1, 31);
        long multiplier = 1L << Math.min(failures - 1, 4);
        Duration delay = DataSourceRecoveryPolicyService.RETRY_INTERVAL.multipliedBy(multiplier);
        if (delay.compareTo(DataSourceRecoveryPolicyService.MAX_BACKOFF) > 0) {
            delay = DataSourceRecoveryPolicyService.MAX_BACKOFF;
        }
        recoveryAttempts.put(dataSourceId, new RecoveryAttempt(failures, now.plus(delay)));
    }

    private record RecoveryAttempt(int failures, Instant nextAttemptAt) {}

    public List<SchemaView> schemas(String id) {
        DataSourceConfig config = requireEnabled(id);
        List<SchemaView> schemas = new ArrayList<>();
        try (Connection connection = connections.connection(config)) {
            connectorRegistry.require(config.databaseType()).beginReadOnly(connection);
            try {
                try (ResultSet resultSet = connection.getMetaData().getSchemas()) {
                    while (resultSet.next()) {
                        String name = resultSet.getString("TABLE_SCHEM");
                        schemas.add(new SchemaView(name, isSystemSchema(name)));
                    }
                }
                if (schemas.isEmpty()) {
                    try (ResultSet catalogs = connection.getMetaData().getCatalogs()) {
                        while (catalogs.next()) {
                            String name = catalogs.getString(1);
                            schemas.add(new SchemaView(name, isSystemSchema(name)));
                        }
                    }
                }
                return schemas;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public List<TableView> tables(String id, String schema) {
        validateIdentifier(schema);
        DataSourceConfig config = requireEnabled(id);
        List<TableView> tables = new ArrayList<>();
        try (Connection connection = connections.connection(config)) {
            connectorRegistry.require(config.databaseType()).beginReadOnly(connection);
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                String catalog = config.databaseType() == com.tangguo.gateway.model.DatabaseType.OCEANBASE_ORACLE
                        ? null
                        : schema;
                String schemaPattern =
                        config.databaseType() == com.tangguo.gateway.model.DatabaseType.OCEANBASE_ORACLE
                                ? schema
                                : null;
                try (ResultSet resultSet =
                        metadata.getTables(catalog, schemaPattern, "%", new String[] {"TABLE", "VIEW"})) {
                    while (resultSet.next()) {
                        tables.add(new TableView(
                                value(resultSet.getString("TABLE_SCHEM"), resultSet.getString("TABLE_CAT")),
                                resultSet.getString("TABLE_NAME"),
                                resultSet.getString("TABLE_TYPE")));
                    }
                }
                return tables;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public List<ColumnView> columns(String id, String schema, String table) {
        validateIdentifier(schema);
        validateIdentifier(table);
        DataSourceConfig config = requireEnabled(id);
        List<ColumnView> columns = new ArrayList<>();
        try (Connection connection = connections.connection(config)) {
            connectorRegistry.require(config.databaseType()).beginReadOnly(connection);
            try {
                boolean oracle =
                        config.databaseType() == com.tangguo.gateway.model.DatabaseType.OCEANBASE_ORACLE;
                try (ResultSet resultSet = connection
                        .getMetaData()
                        .getColumns(oracle ? null : schema, oracle ? schema : null, table, "%")) {
                    while (resultSet.next()) {
                        columns.add(new ColumnView(
                                value(resultSet.getString("TABLE_SCHEM"), resultSet.getString("TABLE_CAT")),
                                resultSet.getString("TABLE_NAME"),
                                resultSet.getString("COLUMN_NAME"),
                                resultSet.getInt("DATA_TYPE"),
                                resultSet.getString("TYPE_NAME"),
                                resultSet.getInt("COLUMN_SIZE"),
                                resultSet.getInt("DECIMAL_DIGITS"),
                                resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                resultSet.getInt("ORDINAL_POSITION")));
                    }
                }
                return columns;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    public boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT));
    }

    private void putSecret(String secretRef, ConnectionSecret secret) {
        try {
            secretStore.put(secretRef, objectMapper.writeValueAsString(secret));
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据源凭据序列化失败", exception);
        }
    }

    private void validateEndpoint(String host, int port, Map<String, String> customProperties) {
        if (host == null
                || host.isBlank()
                || host.length() > 255
                || host.chars().anyMatch(character -> Character.isISOControl(character))
                || host.contains("/")
                || host.contains("?")
                || host.contains("#")) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_DATABASE_HOST", "数据库主机地址无效");
        }
        if (port < 1 || port > 65535) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_DATABASE_PORT", "数据库端口无效");
        }
        if (customProperties != null) {
            for (Map.Entry<String, String> entry : customProperties.entrySet()) {
                String key = entry.getKey();
                if (key == null
                        || !"tlsMode".equals(key)
                        || entry.getValue() == null
                        || !Set.of("VERIFY_IDENTITY", "REQUIRED", "DISABLED").contains(entry.getValue())) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST,
                            "INVALID_CONNECTION_PROPERTY",
                            "v1 仅允许 tlsMode=VERIFY_IDENTITY|REQUIRED|DISABLED");
                }
            }
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST, "UNSAFE_DATABASE_HOST", "禁止连接任意地址、组播或链路本地地址");
                }
            }
        } catch (UnknownHostException exception) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "DATABASE_HOST_UNRESOLVED", "数据库主机无法解析");
        }
    }

    private void validateIdentifier(String identifier) {
        if (identifier == null
                || identifier.isBlank()
                || identifier.length() > 128
                || identifier.chars().anyMatch(Character::isISOControl)) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER", "数据库对象标识无效");
        }
    }

    private GatewayException databaseFailure(SQLException exception) {
        return new GatewayException(
                HttpStatus.BAD_GATEWAY, "DATABASE_METADATA_FAILED", "读取数据库元数据失败", exception);
    }

    private DataSourceView view(DataSourceConfig config) {
        return new DataSourceView(
                config.id(),
                config.name(),
                config.databaseType(),
                config.readOnlyStatus(),
                config.enabled(),
                config.allowCompatibility(),
                config.queryTimeoutSeconds(),
                config.lastTestedAt(),
                config.lastTestMessage(),
                config.createdAt(),
                config.updatedAt());
    }

    private String value(String candidate, String fallback) {
        return candidate == null ? fallback : candidate;
    }
}
