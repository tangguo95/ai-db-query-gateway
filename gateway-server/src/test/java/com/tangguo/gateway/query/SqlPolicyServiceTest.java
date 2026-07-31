package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.DatabaseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlPolicyServiceTest {
    private SqlPolicyService policy;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.isSystemSchema("MYSQL")).thenReturn(true);
        when(dataSourceService.isSystemSchema("SYS")).thenReturn(true);
        policy = new SqlPolicyService(properties, dataSourceService);
    }

    @Test
    void acceptsParameterizedSelectAndReportsRisks() {
        SqlAnalysis analysis =
                policy.analyze(DatabaseType.MYSQL, "SELECT * FROM app_user WHERE id = ?", 500);

        assertThat(analysis.parameterCount()).isEqualTo(1);
        assertThat(analysis.tables()).contains("app_user");
        assertThat(analysis.riskReasons()).containsExactlyInAnyOrder("SELECT_ALL", "MAX_ROWS_OVER_AUTO_LIMIT");
        assertThat(analysis.fingerprint()).hasSize(64);
    }

    @Test
    void acceptsOracleCteAndUnion() {
        SqlAnalysis analysis = policy.analyze(
                DatabaseType.OCEANBASE_ORACLE,
                "WITH recent AS (SELECT id FROM orders WHERE created_at >= ?) "
                        + "SELECT id FROM recent UNION SELECT id FROM archived_orders WHERE id = ?",
                100);

        assertThat(analysis.parameterCount()).isEqualTo(2);
        assertThat(analysis.tables()).contains("orders", "archived_orders");
    }

    @Test
    void flagsSystemAndCrossSchemaQueries() {
        SqlAnalysis analysis = policy.analyze(
                DatabaseType.MYSQL,
                "SELECT a.id FROM mysql.user a JOIN app.orders b ON b.id = a.id WHERE a.id = ?",
                100);

        assertThat(analysis.riskReasons()).contains("SYSTEM_SCHEMA", "CROSS_SCHEMA_QUERY");
    }

    @Test
    void rejectsEveryNonSelectAndMultiStatementShape() {
        assertRejected("UPDATE users SET name = 'x'", "ONLY_SINGLE_SELECT_ALLOWED");
        assertRejected("DELETE FROM users", "ONLY_SINGLE_SELECT_ALLOWED");
        assertRejected("CREATE TABLE x(id INT)", "ONLY_SINGLE_SELECT_ALLOWED");
        assertRejected("SELECT 1; SELECT 2", "SQL_DELIMITER_FORBIDDEN");
        assertRejected("SELECT 1 -- comment", "SQL_COMMENT_FORBIDDEN");
        assertRejected("SELECT /*+ INDEX(x) */ id FROM x", "SQL_COMMENT_FORBIDDEN");
    }

    @Test
    void rejectsLockingFileAndUnknownFunctionQueries() {
        assertRejected("SELECT id FROM users WHERE id = 1 FOR UPDATE", "LOCKING_SELECT_FORBIDDEN");
        assertRejected("SELECT * INTO OUTFILE '/tmp/x' FROM users", "SELECT_INTO_FORBIDDEN");
        assertRejected("SELECT SLEEP(10) FROM users", "UNKNOWN_FUNCTION_FORBIDDEN");
        assertRejected("SELECT id FROM remote_users@prod WHERE id = 1", "DATABASE_LINK_FORBIDDEN");
    }

    @Test
    void rejectsRecursiveOrMutatingCte() {
        assertRejected(
                "WITH RECURSIVE n AS (SELECT 1 UNION ALL SELECT 1 FROM n) SELECT * FROM n",
                "RECURSIVE_CTE_FORBIDDEN");
        assertThatThrownBy(() -> policy.analyze(
                        DatabaseType.MYSQL,
                        "WITH changed AS (UPDATE users SET name='x' RETURNING id) SELECT * FROM changed",
                        100))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void allowsCommentMarkersInsideStringLiteralsOnly() {
        SqlAnalysis analysis =
                policy.analyze(DatabaseType.MYSQL, "SELECT '--not comment' AS marker FROM users WHERE id = 1", 10);
        assertThat(analysis.tables()).contains("users");
    }

    private void assertRejected(String sql, String code) {
        assertThatThrownBy(() -> policy.analyze(DatabaseType.MYSQL, sql, 100))
                .isInstanceOfSatisfying(
                        GatewayException.class, exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
