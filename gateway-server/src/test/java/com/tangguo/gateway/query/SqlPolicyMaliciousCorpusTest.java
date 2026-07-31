package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.DatabaseType;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SQL 准入恶意语料。测试只验证策略层，确保任何危险或无法理解的语句都以可控的
 * {@link GatewayException} 失败，而不是把解析器异常泄漏到执行链。
 */
class SqlPolicyMaliciousCorpusTest {
    private SqlPolicyService policy;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.isSystemSchema(anyString())).thenAnswer(invocation -> {
            String schema = invocation.getArgument(0, String.class).toUpperCase(Locale.ROOT);
            return schema.equals("MYSQL")
                    || schema.equals("INFORMATION_SCHEMA")
                    || schema.equals("SYS")
                    || schema.equals("SYSTEM");
        });
        policy = new SqlPolicyService(properties, dataSourceService);
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"MYSQL", "OCEANBASE_ORACLE"})
    void acceptsOrdinaryParameterizedSelect(DatabaseType databaseType) {
        SqlAnalysis analysis = policy.analyze(
                databaseType,
                "SELECT u.id, UPPER(u.name) AS display_name FROM app_user u WHERE u.id = ?",
                100);

        assertThat(analysis.parameterCount()).isEqualTo(1);
        assertThat(analysis.tables()).contains("app_user");
        assertThat(analysis.fingerprint()).hasSize(64);
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"MYSQL", "OCEANBASE_ORACLE"})
    void acceptsUnionOfReadOnlyBranches(DatabaseType databaseType) {
        SqlAnalysis analysis = policy.analyze(
                databaseType,
                "SELECT id FROM active_orders WHERE id = ? "
                        + "UNION ALL SELECT id FROM archived_orders WHERE id = ?",
                100);

        assertThat(analysis.parameterCount()).isEqualTo(2);
        assertThat(analysis.tables()).contains("active_orders", "archived_orders");
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"MYSQL", "OCEANBASE_ORACLE"})
    void acceptsNonRecursiveSelectCte(DatabaseType databaseType) {
        SqlAnalysis analysis = policy.analyze(
                databaseType,
                "WITH recent_orders AS (SELECT id FROM orders WHERE created_at >= ?) "
                        + "SELECT id FROM recent_orders WHERE id = ?",
                100);

        assertThat(analysis.parameterCount()).isEqualTo(2);
        assertThat(analysis.tables()).contains("orders").doesNotContain("recent_orders");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("nonSelectStatements")
    void rejectsDmlAndDdl(DatabaseType databaseType, String label, String sql) {
        assertSafelyRejected(databaseType, sql);
    }

    static Stream<Arguments> nonSelectStatements() {
        return Stream.of(
                sqlForBoth("insert", "INSERT INTO app_user(id, name) VALUES (1, 'x')"),
                sqlForBoth("update", "UPDATE app_user SET name = 'x' WHERE id = 1"),
                sqlForBoth("delete", "DELETE FROM app_user WHERE id = 1"),
                sqlForBoth("create", "CREATE TABLE gateway_escape(id INT)"),
                sqlForBoth("alter", "ALTER TABLE app_user ADD escape_flag INT"),
                sqlForBoth("drop", "DROP TABLE app_user"),
                sqlForBoth("truncate", "TRUNCATE TABLE app_user"))
                .flatMap(stream -> stream);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("multiStatementCommentAndHintCases")
    void rejectsMultiStatementCommentsAndHints(
            DatabaseType databaseType, String label, String sql, String expectedCode) {
        assertRejectedWithCode(databaseType, sql, expectedCode);
    }

    static Stream<Arguments> multiStatementCommentAndHintCases() {
        return Stream.of(
                sqlForBoth(
                        "multi statement",
                        "SELECT id FROM app_user WHERE id = 1; DELETE FROM app_user",
                        "SQL_DELIMITER_FORBIDDEN"),
                sqlForBoth(
                        "line comment",
                        "SELECT id FROM app_user WHERE id = 1 -- bypass",
                        "SQL_COMMENT_FORBIDDEN"),
                sqlForBoth(
                        "block comment",
                        "SELECT id FROM app_user /* bypass */ WHERE id = 1",
                        "SQL_COMMENT_FORBIDDEN"),
                sqlForBoth(
                        "optimizer hint",
                        "SELECT /*+ FULL(app_user) */ id FROM app_user WHERE id = 1",
                        "SQL_COMMENT_FORBIDDEN"))
                .flatMap(stream -> stream);
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("selectIntoCases")
    void rejectsSelectIntoAndFileWrites(DatabaseType databaseType, String label, String sql) {
        assertSafelyRejected(databaseType, sql);
    }

    static Stream<Arguments> selectIntoCases() {
        return Stream.of(
                Arguments.of(
                        DatabaseType.MYSQL,
                        "outfile",
                        "SELECT id FROM app_user WHERE id = 1 INTO OUTFILE '/tmp/gateway_escape'"),
                Arguments.of(
                        DatabaseType.MYSQL,
                        "dumpfile",
                        "SELECT name FROM app_user WHERE id = 1 INTO DUMPFILE '/tmp/gateway_escape'"),
                Arguments.of(
                        DatabaseType.OCEANBASE_ORACLE,
                        "select into variable",
                        "SELECT id INTO target_id FROM app_user WHERE id = 1"));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("lockingSelectCases")
    void rejectsLockingSelects(DatabaseType databaseType, String label, String sql) {
        assertRejectedWithCode(databaseType, sql, "LOCKING_SELECT_FORBIDDEN");
    }

    static Stream<Arguments> lockingSelectCases() {
        return Stream.of(
                Arguments.of(
                        DatabaseType.MYSQL,
                        "for update",
                        "SELECT id FROM app_user WHERE id = 1 FOR UPDATE"),
                Arguments.of(
                        DatabaseType.MYSQL,
                        "share lock",
                        "SELECT id FROM app_user WHERE id = 1 LOCK IN SHARE MODE"),
                Arguments.of(
                        DatabaseType.OCEANBASE_ORACLE,
                        "for update nowait",
                        "SELECT id FROM app_user WHERE id = 1 FOR UPDATE NOWAIT"),
                Arguments.of(
                        DatabaseType.OCEANBASE_ORACLE,
                        "for update skip locked",
                        "SELECT id FROM app_user WHERE id = 1 FOR UPDATE SKIP LOCKED"));
    }

    @Test
    void rejectsMysqlRecursiveCte() {
        assertRejectedWithCode(
                DatabaseType.MYSQL,
                "WITH RECURSIVE sequence_numbers(n) AS ("
                        + "SELECT 1 UNION ALL SELECT n + 1 FROM sequence_numbers WHERE n < 10"
                        + ") SELECT n FROM sequence_numbers",
                "RECURSIVE_CTE_FORBIDDEN");
    }

    @Test
    void rejectsOracleRecursiveCteWithoutRecursiveKeyword() {
        // Oracle 递归子查询分解不使用 RECURSIVE 关键字，自引用仍必须被识别并拒绝。
        assertSafelyRejected(
                DatabaseType.OCEANBASE_ORACLE,
                "WITH sequence_numbers(n) AS ("
                        + "SELECT 1 FROM dual "
                        + "UNION ALL SELECT n + 1 FROM sequence_numbers WHERE n < 10"
                        + ") SELECT n FROM sequence_numbers");
    }

    @Test
    void rejectsOracleDatabaseLink() {
        assertRejectedWithCode(
                DatabaseType.OCEANBASE_ORACLE,
                "SELECT id FROM app.remote_users@production_link WHERE id = 1",
                "DATABASE_LINK_FORBIDDEN");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unknownFunctionCases")
    void rejectsUnknownOrSideEffectFunctions(DatabaseType databaseType, String label, String sql) {
        assertRejectedWithCode(databaseType, sql, "UNKNOWN_FUNCTION_FORBIDDEN");
    }

    static Stream<Arguments> unknownFunctionCases() {
        return Stream.of(
                Arguments.of(DatabaseType.MYSQL, "sleep", "SELECT SLEEP(10) FROM app_user WHERE id = 1"),
                Arguments.of(
                        DatabaseType.MYSQL,
                        "load file",
                        "SELECT LOAD_FILE('/etc/passwd') FROM app_user WHERE id = 1"),
                Arguments.of(
                        DatabaseType.OCEANBASE_ORACLE,
                        "dbms lock",
                        "SELECT DBMS_LOCK.SLEEP(10) FROM dual"),
                Arguments.of(
                        DatabaseType.OCEANBASE_ORACLE,
                        "network package",
                        "SELECT UTL_HTTP.REQUEST('http://127.0.0.1/') FROM dual"));
    }

    @Test
    void rejectsMysqlProcedureClause() {
        assertSafelyRejected(
                DatabaseType.MYSQL,
                "SELECT id FROM app_user WHERE id = 1 PROCEDURE ANALYSE()");
    }

    @Test
    void rejectsMysqlSessionVariableAssignment() {
        assertRejectedWithCode(
                DatabaseType.MYSQL,
                "SELECT @gateway_probe := id FROM app_user WHERE id = 1",
                "SESSION_VARIABLE_FORBIDDEN");
    }

    @Test
    void flagsOracleDictionaryViewsForApproval() {
        SqlAnalysis analysis = policy.analyze(
                DatabaseType.OCEANBASE_ORACLE,
                "SELECT username FROM DBA_USERS WHERE username = ?",
                100);

        assertThat(analysis.riskReasons()).contains("SYSTEM_SCHEMA");
    }

    @Test
    void rejectsOracleSequenceMutation() {
        // NEXTVAL 会推进序列，即使语句外形是 SELECT 也不是无副作用查询。
        assertSafelyRejected(
                DatabaseType.OCEANBASE_ORACLE,
                "SELECT order_sequence.NEXTVAL FROM dual");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unicodeAndMalformedCases")
    void rejectsUnicodeConfusionAndParseFailures(
            DatabaseType databaseType, String label, String sql) {
        assertSafelyRejected(databaseType, sql);
    }

    static Stream<Arguments> unicodeAndMalformedCases() {
        return Stream.of(
                sqlForBoth("full-width keyword", "ＳＥＬＥＣＴ id FROM app_user"),
                sqlForBoth("zero-width keyword split", "SEL\u200BECT id FROM app_user"),
                sqlForBoth("Cyrillic homoglyph", "SELE\u0421T id FROM app_user"),
                sqlForBoth("unterminated string", "SELECT 'unterminated FROM app_user"),
                sqlForBoth("unbalanced expression", "SELECT (id FROM app_user"))
                .flatMap(stream -> stream);
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"MYSQL", "OCEANBASE_ORACLE"})
    void permitsCommentTokensInsideQuotedText(DatabaseType databaseType) {
        assertThatCode(() -> policy.analyze(
                        databaseType,
                        "SELECT '; -- # /* @ not comments */' AS marker FROM app_user WHERE id = 1",
                        100))
                .doesNotThrowAnyException();
    }

    private void assertSafelyRejected(DatabaseType databaseType, String sql) {
        assertThatThrownBy(() -> policy.analyze(databaseType, sql, 100))
                .as("%s must reject SQL safely: %s", databaseType, sql)
                .isInstanceOfSatisfying(GatewayException.class, exception -> {
                    assertThat(exception.status().is4xxClientError()).isTrue();
                    assertThat(exception.code()).isNotBlank();
                });
    }

    private void assertRejectedWithCode(DatabaseType databaseType, String sql, String expectedCode) {
        assertThatThrownBy(() -> policy.analyze(databaseType, sql, 100))
                .as("%s must reject SQL with %s: %s", databaseType, expectedCode, sql)
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static Stream<Arguments> sqlForBoth(String label, String sql) {
        return Stream.of(
                Arguments.of(DatabaseType.MYSQL, label, sql),
                Arguments.of(DatabaseType.OCEANBASE_ORACLE, label, sql));
    }

    private static Stream<Arguments> sqlForBoth(String label, String sql, String expectedCode) {
        return Stream.of(
                Arguments.of(DatabaseType.MYSQL, label, sql, expectedCode),
                Arguments.of(DatabaseType.OCEANBASE_ORACLE, label, sql, expectedCode));
    }
}
