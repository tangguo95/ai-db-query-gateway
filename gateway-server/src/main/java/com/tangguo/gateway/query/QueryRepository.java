package com.tangguo.gateway.query;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.QueryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class QueryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public QueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(StoredQuery query) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO query_request(
                        id, actor, actor_type, data_source_id, sql_cipher, parameters_cipher,
                        sql_fingerprint, purpose, requested_max_rows, effective_max_rows, status,
                        risk_reasons, approval_expires_at, approved_at, consumed_at, error_code,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    query.id(),
                    query.actor(),
                    query.actorType().name(),
                    query.dataSourceId(),
                    query.sqlCipher(),
                    query.parametersCipher(),
                    query.sqlFingerprint(),
                    query.purpose(),
                    query.requestedMaxRows(),
                    query.effectiveMaxRows(),
                    query.status().name(),
                    objectMapper.writeValueAsString(query.riskReasons()),
                    instant(query.approvalExpiresAt()),
                    instant(query.approvedAt()),
                    instant(query.consumedAt()),
                    query.errorCode(),
                    query.createdAt().toString(),
                    query.updatedAt().toString());
        } catch (JacksonException exception) {
            throw new IllegalStateException("查询风险信息序列化失败", exception);
        }
    }

    public StoredQuery require(String id) {
        List<StoredQuery> results =
                jdbcTemplate.query("SELECT * FROM query_request WHERE id = ?", this::map, id);
        if (results.isEmpty()) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "QUERY_NOT_FOUND", "查询请求不存在");
        }
        return results.getFirst();
    }

    public List<StoredQuery> find(QueryStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (status == null) {
            return jdbcTemplate.query(
                    "SELECT * FROM query_request ORDER BY created_at DESC LIMIT ?", this::map, safeLimit);
        }
        return jdbcTemplate.query(
                "SELECT * FROM query_request WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                this::map,
                status.name(),
                safeLimit);
    }

    public boolean approve(String id, Instant approvedAt, Instant expiresAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE query_request
                           SET status = 'APPROVED', approved_at = ?, approval_expires_at = ?, updated_at = ?
                         WHERE id = ? AND status = 'PENDING_APPROVAL'
                        """,
                        approvedAt.toString(),
                        expiresAt.toString(),
                        approvedAt.toString(),
                        id)
                == 1;
    }

    public boolean consume(String id, Instant consumedAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE query_request
                           SET status = 'EXECUTING', consumed_at = ?, updated_at = ?
                         WHERE id = ? AND status = 'APPROVED' AND consumed_at IS NULL
                        """,
                        consumedAt.toString(),
                        consumedAt.toString(),
                        id)
                == 1;
    }

    public void updateStatus(String id, QueryStatus status, String errorCode) {
        jdbcTemplate.update(
                "UPDATE query_request SET status = ?, error_code = ?, updated_at = ? WHERE id = ?",
                status.name(),
                errorCode,
                Instant.now().toString(),
                id);
    }

    public CancelOutcome cancelActive(String id, Instant cancelledAt) {
        int nonExecuting = jdbcTemplate.update(
                        """
                        UPDATE query_request
                           SET status = 'CANCELLED', error_code = 'QUERY_CANCELLED', updated_at = ?
                         WHERE id = ? AND status IN ('PENDING_APPROVAL', 'APPROVED')
                        """,
                        cancelledAt.toString(),
                        id);
        if (nonExecuting == 1) {
            return CancelOutcome.NON_EXECUTING;
        }
        int executing = jdbcTemplate.update(
                """
                UPDATE query_request
                   SET status = 'CANCELLED', error_code = 'QUERY_CANCELLED', updated_at = ?
                 WHERE id = ? AND status = 'EXECUTING'
                """,
                cancelledAt.toString(),
                id);
        return executing == 1 ? CancelOutcome.EXECUTING : CancelOutcome.NONE;
    }

    public boolean completeExecuted(String id) {
        return jdbcTemplate.update(
                        """
                        UPDATE query_request
                           SET status = 'EXECUTED', error_code = NULL, updated_at = ?
                         WHERE id = ? AND status = 'EXECUTING'
                        """,
                        Instant.now().toString(),
                        id)
                == 1;
    }

    public boolean failIfExecuting(String id, QueryStatus status, String errorCode) {
        return jdbcTemplate.update(
                        """
                        UPDATE query_request
                           SET status = ?, error_code = ?, updated_at = ?
                         WHERE id = ? AND status = 'EXECUTING'
                        """,
                        status.name(),
                        errorCode,
                        Instant.now().toString(),
                        id)
                == 1;
    }

    private StoredQuery map(ResultSet resultSet, int rowNum) throws SQLException {
        try {
            return new StoredQuery(
                    resultSet.getString("id"),
                    resultSet.getString("actor"),
                    ActorType.valueOf(resultSet.getString("actor_type")),
                    resultSet.getString("data_source_id"),
                    resultSet.getString("sql_cipher"),
                    resultSet.getString("parameters_cipher"),
                    resultSet.getString("sql_fingerprint"),
                    resultSet.getString("purpose"),
                    resultSet.getInt("requested_max_rows"),
                    resultSet.getInt("effective_max_rows"),
                    QueryStatus.valueOf(resultSet.getString("status")),
                    objectMapper.readValue(resultSet.getString("risk_reasons"), new TypeReference<>() {}),
                    instant(resultSet.getString("approval_expires_at")),
                    instant(resultSet.getString("approved_at")),
                    instant(resultSet.getString("consumed_at")),
                    resultSet.getString("error_code"),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at")));
        } catch (JacksonException exception) {
            throw new SQLException("stored query risk reasons are invalid", exception);
        }
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    public enum CancelOutcome {
        NON_EXECUTING,
        EXECUTING,
        NONE
    }
}
