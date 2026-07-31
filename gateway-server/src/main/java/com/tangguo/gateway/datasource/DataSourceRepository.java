package com.tangguo.gateway.datasource;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataSourceRepository {
    private final JdbcTemplate jdbcTemplate;

    public DataSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DataSourceConfig> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM data_source_config WHERE deleted = 0 ORDER BY created_at DESC",
                this::map);
    }

    public DataSourceConfig require(String id) {
        List<DataSourceConfig> results = jdbcTemplate.query(
                "SELECT * FROM data_source_config WHERE id = ? AND deleted = 0", this::map, id);
        if (results.isEmpty()) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "DATASOURCE_NOT_FOUND", "数据源不存在");
        }
        return results.getFirst();
    }

    public void insert(DataSourceConfig config) {
        jdbcTemplate.update(
                """
                INSERT INTO data_source_config(
                    id, name, database_type, secret_ref, credential_version,
                    read_only_status, enabled, deleted,
                    allow_compatibility, query_timeout_seconds, last_tested_at, last_test_message,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                """,
                config.id(),
                config.name(),
                config.databaseType().name(),
                config.secretRef(),
                config.credentialVersion(),
                config.readOnlyStatus().name(),
                config.enabled() ? 1 : 0,
                config.allowCompatibility() ? 1 : 0,
                config.queryTimeoutSeconds(),
                instant(config.lastTestedAt()),
                config.lastTestMessage(),
                instant(config.createdAt()),
                instant(config.updatedAt()));
    }

    public boolean updateBasics(
            String id,
            long credentialVersion,
            String name,
            boolean allowCompatibility,
            int timeout,
            boolean requestedEnabled) {
        return jdbcTemplate.update(
                """
                UPDATE data_source_config
                   SET name = ?, allow_compatibility = ?, query_timeout_seconds = ?,
                       enabled = CASE
                           WHEN ? = 1
                            AND read_only_status IN ('STRICT', 'COMPATIBILITY')
                           THEN 1 ELSE 0 END,
                       updated_at = ?
                 WHERE id = ? AND deleted = 0 AND credential_version = ?
                """,
                name,
                allowCompatibility ? 1 : 0,
                timeout,
                requestedEnabled ? 1 : 0,
                Instant.now().toString(),
                id,
                credentialVersion)
                == 1;
    }

    public boolean rotateSecret(String id, long expectedVersion, String newSecretRef) {
        return jdbcTemplate.update(
                """
                UPDATE data_source_config
                   SET secret_ref = ?, credential_version = credential_version + 1,
                       enabled = 0, read_only_status = 'UNKNOWN', last_tested_at = NULL,
                       last_test_message = '连接信息已变更，必须重新复检', updated_at = ?
                 WHERE id = ? AND deleted = 0 AND credential_version = ?
                """,
                newSecretRef,
                Instant.now().toString(),
                id,
                expectedVersion)
                == 1;
    }

    public boolean updateTestResult(
            String id, long credentialVersion, ReadOnlyStatus status, String message, Instant testedAt) {
        return jdbcTemplate.update(
                """
                UPDATE data_source_config
                   SET read_only_status = ?,
                       enabled = CASE
                           WHEN ? IN ('STRICT', 'COMPATIBILITY')
                           THEN 1 ELSE 0 END,
                       last_tested_at = ?,
                       last_test_message = ?, updated_at = ?
                 WHERE id = ? AND deleted = 0 AND credential_version = ?
                """,
                status.name(),
                status.name(),
                testedAt.toString(),
                message,
                testedAt.toString(),
                id,
                credentialVersion)
                == 1;
    }

    /**
     * 连接复检期间先隔离数据源。这样连接、Keychain、驱动或审计链在检查中途失败时，
     * 旧的 COMPATIBILITY 结果不会继续放行新查询。
     */
    public boolean beginInspection(String id, long credentialVersion) {
        return jdbcTemplate.update(
                """
                UPDATE data_source_config
                   SET enabled = 0, read_only_status = 'UNKNOWN',
                       last_test_message = '正在执行连接复检，数据源已临时隔离',
                       updated_at = ?
                 WHERE id = ? AND deleted = 0 AND credential_version = ?
                """,
                Instant.now().toString(),
                id,
                credentialVersion)
                == 1;
    }

    public void softDelete(String id) {
        jdbcTemplate.update(
                """
                UPDATE data_source_config
                   SET deleted = 1, enabled = 0, credential_version = credential_version + 1,
                       updated_at = ?
                 WHERE id = ? AND deleted = 0
                """,
                Instant.now().toString(),
                id);
    }

    private DataSourceConfig map(ResultSet resultSet, int rowNum) throws SQLException {
        return new DataSourceConfig(
                resultSet.getString("id"),
                resultSet.getString("name"),
                DatabaseType.valueOf(resultSet.getString("database_type")),
                resultSet.getString("secret_ref"),
                resultSet.getLong("credential_version"),
                ReadOnlyStatus.valueOf(resultSet.getString("read_only_status")),
                resultSet.getInt("enabled") == 1,
                resultSet.getInt("allow_compatibility") == 1,
                resultSet.getInt("query_timeout_seconds"),
                instant(resultSet.getString("last_tested_at")),
                resultSet.getString("last_test_message"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")));
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
