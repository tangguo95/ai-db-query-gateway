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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        assertRejected("SELECT /*+ SET_VAR(max_execution_time=0) */ id FROM x", "SQL_HINT_FORBIDDEN");
    }

    @Test
    void rejectsLockingFileAndDatabaseLinkQueries() {
        assertRejected("SELECT id FROM users WHERE id = 1 FOR UPDATE", "LOCKING_SELECT_FORBIDDEN");
        assertRejected("SELECT * INTO OUTFILE '/tmp/x' FROM users", "SELECT_INTO_FORBIDDEN");
        assertRejected("SELECT id FROM remote_users@prod WHERE id = 1", "DATABASE_LINK_FORBIDDEN");
    }

    @Test
    void allowsDateAndCustomFunctionsWithoutAllowingWrites() {
        for (DatabaseType type : new DatabaseType[] {
                DatabaseType.MYSQL, DatabaseType.DRDS_MYSQL, DatabaseType.OCEANBASE_MYSQL}) {
            var analysis = policy.analyze(type,
                    "SELECT DATE_FORMAT(STR_TO_DATE(?, '%Y-%m-%d'), '%Y-%m'), "
                            + "get_workday_minutes(?, ?) FROM orders WHERE id = ?", 100);
            assertThat(analysis.parameterCount()).isEqualTo(4);
        }
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT get_workday_minutes(SLEEP(5), ?) FROM orders", 100).parameterCount()).isEqualTo(1);
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT get_workday_minutes_other(?, ?) FROM orders", 100).parameterCount()).isEqualTo(2);
        assertRejected("UPDATE orders SET minutes = get_workday_minutes(?, ?)",
                "ONLY_SINGLE_SELECT_ALLOWED");
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void acceptsAllFunctionNamesAndInspectsArguments(DatabaseType type) {
        SqlAnalysis analysis = policy.analyze(type,
                "WITH recent AS (SELECT app.custom_value(o.id, ?) AS value FROM app.orders o "
                        + "WHERE custom_filter(o.status, ?) = 1) "
                        + "SELECT custom_aggregate(nested_value(value, ?)) FROM recent "
                        + "WHERE custom_match(value, ?) = 1 "
                        + "HAVING custom_having(COUNT(*), ?) = 1 ORDER BY custom_sort(?)", 100);
        assertThat(analysis.parameterCount()).isEqualTo(6);
        assertThat(analysis.tables()).containsExactly("app.orders");
        assertThat(analysis.schemas()).containsExactly("APP");
        assertThat(analysis.fingerprint()).hasSize(64);
        assertThat(policy.analyze(type,
                "SELECT /*+ INDEX(o idx_orders) */ app.custom_value(?) FROM orders o WHERE id = ?",
                100).parameterCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void unrestrictedFunctionsCannotHideForbiddenSqlStructures(DatabaseType type) {
        for (String sql : java.util.List.of(
                "SELECT custom_value(@@version) FROM orders",
                "SELECT custom_value((SELECT id FROM orders FOR UPDATE)) FROM orders",
                "SELECT custom_value((SELECT id INTO target_id FROM orders)) FROM orders",
                "SELECT custom_value((SELECT id FROM remote_orders@prod)) FROM orders",
                "SELECT custom_value(?) FROM orders; DELETE FROM orders",
                "SELECT custom_value(?) /* comment */ FROM orders")) {
            assertThatThrownBy(() -> policy.analyze(type, sql, 100))
                    .as("%s: %s", type, sql).isInstanceOf(GatewayException.class);
        }
        SqlAnalysis analysis = policy.analyze(type,
                "SELECT custom_value((SELECT MAX(id) FROM archive.orders WHERE id = ?)) "
                        + "FROM app.orders WHERE id = ?", 500);
        assertThat(analysis.parameterCount()).isEqualTo(2);
        assertThat(analysis.tables()).containsExactlyInAnyOrder("app.orders", "archive.orders");
        assertThat(analysis.riskReasons()).contains("CROSS_SCHEMA_QUERY", "MAX_ROWS_OVER_AUTO_LIMIT");
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

    @Test
    void acceptsIndexHintsWithoutManagedAllowlist() {
        for (DatabaseType type : DatabaseType.values()) {
            for (String hint : java.util.List.of(
                    "INDEX(o idx_orders_created)",
                    "index (o, idx_orders_created)",
                    "NO_INDEX(o idx_orders_created idx_orders_status)",
                    "INDEX_ASC(o idx_orders_created)",
                    "INDEX_DESC(o idx_orders_created)",
                    "INDEX(o idx_orders_created) NO_INDEX(o idx_orders_status)")) {
                SqlAnalysis analysis = policy.analyze(type,
                        "SELECT /*+ " + hint + " */ o.id FROM orders o WHERE o.created_at >= ? AND o.status = ?", 100);
                assertThat(analysis.parameterCount()).isEqualTo(2);
                assertThat(analysis.tables()).containsExactly("orders");
                assertThat(analysis.riskReasons()).isEmpty();
            }
        }
        assertThat(policy.analyze(DatabaseType.OCEANBASE_ORACLE,
                "SELECT /*+ INDEX(\"O\" \"IDX_ORDERS\") */ o.id FROM orders o WHERE o.id = ?", 100)
                .parameterCount()).isEqualTo(1);
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT /*+ INDEX(`o` `idx_orders`) */ o.id FROM orders o WHERE o.id = ?", 100)
                .parameterCount()).isEqualTo(1);
    }

    @Test
    void indexHintsPreserveCteNestedQueryAndRiskInspection() {
        for (DatabaseType type : DatabaseType.values()) {
            String sql = "WITH recent AS (SELECT /*+ INDEX(o idx_orders_created) */ o.id FROM app.orders o "
                    + "WHERE o.created_at >= ?) SELECT * FROM recent "
                    + "UNION ALL SELECT * FROM archive.orders WHERE id IN "
                    + "(SELECT /*+ INDEX(a idx_archived_id) */ a.id FROM archived_orders a WHERE a.id = ?)";
            SqlAnalysis hinted = policy.analyze(type, sql, 500);
            SqlAnalysis plain = policy.analyze(type, sql.replaceAll("/\\*\\+.*?\\*/", ""), 500);
            assertThat(hinted.parameterCount()).isEqualTo(plain.parameterCount()).isEqualTo(2);
            assertThat(hinted.tables()).isEqualTo(plain.tables());
            assertThat(hinted.schemas()).isEqualTo(plain.schemas());
            assertThat(hinted.riskReasons()).isEqualTo(plain.riskReasons())
                    .contains("SELECT_ALL", "CROSS_SCHEMA_QUERY", "MAX_ROWS_OVER_AUTO_LIMIT");
        }
    }

    @Test
    void indexHintsCannotBypassReadOnlyPolicy() {
        for (DatabaseType type : DatabaseType.values()) {
            for (String sql : java.util.List.of(
                    "SELECT /*+ INDEX(o idx) SET_VAR(max_execution_time=0) */ id FROM orders o",
                    "SELECT /*+ INDEX(o SLEEP(1)) */ id FROM orders o",
                    "SELECT /*+ INDEX(o idx); DELETE FROM orders */ id FROM orders o",
                    "SELECT /*+ INDEX(o idx) /* nested */ id FROM orders o",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o /* comment */",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o /*! FOR UPDATE */",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o; DELETE FROM orders",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o FOR UPDATE",
                    "SELECT /*+ INDEX(o idx) */ @@version FROM orders o",
                    "SELECT /*+ INDEX(o idx) */ id INTO target_id FROM orders o",
                    "DELETE /*+ INDEX(o idx) */ FROM orders o",
                    "SELECT /*+ INDEX() */ id FROM orders o",
                    "SELECT /*+ INDEX(o,idx,) */ id FROM orders o",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o -- comment",
                    "SELECT /*+ INDEX(o idx) */ id FROM orders o # comment",
                    "SELECT /*+ INDEX(o idx) id FROM orders o")) {
                assertThatThrownBy(() -> policy.analyze(type, sql, 100))
                        .as("%s: %s", type, sql).isInstanceOf(GatewayException.class);
            }
        }
    }

    private void assertRejected(String sql, String code) {
        assertThatThrownBy(() -> policy.analyze(DatabaseType.MYSQL, sql, 100))
                .isInstanceOfSatisfying(
                        GatewayException.class, exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Test
    void legacyFunctionSettingsDoNotRestrictQueriesAndHintAllowlistRemainsScoped() {
        var settings = mock(com.tangguo.gateway.security.SettingService.class);
        var sources = mock(com.tangguo.gateway.datasource.DataSourceRepository.class);
        var values = new java.util.HashMap<String, String>();
        when(settings.get(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                java.util.Optional.ofNullable(values.get(invocation.getArgument(0))));
        org.mockito.Mockito.doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settings).put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var rules = new SqlAllowlistService(settings, sources);
        policy.setAllowlist(rules);
        rules.saveFunctions("source-a", java.util.List.of("business_minutes"));
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT business_minutes(?) FROM orders WHERE id = ?", 100, "source-a").parameterCount())
                .isEqualTo(2);
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT business_minutes(?) FROM orders", 100, "source-b").parameterCount()).isEqualTo(1);
        rules.saveHints(DatabaseType.OCEANBASE_ORACLE, java.util.List.of("MATERIALIZE"));
        String sql = "WITH t AS (SELECT /*+ materialize */ id FROM orders WHERE id = ?) SELECT id FROM t";
        assertThat(policy.analyze(DatabaseType.OCEANBASE_ORACLE, sql, 100, "source-a").parameterCount()).isEqualTo(1);
        assertThat(policy.analyze(DatabaseType.OCEANBASE_ORACLE,
                sql.replace("materialize", "materialize INDEX(orders idx_orders)"), 100, "source-a")
                .parameterCount()).isEqualTo(1);
        assertThatThrownBy(() -> policy.analyze(DatabaseType.MYSQL, sql, 100)).isInstanceOf(GatewayException.class);
        for (String unsafe : java.util.List.of(
                "SELECT /*+ MATERIALIZE SET_VAR(max_execution_time=0) */ id FROM orders",
                "SELECT /*+ MATERIALIZE */ id FROM orders; DELETE FROM orders",
                "SELECT /*+ MATERIALIZE */ id FROM orders /* comment */",
                "SELECT /*+ MATERIALIZE */ id FROM orders FOR UPDATE",
                "SELECT /*+ MATERIALIZE(DELETE FROM orders) */ id FROM orders")) {
            assertThatThrownBy(() -> policy.analyze(DatabaseType.OCEANBASE_ORACLE, unsafe, 100))
                    .isInstanceOf(GatewayException.class);
        }
        rules.saveFunctions("source-a", java.util.List.of());
        assertThat(policy.analyze(DatabaseType.MYSQL,
                "SELECT business_minutes(?) FROM orders", 100, "source-a").parameterCount()).isEqualTo(1);
        rules.saveFunctions("source-a", java.util.List.of("LOAD_FILE"));
        assertThat(rules.functions("source-a")).containsExactly("LOAD_FILE");
    }
}
