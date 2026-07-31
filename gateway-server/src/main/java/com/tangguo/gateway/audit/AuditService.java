package com.tangguo.gateway.audit;

import com.tangguo.gateway.api.ApiDtos.AuditPage;
import com.tangguo.gateway.api.ApiDtos.AuditView;
import com.tangguo.gateway.api.GatewayException;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService {
    private static final String GENESIS_HMAC = "GENESIS";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final List<String> APPROVAL_EVENTS = List.of(
            "QUERY_PENDING_APPROVAL", "QUERY_APPROVAL_REQUESTED", "QUERY_APPROVED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditCryptoService crypto;
    private volatile boolean chainValid = true;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AuditCryptoService crypto) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
    }

    @PostConstruct
    void verifyAtStartup() {
        if (!verifyChain()) {
            throw new IllegalStateException("审计链校验失败，服务拒绝启动");
        }
    }

    /**
     * 审计采用同步先写策略；任何加密或写入失败都会中止业务操作，避免无审计执行生产查询。
     */
    public synchronized void record(AuditCommand command) {
        if (!chainValid) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUDIT_CHAIN_INVALID",
                    "审计链校验未通过，操作已中止");
        }
        try {
            String previous = jdbcTemplate.query(
                    "SELECT record_hmac FROM audit_event ORDER BY sequence_no DESC LIMIT 1",
                    resultSet -> resultSet.next() ? resultSet.getString(1) : GENESIS_HMAC);
            String eventId = UUID.randomUUID().toString();
            String occurredAt = Instant.now().toString();
            String payload = crypto.encrypt(objectMapper.writeValueAsString(command.sensitivePayload()));
            String material = material(
                    eventId,
                    occurredAt,
                    command.actor(),
                    command.actorType().name(),
                    command.eventType(),
                    command.dataSourceId(),
                    command.queryId(),
                    command.purpose(),
                    command.sqlFingerprint(),
                    payload,
                    command.status(),
                    command.durationMs(),
                    command.rowCount(),
                    command.byteCount(),
                    command.errorCode(),
                    previous);
            String hmac = crypto.hmac(material);
            jdbcTemplate.update(
                    """
                    INSERT INTO audit_event(
                        event_id, occurred_at, actor, actor_type, event_type, data_source_id, query_id,
                        purpose, sql_fingerprint, encrypted_payload, status, duration_ms, row_count,
                        byte_count, error_code, previous_hmac, record_hmac)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventId,
                    occurredAt,
                    command.actor(),
                    command.actorType().name(),
                    command.eventType(),
                    command.dataSourceId(),
                    command.queryId(),
                    command.purpose(),
                    command.sqlFingerprint(),
                    payload,
                    command.status(),
                    command.durationMs(),
                    command.rowCount(),
                    command.byteCount(),
                    command.errorCode(),
                    previous,
                    hmac);
        } catch (RuntimeException exception) {
            chainValid = false;
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUDIT_UNAVAILABLE",
                    "审计写入失败，操作已中止",
                    exception);
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void scheduledVerification() {
        chainValid = verifyChain();
    }

    public synchronized boolean verifyChain() {
        try {
            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList("SELECT * FROM audit_event ORDER BY sequence_no ASC");
            String previous = GENESIS_HMAC;
            for (Map<String, Object> row : rows) {
                String storedPrevious = string(row.get("previous_hmac"));
                if (!crypto.constantTimeEquals(previous, storedPrevious)) {
                    chainValid = false;
                    return false;
                }
                String expected = crypto.hmac(material(
                        string(row.get("event_id")),
                        string(row.get("occurred_at")),
                        string(row.get("actor")),
                        string(row.get("actor_type")),
                        string(row.get("event_type")),
                        string(row.get("data_source_id")),
                        string(row.get("query_id")),
                        string(row.get("purpose")),
                        string(row.get("sql_fingerprint")),
                        string(row.get("encrypted_payload")),
                        string(row.get("status")),
                        number(row.get("duration_ms")),
                        integer(row.get("row_count")),
                        number(row.get("byte_count")),
                        string(row.get("error_code")),
                        storedPrevious));
                String actual = string(row.get("record_hmac"));
                if (!crypto.constantTimeEquals(expected, actual)) {
                    chainValid = false;
                    return false;
                }
                previous = actual;
            }
            chainValid = true;
            return true;
        } catch (RuntimeException exception) {
            chainValid = false;
            return false;
        }
    }

    public boolean isChainValid() {
        return chainValid;
    }

    public AuditPage findPage(
            int page, int size, String eventType, String status, String queryId) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        String normalizedEventType = optionalFilter(eventType, 64, "eventType");
        String normalizedStatus = optionalFilter(status, 32, "status");
        String normalizedQueryId = optionalQueryId(queryId);
        List<String> predicates = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (normalizedEventType != null) {
            if ("APPROVAL".equals(normalizedEventType)) {
                predicates.add("a.event_type IN (?, ?, ?)");
                arguments.addAll(APPROVAL_EVENTS);
            } else {
                predicates.add("a.event_type LIKE ? ESCAPE '\\'");
                arguments.add(escapeLike(normalizedEventType) + "%");
            }
        }
        if (normalizedStatus != null) {
            predicates.add("a.status = ?");
            arguments.add(normalizedStatus);
        }
        if (normalizedQueryId != null) {
            predicates.add("a.query_id = ?");
            arguments.add(normalizedQueryId);
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(safeSize);
        pageArguments.add(safePage * safeSize);
        List<AuditView> items = jdbcTemplate.query(
                """
                SELECT a.sequence_no, a.event_id, a.occurred_at, a.actor, a.actor_type, a.event_type,
                       a.data_source_id, COALESCE(ds.name, a.data_source_id) AS data_source_name,
                       a.query_id, a.purpose, a.sql_fingerprint, a.status, a.duration_ms,
                       a.row_count, a.byte_count, a.error_code
                  FROM audit_event a
                  LEFT JOIN data_source_config ds ON ds.id = a.data_source_id
                """
                        + where
                        + " ORDER BY sequence_no DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> new AuditView(
                        resultSet.getLong("sequence_no"),
                        resultSet.getString("event_id"),
                        Instant.parse(resultSet.getString("occurred_at")),
                        resultSet.getString("actor"),
                        resultSet.getString("actor_type"),
                        resultSet.getString("event_type"),
                        resultSet.getString("data_source_id"),
                        resultSet.getString("data_source_name"),
                        resultSet.getString("query_id"),
                        resultSet.getString("purpose"),
                        resultSet.getString("sql_fingerprint"),
                        resultSet.getString("status"),
                        nullableLong(resultSet, "duration_ms"),
                        nullableInteger(resultSet, "row_count"),
                        nullableLong(resultSet, "byte_count"),
                        resultSet.getString("error_code"),
                        chainValid),
                pageArguments.toArray());
        Long totalValue = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event a LEFT JOIN data_source_config ds ON ds.id = a.data_source_id" + where,
                Long.class,
                arguments.toArray());
        long total = totalValue == null ? 0 : totalValue;
        return new AuditPage(items, safePage, safeSize, total, chainValid);
    }

    private String optionalFilter(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() > maxLength || !normalized.matches("[A-Z0-9_]+")) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUDIT_FILTER",
                    "审计筛选字段 " + field + " 格式无效");
        }
        return normalized;
    }

    private String optionalQueryId(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            return null;
        }
        String normalized = queryId.trim();
        try {
            return UUID.fromString(normalized).toString();
        } catch (IllegalArgumentException exception) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUDIT_FILTER",
                    "审计 queryId 格式无效");
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String material(
            Object eventId,
            Object occurredAt,
            Object actor,
            Object actorType,
            Object eventType,
            Object dataSourceId,
            Object queryId,
            Object purpose,
            Object sqlFingerprint,
            Object payload,
            Object status,
            Object durationMs,
            Object rowCount,
            Object byteCount,
            Object errorCode,
            Object previous) {
        return String.join(
                FIELD_SEPARATOR,
                string(eventId),
                string(occurredAt),
                string(actor),
                string(actorType),
                string(eventType),
                string(dataSourceId),
                string(queryId),
                string(purpose),
                string(sqlFingerprint),
                string(payload),
                string(status),
                string(durationMs),
                string(rowCount),
                string(byteCount),
                string(errorCode),
                string(previous));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
