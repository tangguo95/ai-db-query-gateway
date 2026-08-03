package com.tangguo.gateway.query;

import com.tangguo.gateway.api.ApiDtos.QueryColumn;
import com.tangguo.gateway.api.ApiDtos.QueryCreateRequest;
import com.tangguo.gateway.api.ApiDtos.QueryParameter;
import com.tangguo.gateway.api.ApiDtos.QueryPreview;
import com.tangguo.gateway.api.ApiDtos.QueryResult;
import com.tangguo.gateway.api.ApiDtos.QueryView;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditCryptoService;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.ConnectorRegistry;
import com.tangguo.gateway.datasource.DataSourceConfig;
import com.tangguo.gateway.datasource.DataSourceConnectionManager;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.QueryStatus;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.security.TokenContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class QueryService {
    private static final String TRUNCATED_MARKER = "…[TRUNCATED]";
    private static final int MAX_PARAMETER_TOTAL_BYTES = 1024 * 1024;
    private static final int RESPONSE_ENVELOPE_RESERVE_BYTES = 64 * 1024;
    private static final int MAX_RESULT_COLUMNS = 512;
    private static final int MAX_METADATA_TEXT_BYTES = 4096;
    private static final int MAX_RECENT_RESULTS = 20;
    private static final Duration RECENT_RESULT_TTL = Duration.ofMinutes(15);
    private static final Set<String> PARAMETER_TYPES = Set.of(
            "STRING",
            "VARCHAR",
            "INTEGER",
            "INT",
            "LONG",
            "BIGINT",
            "DECIMAL",
            "NUMERIC",
            "DOUBLE",
            "BOOLEAN",
            "DATE",
            "TIME",
            "TIMESTAMP",
            "NULL");

    private final DataSourceService dataSourceService;
    private final DataSourceConnectionManager connections;
    private final ConnectorRegistry connectors;
    private final SqlPolicyService sqlPolicy;
    private final QueryRepository queryRepository;
    private final AuditCryptoService crypto;
    private final AuditService auditService;
    private final ActorContext actorContext;
    private final GatewayProperties properties;
    private final QueryApprovalPolicyService approvalPolicy;
    private final ObjectMapper objectMapper;
    private final Semaphore globalSemaphore;
    private final ConcurrentHashMap<String, Semaphore> dataSourceSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PreparedStatement> runningStatements = new ConcurrentHashMap<>();
    private final Set<String> cancelledQueries = ConcurrentHashMap.newKeySet();
    /**
     * 查询结果只在进程内短暂保留，便于管理员从审计轨迹回看刚执行的结果；不写入 SQLite、日志或审计密文。
     */
    private final Map<String, RecentResult> recentResults = new LinkedHashMap<>(32, .75f, true);

    public QueryService(
            DataSourceService dataSourceService,
            DataSourceConnectionManager connections,
            ConnectorRegistry connectors,
            SqlPolicyService sqlPolicy,
            QueryRepository queryRepository,
            AuditCryptoService crypto,
            AuditService auditService,
            ActorContext actorContext,
            GatewayProperties properties,
            QueryApprovalPolicyService approvalPolicy,
            ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.connections = connections;
        this.connectors = connectors;
        this.sqlPolicy = sqlPolicy;
        this.queryRepository = queryRepository;
        this.crypto = crypto;
        this.auditService = auditService;
        this.actorContext = actorContext;
        this.properties = properties;
        this.approvalPolicy = approvalPolicy;
        this.objectMapper = objectMapper;
        this.globalSemaphore = new Semaphore(properties.getQuery().getGlobalConcurrency(), true);
    }

    public QueryPreview preview(QueryCreateRequest request) {
        String actor = actorContext.actor();
        ActorType actorType = actorContext.actorType();
        List<QueryParameter> parameters = request.parameters() == null ? List.of() : request.parameters();
        int requestedMaxRows =
                request.maxRows() == null ? properties.getQuery().getDefaultMaxRows() : request.maxRows();
        int effectiveMaxRows = Math.min(requestedMaxRows, properties.getQuery().getHardMaxRows());
        String previewId = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();

        // 预检同样采用同步先写审计；SQL 与参数只存在于将被审计服务加密的敏感载荷中。
        auditService.record(new AuditCommand(
                actor,
                actorType,
                "QUERY_PREVIEW_REQUESTED",
                request.dataSourceId(),
                previewId,
                request.purpose(),
                null,
                Map.of(
                        "sql", request.sql(),
                        "parameters", parameters,
                        "maxRows", requestedMaxRows),
                "REQUESTED",
                null,
                null,
                null,
                null));

        DataSourceConfig dataSource = null;
        SqlAnalysis analysis = null;
        try {
            if (actorType != ActorType.ADMIN) {
                throw new GatewayException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "仅管理员可预检查询");
            }
            dataSource = dataSourceService.requireEnabled(request.dataSourceId());
            validateParameters(parameters);
            analysis = sqlPolicy.analyze(dataSource.databaseType(), request.sql(), requestedMaxRows);
            if (analysis.parameterCount() != parameters.size()) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "PARAMETER_COUNT_MISMATCH", "SQL 占位符数量与参数数量不一致");
            }
        } catch (GatewayException exception) {
            recordPreviewRejection(actor, actorType, request, previewId, analysis, exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            String errorCode = "QUERY_PREVIEW_FAILED";
            recordPreviewRejection(actor, actorType, request, previewId, analysis, errorCode);
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE, errorCode, "查询预检失败，请稍后重试", exception);
        }

        QueryPreview preview = new QueryPreview(
                dataSource.id(),
                dataSource.readOnlyStatus(),
                analysis.schemas().stream().sorted().toList(),
                analysis.tables().stream().sorted().toList(),
                List.copyOf(analysis.riskReasons()),
                effectiveMaxRows,
                analysis.parameterCount(),
                analysis.fingerprint());
        auditService.record(new AuditCommand(
                actor,
                actorType,
                "QUERY_PREVIEW_SUCCEEDED",
                dataSource.id(),
                previewId,
                request.purpose(),
                analysis.fingerprint(),
                Map.of(
                        "schemas", preview.schemas(),
                        "tables", preview.tables(),
                        "riskReasons", preview.riskReasons(),
                        "readOnlyStatus", preview.readOnlyStatus().name(),
                        "effectiveMaxRows", preview.effectiveMaxRows(),
                        "parameterCount", preview.parameterCount()),
                "SUCCESS",
                null,
                null,
                null,
                null));
        return preview;
    }

    public QueryView create(QueryCreateRequest request) {
        String actor = actorContext.actor();
        ActorType actorType = actorContext.actorType();
        if (actorType == ActorType.API_TOKEN) {
            assertTokenScope(request.dataSourceId());
        }
        DataSourceConfig dataSource = dataSourceService.requireEnabled(request.dataSourceId());
        List<QueryParameter> parameters = request.parameters() == null ? List.of() : request.parameters();
        int requestedMaxRows =
                request.maxRows() == null ? properties.getQuery().getDefaultMaxRows() : request.maxRows();
        int effectiveMaxRows = Math.min(requestedMaxRows, properties.getQuery().getHardMaxRows());
        String queryId = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();

        try {
            validateParameters(parameters);
        } catch (GatewayException exception) {
            auditService.record(new AuditCommand(
                    actor,
                    actorType,
                    "QUERY_REQUEST_REJECTED",
                    dataSource.id(),
                    queryId,
                    request.purpose(),
                    null,
                    Map.of("reason", exception.code()),
                    "REJECTED",
                    null,
                    null,
                    null,
                    exception.code()));
            throw exception;
        }

        // 必须在任何 SQL 解析和数据库连接之前预写原始请求审计，写入失败直接拒绝。
        auditService.record(new AuditCommand(
                actor,
                actorType,
                "QUERY_REQUESTED",
                dataSource.id(),
                queryId,
                request.purpose(),
                null,
                Map.of(
                        "sql",
                        request.sql(),
                        "parameters",
                        parameters,
                        "maxRows",
                        requestedMaxRows,
                        "readOnlyStatus",
                        dataSource.readOnlyStatus().name()),
                "REQUESTED",
                null,
                null,
                null,
                null));

        SqlAnalysis analysis;
        try {
            analysis = sqlPolicy.analyze(dataSource.databaseType(), request.sql(), requestedMaxRows);
            if (analysis.parameterCount() != parameters.size()) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "PARAMETER_COUNT_MISMATCH", "SQL 占位符数量与参数数量不一致");
            }
        } catch (GatewayException exception) {
            auditService.record(new AuditCommand(
                    actor,
                    actorType,
                    "QUERY_POLICY_REJECTED",
                    dataSource.id(),
                    queryId,
                    request.purpose(),
                    null,
                    Map.of("reason", exception.code()),
                    "REJECTED",
                    null,
                    null,
                    null,
                    exception.code()));
            throw exception;
        }

        boolean highRiskApiQuery = actorType == ActorType.API_TOKEN && !analysis.riskReasons().isEmpty();
        boolean approvalRequired = highRiskApiQuery && approvalPolicy.approvalRequired();
        boolean approvalBypassed = highRiskApiQuery && !approvalRequired;
        QueryStatus initialStatus = approvalRequired ? QueryStatus.PENDING_APPROVAL : QueryStatus.APPROVED;
        Instant now = Instant.now();
        StoredQuery stored = new StoredQuery(
                queryId,
                actor,
                actorType,
                dataSource.id(),
                crypto.encrypt(request.sql()),
                crypto.encrypt(writeJson(parameters)),
                analysis.fingerprint(),
                request.purpose(),
                requestedMaxRows,
                effectiveMaxRows,
                initialStatus,
                analysis.riskReasons(),
                approvalRequired ? null : now.plus(properties.getQuery().getApprovalTtl()),
                approvalRequired ? null : now,
                null,
                null,
                now,
                now);
        queryRepository.insert(stored);
        auditService.record(new AuditCommand(
                actor,
                actorType,
                approvalRequired
                        ? "QUERY_PENDING_APPROVAL"
                        : approvalBypassed ? "QUERY_POLICY_AUTO_APPROVED" : "QUERY_POLICY_APPROVED",
                dataSource.id(),
                queryId,
                request.purpose(),
                analysis.fingerprint(),
                Map.of(
                        "tables",
                        analysis.tables(),
                        "schemas",
                        analysis.schemas(),
                        "riskReasons",
                        analysis.riskReasons(),
                        "readOnlyStatus",
                        dataSource.readOnlyStatus().name(),
                        "approvalRequired",
                        approvalRequired,
                        "approvalBypassed",
                        approvalBypassed),
                approvalRequired ? "PENDING_APPROVAL" : "APPROVED",
                null,
                null,
                null,
                null));
        if (approvalRequired) {
            return view(stored, null);
        }
        return execute(queryId);
    }

    private void recordPreviewRejection(
            String actor,
            ActorType actorType,
            QueryCreateRequest request,
            String previewId,
            SqlAnalysis analysis,
            String errorCode) {
        auditService.record(new AuditCommand(
                actor,
                actorType,
                "QUERY_PREVIEW_REJECTED",
                request.dataSourceId(),
                previewId,
                request.purpose(),
                analysis == null ? null : analysis.fingerprint(),
                Map.of("reason", errorCode),
                "REJECTED",
                null,
                null,
                null,
                errorCode));
    }

    public QueryView get(String queryId) {
        StoredQuery query = requireAccessible(queryId);
        if (query.status() == QueryStatus.APPROVED
                && query.approvalExpiresAt() != null
                && !query.approvalExpiresAt().isAfter(Instant.now())) {
            queryRepository.updateStatus(query.id(), QueryStatus.EXPIRED, "APPROVAL_EXPIRED");
            query = queryRepository.require(query.id());
        }
        return view(query, null);
    }

    public QueryView getResultForAudit(String queryId) {
        if (actorContext.actorType() != ActorType.ADMIN) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "仅网页管理员可查看审计查询结果");
        }
        StoredQuery query = queryRepository.require(queryId);
        // 审计查看只对网页管理员开放；允许在这里解密原始 SQL，便于把结果与审批内容对应起来。
        return view(query, recentResult(queryId), true);
    }

    public List<QueryView> list(QueryStatus status, int limit) {
        if (actorContext.actorType() != ActorType.ADMIN) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "仅管理员可查看查询列表");
        }
        return queryRepository.find(status, limit).stream().map(query -> view(query, null)).toList();
    }

    public QueryView approve(String queryId) {
        StoredQuery query = queryRepository.require(queryId);
        if (actorContext.actorType() != ActorType.ADMIN) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "ADMIN_APPROVAL_REQUIRED", "风险查询只能由网页管理员审批");
        }
        auditService.record(new AuditCommand(
                actorContext.actor(),
                ActorType.ADMIN,
                "QUERY_APPROVAL_REQUESTED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                "REQUESTED",
                null,
                null,
                null,
                null));
        Instant now = Instant.now();
        Instant expires = now.plus(properties.getQuery().getApprovalTtl());
        if (!queryRepository.approve(queryId, now, expires)) {
            throw new GatewayException(HttpStatus.CONFLICT, "QUERY_NOT_PENDING", "查询当前不处于待审批状态");
        }
        auditService.record(new AuditCommand(
                actorContext.actor(),
                ActorType.ADMIN,
                "QUERY_APPROVED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of("approvalExpiresAt", expires.toString(), "riskReasons", query.riskReasons()),
                "APPROVED",
                null,
                null,
                null,
                null));
        return view(queryRepository.require(queryId), null);
    }

    public QueryView execute(String queryId) {
        StoredQuery query = requireAccessible(queryId);
        if (query.status() != QueryStatus.APPROVED) {
            throw new GatewayException(HttpStatus.CONFLICT, "QUERY_NOT_APPROVED", "查询尚未批准或已经执行");
        }
        if (query.approvalExpiresAt() == null || !query.approvalExpiresAt().isAfter(Instant.now())) {
            queryRepository.updateStatus(query.id(), QueryStatus.EXPIRED, "APPROVAL_EXPIRED");
            throw new GatewayException(HttpStatus.GONE, "APPROVAL_EXPIRED", "一次性审批已过期");
        }
        if (!queryRepository.consume(query.id(), Instant.now())) {
            throw new GatewayException(HttpStatus.CONFLICT, "APPROVAL_ALREADY_CONSUMED", "一次性审批已经使用");
        }
        try {
            auditService.record(new AuditCommand(
                    actorContext.actor(),
                    actorContext.actorType(),
                    "QUERY_EXECUTION_STARTED",
                    query.dataSourceId(),
                    query.id(),
                    query.purpose(),
                    query.sqlFingerprint(),
                    Map.of(),
                    "EXECUTING",
                    null,
                    null,
                    null,
                    null));
            String sql = crypto.decrypt(query.sqlCipher());
            List<QueryParameter> parameters = readParameters(crypto.decrypt(query.parametersCipher()));
            QueryResult result = executeJdbc(query, sql, parameters);
            return view(queryRepository.require(query.id()), result);
        } catch (GatewayException exception) {
            markUnexpectedExecutionFailure(query, exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            String code = "QUERY_EXECUTION_INTERNAL_ERROR";
            markUnexpectedExecutionFailure(query, code);
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE, code, "查询执行链暂不可用", exception);
        }
    }

    public QueryView cancel(String queryId) {
        StoredQuery query = requireAccessible(queryId);
        if (Set.of(
                        QueryStatus.EXECUTED,
                        QueryStatus.REJECTED,
                        QueryStatus.EXPIRED,
                        QueryStatus.FAILED,
                        QueryStatus.CANCELLED,
                        QueryStatus.TIMED_OUT)
                .contains(query.status())) {
            throw new GatewayException(HttpStatus.CONFLICT, "QUERY_ALREADY_TERMINAL", "查询已经处于终态");
        }
        auditService.record(new AuditCommand(
                actorContext.actor(),
                actorContext.actorType(),
                "QUERY_CANCEL_REQUESTED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                "REQUESTED",
                null,
                null,
                null,
                null));
        cancelledQueries.add(queryId);
        QueryRepository.CancelOutcome cancelOutcome =
                queryRepository.cancelActive(queryId, Instant.now());
        if (cancelOutcome == QueryRepository.CancelOutcome.NONE) {
            cancelledQueries.remove(queryId);
            throw new GatewayException(HttpStatus.CONFLICT, "QUERY_NOT_CANCELLABLE", "查询当前无法取消");
        }
        PreparedStatement statement = runningStatements.get(queryId);
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException exception) {
                // Hikari Connection.close() 只会归还代理连接；关闭整个小型数据源池才能
                // 确保取消失败的物理连接不会继续在后台执行。
                connections.invalidate(query.dataSourceId());
            }
        }
        boolean wasExecuting = cancelOutcome == QueryRepository.CancelOutcome.EXECUTING;
        if (!wasExecuting) {
            cancelledQueries.remove(queryId);
        }
        auditService.record(new AuditCommand(
                actorContext.actor(),
                actorContext.actorType(),
                wasExecuting ? "QUERY_CANCEL_ACCEPTED" : "QUERY_CANCELLED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                "CANCELLED",
                null,
                null,
                null,
                "QUERY_CANCELLED"));
        return view(queryRepository.require(queryId), null);
    }

    private QueryResult executeJdbc(StoredQuery query, String sql, List<QueryParameter> parameters) {
        if (cancelledQueries.contains(query.id())) {
            cancelledQueries.remove(query.id());
            throw new GatewayException(HttpStatus.CONFLICT, "QUERY_CANCELLED", "查询已取消");
        }
        DataSourceConfig dataSource = dataSourceService.requireEnabled(query.dataSourceId());
        Semaphore sourceSemaphore = dataSourceSemaphores.computeIfAbsent(
                dataSource.id(),
                ignored -> new Semaphore(properties.getQuery().getPerDataSourceConcurrency(), true));
        if (!globalSemaphore.tryAcquire()) {
            failBeforeExecution(query, "GLOBAL_CONCURRENCY_LIMIT");
        }
        boolean sourceAcquired = sourceSemaphore.tryAcquire();
        if (!sourceAcquired) {
            globalSemaphore.release();
            failBeforeExecution(query, "DATASOURCE_CONCURRENCY_LIMIT");
        }
        long started = System.nanoTime();
        try (Connection connection = connections.connection(dataSource)) {
            throwIfCancelled(query.id());
            connectors.require(dataSource.databaseType()).beginReadOnly(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                runningStatements.put(query.id(), statement);
                throwIfCancelled(query.id());
                statement.setQueryTimeout(dataSource.queryTimeoutSeconds());
                statement.setFetchSize(Math.min(query.effectiveMaxRows() + 1, 500));
                statement.setMaxRows(query.effectiveMaxRows() + 1);
                bind(statement, parameters);
                QueryResult result;
                boolean resourceCancelFailed = false;
                try (ResultSet resultSet = statement.executeQuery()) {
                    result = readResult(
                            resultSet,
                            query.id(),
                            query.effectiveMaxRows(),
                            Math.max(
                                    0,
                                    properties.getQuery().getMaxResponseBytes()
                                            - RESPONSE_ENVELOPE_RESERVE_BYTES),
                            properties.getQuery().getMaxFieldBytes(),
                            started);
                    if (result.truncated()) {
                        try {
                            statement.cancel();
                        } catch (SQLException exception) {
                            resourceCancelFailed = true;
                        }
                    }
                }
                connection.rollback();
                if (resourceCancelFailed) {
                    connections.invalidate(query.dataSourceId());
                }
                throwIfCancelled(query.id());
                if (!queryRepository.completeExecuted(query.id())) {
                    throw new QueryCancelledSQLException();
                }
                auditService.record(new AuditCommand(
                        query.actor(),
                        query.actorType(),
                        "QUERY_EXECUTED",
                        query.dataSourceId(),
                        query.id(),
                        query.purpose(),
                        query.sqlFingerprint(),
                        Map.of(
                                "truncated",
                                result.truncated(),
                                "readOnlyStatus",
                                dataSource.readOnlyStatus().name()),
                        "EXECUTED",
                        result.durationMs(),
                        result.rowCount(),
                        result.byteCount(),
                        null));
                rememberResult(query.id(), result);
                return result;
            } finally {
                runningStatements.remove(query.id());
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // 只读查询结束后尽力回滚；连接异常时 Hikari 会回收连接。
                }
            }
        } catch (SQLTimeoutException exception) {
            return throwExecutionFailure(query, QueryStatus.TIMED_OUT, "QUERY_TIMEOUT", started, exception);
        } catch (SQLException exception) {
            String code = cancelledQueries.contains(query.id()) ? "QUERY_CANCELLED" : "DATABASE_QUERY_FAILED";
            QueryStatus status =
                    cancelledQueries.contains(query.id()) ? QueryStatus.CANCELLED : QueryStatus.FAILED;
            return throwExecutionFailure(query, status, code, started, exception);
        } finally {
            cancelledQueries.remove(query.id());
            sourceSemaphore.release();
            globalSemaphore.release();
        }
    }

    private QueryResult throwExecutionFailure(
            StoredQuery query, QueryStatus status, String code, long started, SQLException cause) {
        long duration = elapsedMillis(started);
        queryRepository.failIfExecuting(query.id(), status, code);
        auditService.record(new AuditCommand(
                query.actor(),
                query.actorType(),
                status == QueryStatus.CANCELLED ? "QUERY_CANCELLED" : "QUERY_FAILED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                status.name(),
                duration,
                null,
                null,
                code));
        HttpStatus httpStatus = status == QueryStatus.CANCELLED
                ? HttpStatus.CONFLICT
                : status == QueryStatus.TIMED_OUT ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        throw new GatewayException(httpStatus, code, "数据库查询未完成", cause);
    }

    private void failBeforeExecution(StoredQuery query, String code) {
        if (!queryRepository.failIfExecuting(query.id(), QueryStatus.FAILED, code)) {
            StoredQuery current = queryRepository.require(query.id());
            if (current.status() == QueryStatus.CANCELLED) {
                throw new GatewayException(HttpStatus.CONFLICT, "QUERY_CANCELLED", "查询已取消");
            }
            throw new GatewayException(
                    HttpStatus.CONFLICT, "QUERY_STATE_CHANGED", "查询状态已变化，未进入数据库执行");
        }
        auditService.record(new AuditCommand(
                query.actor(),
                query.actorType(),
                "QUERY_REJECTED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                "REJECTED",
                null,
                null,
                null,
                code));
        throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, code, "查询并发已达到安全上限");
    }

    QueryResult readResult(
            ResultSet resultSet,
            String queryId,
            int maxRows,
            long maxBytes,
            int maxFieldBytes,
            long started)
            throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        if (metadata.getColumnCount() > MAX_RESULT_COLUMNS) {
            throw new SQLException("result contains too many columns");
        }
        List<QueryColumn> columns = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            columns.add(new QueryColumn(
                    metadataText(metadata.getColumnLabel(index)),
                    metadataText(metadata.getColumnName(index)),
                    metadata.getColumnType(index),
                    metadataText(metadata.getColumnTypeName(index)),
                    metadata.isNullable(index) != ResultSetMetaData.columnNoNulls));
        }
        List<List<Object>> rows = new ArrayList<>();
        long bytes = estimateColumnBytes(columns) + 2;
        boolean truncated = false;
        if (bytes > maxBytes) {
            throw new SQLException("result metadata exceeds response limit");
        }
        resultRows:
        while (resultSet.next()) {
            throwIfCancelled(queryId);
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columns.size());
            long rowBytes = 2;
            for (int index = 1; index <= columns.size(); index++) {
                ConvertedValue converted =
                        safeValue(resultSet, index, columns.get(index - 1).jdbcType(), maxFieldBytes);
                long fieldBytes = estimateBytes(converted.value());
                if (bytes + rowBytes + fieldBytes > maxBytes) {
                    truncated = true;
                    break resultRows;
                }
                row.add(converted.value());
                rowBytes += fieldBytes;
                truncated |= converted.truncated();
            }
            bytes += rowBytes;
            rows.add(row);
        }
        return new QueryResult(
                List.copyOf(columns), List.copyOf(rows), truncated, elapsedMillis(started), rows.size(), bytes);
    }

    private ConvertedValue safeValue(ResultSet resultSet, int index, int jdbcType, int maxFieldBytes)
            throws SQLException {
        if (Set.of(
                        Types.BINARY,
                        Types.VARBINARY,
                        Types.LONGVARBINARY,
                        Types.BLOB)
                .contains(jdbcType)) {
            return readBinary(resultSet.getBinaryStream(index), maxFieldBytes);
        }
        if (Set.of(
                        Types.CHAR,
                        Types.VARCHAR,
                        Types.LONGVARCHAR,
                        Types.NCHAR,
                        Types.NVARCHAR,
                        Types.LONGNVARCHAR,
                        Types.CLOB,
                        Types.NCLOB)
                .contains(jdbcType)) {
            return readText(resultSet.getCharacterStream(index), maxFieldBytes);
        }

        Object value = resultSet.getObject(index);
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return new ConvertedValue(value, false);
        }
        if (value instanceof byte[] bytes) {
            return encodeBinary(bytes, bytes.length > binaryRawBudget(maxFieldBytes), maxFieldBytes);
        }
        if (value instanceof Blob blob) {
            return readBinary(blob.getBinaryStream(), maxFieldBytes);
        }
        if (value instanceof Clob clob) {
            return readText(clob.getCharacterStream(), maxFieldBytes);
        }
        String text = value instanceof TemporalAccessor ? value.toString() : String.valueOf(value);
        return convertText(text, maxFieldBytes, false);
    }

    private ConvertedValue readBinary(InputStream input, int maxFieldBytes) throws SQLException {
        if (input == null) {
            return new ConvertedValue(null, false);
        }
        int rawBudget = binaryRawBudget(maxFieldBytes);
        try (input) {
            byte[] bytes = input.readNBytes(rawBudget + 1);
            boolean truncated = bytes.length > rawBudget;
            return encodeBinary(bytes, truncated, maxFieldBytes);
        } catch (IOException exception) {
            throw new SQLException("binary result field could not be read safely", exception);
        }
    }

    private ConvertedValue encodeBinary(byte[] bytes, boolean sourceTruncated, int maxFieldBytes) {
        int rawBudget = binaryRawBudget(maxFieldBytes);
        byte[] safeBytes = bytes.length > rawBudget ? Arrays.copyOf(bytes, rawBudget) : bytes;
        String encoded = Base64.getEncoder().encodeToString(safeBytes);
        return convertText(encoded, maxFieldBytes, sourceTruncated || bytes.length > rawBudget);
    }

    private int binaryRawBudget(int maxFieldBytes) {
        int markerBytes = TRUNCATED_MARKER.getBytes(StandardCharsets.UTF_8).length;
        int encodedBudget = Math.max(0, maxFieldBytes - markerBytes);
        return (encodedBudget / 4) * 3;
    }

    private ConvertedValue readText(Reader reader, int maxFieldBytes) throws SQLException {
        if (reader == null) {
            return new ConvertedValue(null, false);
        }
        StringBuilder text = new StringBuilder(Math.min(maxFieldBytes, 8192));
        char[] buffer = new char[8192];
        boolean truncated = false;
        try (reader) {
            while (text.length() <= maxFieldBytes) {
                int count = reader.read(buffer);
                if (count < 0) {
                    break;
                }
                int remaining = maxFieldBytes + 1 - text.length();
                text.append(buffer, 0, Math.min(count, remaining));
                if (count > remaining || text.length() > maxFieldBytes) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException exception) {
            throw new SQLException("character result field could not be read safely", exception);
        }
        return convertText(text.toString(), maxFieldBytes, truncated);
    }

    private ConvertedValue convertText(String text, int maxFieldBytes, boolean sourceTruncated) {
        boolean truncated = sourceTruncated || text.getBytes(StandardCharsets.UTF_8).length > maxFieldBytes;
        return new ConvertedValue(
                truncated ? truncateText(text, maxFieldBytes, true) : text,
                truncated);
    }

    private String truncateText(String text, int maxFieldBytes, boolean sourceTruncated) {
        if (!sourceTruncated && text.getBytes(StandardCharsets.UTF_8).length <= maxFieldBytes) {
            return text;
        }
        int markerBytes = TRUNCATED_MARKER.getBytes(StandardCharsets.UTF_8).length;
        int budget = Math.max(0, maxFieldBytes - markerBytes);
        StringBuilder safe = new StringBuilder(Math.min(text.length(), budget));
        int used = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int encodedBytes = utf8Length(codePoint);
            if (used + encodedBytes > budget) {
                break;
            }
            safe.appendCodePoint(codePoint);
            used += encodedBytes;
            offset += Character.charCount(codePoint);
        }
        if (maxFieldBytes < markerBytes) {
            return "";
        }
        return safe.append(TRUNCATED_MARKER).toString();
    }

    private long estimateBytes(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof String text) {
            long bytes = 2;
            for (int offset = 0; offset < text.length(); ) {
                int codePoint = text.codePointAt(offset);
                bytes += switch (codePoint) {
                    case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                    default -> codePoint < 0x20 ? 6 : utf8Length(codePoint);
                };
                offset += Character.charCount(codePoint);
            }
            return bytes + 1;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private long estimateColumnBytes(List<QueryColumn> columns) {
        long bytes = 2;
        for (QueryColumn column : columns) {
            bytes += estimateBytes(column.label())
                    + estimateBytes(column.name())
                    + estimateBytes(column.typeName())
                    + 64;
        }
        return bytes;
    }

    private String metadataText(String value) {
        return truncateText(value == null ? "" : value, MAX_METADATA_TEXT_BYTES, false);
    }

    private int utf8Length(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    private void bind(PreparedStatement statement, List<QueryParameter> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            QueryParameter parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            String type = parameter.type().toUpperCase(Locale.ROOT);
            Object value = parameter.value();
            if (value == null) {
                statement.setNull(jdbcIndex, jdbcType(type));
                continue;
            }
            switch (type) {
                case "STRING", "VARCHAR" -> statement.setString(jdbcIndex, String.valueOf(value));
                case "INTEGER", "INT" -> statement.setInt(jdbcIndex, Integer.parseInt(String.valueOf(value)));
                case "LONG", "BIGINT" -> statement.setLong(jdbcIndex, Long.parseLong(String.valueOf(value)));
                case "DECIMAL", "NUMERIC" ->
                    statement.setBigDecimal(jdbcIndex, new java.math.BigDecimal(String.valueOf(value)));
                case "DOUBLE" -> statement.setDouble(jdbcIndex, Double.parseDouble(String.valueOf(value)));
                case "BOOLEAN" -> statement.setBoolean(jdbcIndex, parseBoolean(value));
                case "DATE" -> statement.setDate(jdbcIndex, java.sql.Date.valueOf(String.valueOf(value)));
                case "TIME" -> statement.setTime(jdbcIndex, java.sql.Time.valueOf(String.valueOf(value)));
                case "TIMESTAMP" ->
                    statement.setTimestamp(jdbcIndex, java.sql.Timestamp.valueOf(String.valueOf(value)));
                case "NULL" -> statement.setNull(jdbcIndex, Types.NULL);
                default -> throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "UNSUPPORTED_PARAMETER_TYPE", "不支持的查询参数类型：" + type);
            }
        }
    }

    private int jdbcType(String type) {
        return switch (type) {
            case "INTEGER", "INT" -> Types.INTEGER;
            case "LONG", "BIGINT" -> Types.BIGINT;
            case "DECIMAL", "NUMERIC" -> Types.NUMERIC;
            case "DOUBLE" -> Types.DOUBLE;
            case "BOOLEAN" -> Types.BOOLEAN;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP" -> Types.TIMESTAMP;
            case "NULL" -> Types.NULL;
            default -> Types.VARCHAR;
        };
    }

    void validateParameters(List<QueryParameter> parameters) {
        long totalBytes = 0;
        for (QueryParameter parameter : parameters) {
            if (parameter == null || parameter.type() == null) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "查询参数及其类型不能为空");
            }
            String type = parameter.type().toUpperCase(Locale.ROOT);
            if (!PARAMETER_TYPES.contains(type)) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "UNSUPPORTED_PARAMETER_TYPE", "不支持的查询参数类型：" + type);
            }
            Object value = parameter.value();
            if (value != null
                    && !(value instanceof String
                            || value instanceof Number
                            || value instanceof Boolean
                            || value instanceof Character)) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_PARAMETER_VALUE",
                        "查询参数值只能是字符串、数字、布尔值或 null");
            }
            if (value != null) {
                int valueBytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
                if (valueBytes > properties.getQuery().getMaxFieldBytes()) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST,
                            "PARAMETER_TOO_LARGE",
                            "单个查询参数超过 256 KiB 安全限制");
                }
                totalBytes += valueBytes;
                if (totalBytes > MAX_PARAMETER_TOTAL_BYTES) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST,
                            "PARAMETERS_TOO_LARGE",
                            "查询参数总量超过 1 MiB 安全限制");
                }
            }
            if ("NULL".equals(type)) {
                if (value != null) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST, "INVALID_PARAMETER_VALUE", "NULL 参数不能携带非空值");
                }
                continue;
            }
            if (value == null) {
                continue;
            }
            try {
                switch (type) {
                    case "INTEGER", "INT" -> Integer.parseInt(String.valueOf(value));
                    case "LONG", "BIGINT" -> Long.parseLong(String.valueOf(value));
                    case "DECIMAL", "NUMERIC" -> new java.math.BigDecimal(String.valueOf(value));
                    case "DOUBLE" -> {
                        double parsed = Double.parseDouble(String.valueOf(value));
                        if (!Double.isFinite(parsed)) {
                            throw new IllegalArgumentException("non-finite");
                        }
                    }
                    case "BOOLEAN" -> parseBoolean(value);
                    case "DATE" -> java.sql.Date.valueOf(String.valueOf(value));
                    case "TIME" -> java.sql.Time.valueOf(String.valueOf(value));
                    case "TIMESTAMP" -> java.sql.Timestamp.valueOf(String.valueOf(value));
                    default -> String.valueOf(value);
                }
            } catch (IllegalArgumentException exception) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "INVALID_PARAMETER_VALUE", "查询参数值与声明类型不匹配");
            }
        }
    }

    private boolean parseBoolean(Object value) {
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("boolean value must be true or false");
    }

    private void throwIfCancelled(String queryId) throws QueryCancelledSQLException {
        if (cancelledQueries.contains(queryId)) {
            throw new QueryCancelledSQLException();
        }
    }

    private void markUnexpectedExecutionFailure(StoredQuery query, String code) {
        if (!queryRepository.failIfExecuting(query.id(), QueryStatus.FAILED, code)) {
            return;
        }
        auditService.record(new AuditCommand(
                query.actor(),
                query.actorType(),
                "QUERY_FAILED",
                query.dataSourceId(),
                query.id(),
                query.purpose(),
                query.sqlFingerprint(),
                Map.of(),
                "FAILED",
                null,
                null,
                null,
                code));
    }

    private StoredQuery requireAccessible(String queryId) {
        StoredQuery query = queryRepository.require(queryId);
        if (actorContext.actorType() == ActorType.API_TOKEN) {
            assertTokenScope(query.dataSourceId());
            if (!query.actor().equals(actorContext.actor())) {
                throw new GatewayException(HttpStatus.FORBIDDEN, "QUERY_SCOPE_DENIED", "不能访问其它令牌的查询");
            }
        }
        return query;
    }

    private void assertTokenScope(String dataSourceId) {
        TokenContext token = actorContext.requireToken();
        if (!token.permitsDataSource(dataSourceId)) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "DATASOURCE_SCOPE_DENIED", "访问令牌无权使用该数据源");
        }
    }

    private QueryView view(StoredQuery query, QueryResult result) {
        return view(query, result, false);
    }

    private QueryView view(StoredQuery query, QueryResult result, boolean includeRequestPayloadForAdmin) {
        boolean includeRequestPayload =
                includeRequestPayloadForAdmin
                        || query.status() == QueryStatus.PENDING_APPROVAL
                        || query.status() == QueryStatus.APPROVED;
        String sql = includeRequestPayload ? crypto.decrypt(query.sqlCipher()) : null;
        List<QueryParameter> parameters = includeRequestPayload
                ? readParameters(crypto.decrypt(query.parametersCipher()))
                : null;
        return new QueryView(
                query.id(),
                query.status(),
                query.dataSourceId(),
                query.purpose(),
                query.effectiveMaxRows(),
                query.riskReasons(),
                query.approvalExpiresAt(),
                query.createdAt(),
                query.errorCode(),
                sql,
                parameters,
                result);
    }

    private synchronized void rememberResult(String queryId, QueryResult result) {
        evictExpiredResults();
        recentResults.put(queryId, new RecentResult(Instant.now().plus(RECENT_RESULT_TTL), result));
        while (recentResults.size() > MAX_RECENT_RESULTS) {
            Iterator<String> iterator = recentResults.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private synchronized QueryResult recentResult(String queryId) {
        evictExpiredResults();
        RecentResult cached = recentResults.get(queryId);
        return cached == null ? null : cached.result();
    }

    private void evictExpiredResults() {
        Instant now = Instant.now();
        recentResults.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record RecentResult(Instant expiresAt, QueryResult result) {}

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("查询参数序列化失败", exception);
        }
    }

    private List<QueryParameter> readParameters(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "QUERY_PAYLOAD_INVALID",
                    "已保存的查询参数损坏",
                    exception);
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static final class QueryCancelledSQLException extends SQLException {
        private QueryCancelledSQLException() {
            super("query cancelled");
        }
    }

    private record ConvertedValue(Object value, boolean truncated) {}
}
