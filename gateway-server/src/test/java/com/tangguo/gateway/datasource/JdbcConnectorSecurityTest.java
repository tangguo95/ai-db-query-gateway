package com.tangguo.gateway.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.secret.ConnectionSecret;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcConnectorSecurityTest {

    @Test
    void mysqlUrlAlwaysDisablesMultiQueriesAndLocalFileLoading() {
        String url = new MySqlJdbcConnector()
                .jdbcUrl(new ConnectionSecret(
                        "db.example.com", 3306, "orders", "reader", "secret", Map.of("tlsMode", "REQUIRED")));

        assertThat(url)
                .contains("sslMode=REQUIRED")
                .contains("allowMultiQueries=false")
                .contains("allowLoadLocalInfile=false")
                .contains("allowUrlInLocalInfile=false")
                .contains("useCursorFetch=true")
                .contains("useServerPrepStmts=true")
                .doesNotContain("secret")
                .doesNotContain("reader");
    }

    @Test
    void mysqlUrlDefaultsToEncryptedTlsWithoutCertificateVerification() {
        String url = new MySqlJdbcConnector()
                .jdbcUrl(new ConnectionSecret(
                        "db.example.com", 3306, "orders", "reader", "secret", Map.of()));

        assertThat(url).contains("sslMode=REQUIRED");
    }

    @Test
    void mysqlGrantClassificationFailsClosedForRolesAndUnknownDynamicPrivileges() {
        MySqlJdbcConnector connector = new MySqlJdbcConnector();

        assertThat(connector
                        .classifyGrants(
                                "8.4",
                                "reader@%",
                                List.of(
                                        "GRANT USAGE ON *.* TO `reader`@`%`",
                                        "GRANT `writer_role`@`%` TO `reader`@`%`"))
                        .status())
                .isEqualTo(ReadOnlyStatus.BLOCKED);
        assertThat(connector
                        .classifyGrants(
                                "8.4",
                                "reader@%",
                                List.of("GRANT BACKUP_ADMIN ON *.* TO `reader`@`%`"))
                        .status())
                .isEqualTo(ReadOnlyStatus.BLOCKED);
    }

    @Test
    void mysqlGrantClassificationDistinguishesStrictAndCompatibilityAccounts() {
        MySqlJdbcConnector connector = new MySqlJdbcConnector();

        assertThat(connector
                        .classifyGrants(
                                "8.4",
                                "reader@%",
                                List.of(
                                        "GRANT USAGE ON *.* TO `reader`@`%`",
                                        "GRANT SELECT, SHOW VIEW ON `orders`.* TO `reader`@`%`"))
                        .status())
                .isEqualTo(ReadOnlyStatus.STRICT);
        assertThat(connector
                        .classifyGrants(
                                "8.4",
                                "business@%",
                                List.of("GRANT SELECT, INSERT (status), UPDATE ON `orders`.* TO `business`@`%`"))
                        .status())
                .isEqualTo(ReadOnlyStatus.COMPATIBILITY);
    }

    @Test
    void oracleUrlUsesFixedTlsMappingOnly() {
        String url = new OceanBaseOracleJdbcConnector()
                .jdbcUrl(new ConnectionSecret(
                        "ob.example.com", 2881, "tenant", "reader", "secret", Map.of("tlsMode", "VERIFY_IDENTITY")));

        assertThat(url)
                .contains("useSSL=true")
                .contains("verifyServerCertificate=true")
                .doesNotContain("secret")
                .doesNotContain("reader");
    }

    @Test
    void oracleUrlDefaultsToEncryptedTlsWithoutCertificateVerification() {
        String url = new OceanBaseOracleJdbcConnector()
                .jdbcUrl(new ConnectionSecret(
                        "ob.example.com", 2881, "tenant", "reader", "secret", Map.of()));

        assertThat(url)
                .contains("useSSL=true")
                .contains("verifyServerCertificate=false");
    }

    @Test
    void oraclePrivilegeInspectionFallsBackWhenSessionPrivilegesViewIsMissing() throws SQLException {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        PrivilegeInspection inspection =
                connector.inspectPrivileges(oracleConnectionWithSessionPrivilegeFailure(
                        new SQLException("view missing", "42S02", 942)));

        assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.STRICT);
        assertThat(inspection.findings())
                .contains("SESSION_PRIVS 不可用，已使用用户与角色授权视图完成复检");
    }

    @Test
    void oraclePrivilegeInspectionDoesNotIgnoreOtherSessionPrivilegeErrors() throws SQLException {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        assertThatThrownBy(() -> connector.inspectPrivileges(
                        oracleConnectionWithSessionPrivilegeFailure(
                                new SQLException("insufficient privileges", "42000", 1031))))
                .isInstanceOf(SQLException.class)
                .hasFieldOrPropertyWithValue("errorCode", 1031);
    }

    @Test
    void oraclePrivilegeClassificationAcceptsOnlyKnownReadPrivileges() {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        PrivilegeInspection inspection = connector.classifyPrivileges(
                "4.4",
                "APP_READER",
                List.of(
                        systemPrivilege("create session"),
                        systemPrivilege("select any table"),
                        systemPrivilege("select any dictionary"),
                        objectPrivilege("select"),
                        objectPrivilege("read")),
                List.of(new OceanBaseOracleJdbcConnector.GrantedRole("connect", false)),
                List.of());

        assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.STRICT);
        assertThat(inspection.findings()).isEmpty();
    }

    @Test
    void oraclePrivilegeClassificationMarksBusinessWritesAsCompatibility() {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        PrivilegeInspection inspection = connector.classifyPrivileges(
                "4.4",
                "APP_OWNER",
                List.of(
                        systemPrivilege("CREATE SESSION"),
                        systemPrivilege("CREATE TABLE"),
                        objectPrivilege("UPDATE")),
                List.of(),
                List.of("TABLE"));

        assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.COMPATIBILITY);
        assertThat(inspection.findings())
                .anyMatch(finding -> finding.contains("CREATE TABLE"))
                .anyMatch(finding -> finding.contains("UPDATE"))
                .anyMatch(finding -> finding.contains("TABLE"));
    }

    @Test
    void oraclePrivilegeClassificationBlocksHighRiskAndGrantablePrivileges() {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        PrivilegeInspection inspection = connector.classifyPrivileges(
                "4.4",
                "POWER_USER",
                List.of(
                        systemPrivilege("CREATE SESSION"),
                        systemPrivilege("ALTER SYSTEM"),
                        new OceanBaseOracleJdbcConnector.GrantedPrivilege(
                                "SELECT",
                                true,
                                OceanBaseOracleJdbcConnector.PrivilegeKind.OBJECT)),
                List.of(new OceanBaseOracleJdbcConnector.GrantedRole("REPORT_ADMIN", true)),
                List.of("DATABASE LINK"));

        assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.BLOCKED);
        assertThat(inspection.findings())
                .anyMatch(finding -> finding.contains("ALTER SYSTEM"))
                .anyMatch(finding -> finding.contains("GRANTABLE"))
                .anyMatch(finding -> finding.contains("ADMIN OPTION"))
                .anyMatch(finding -> finding.contains("DATABASE LINK"));
    }

    @Test
    void oraclePrivilegeClassificationFailsClosedForUnknownPrivileges() {
        OceanBaseOracleJdbcConnector connector = new OceanBaseOracleJdbcConnector();

        PrivilegeInspection inspection = connector.classifyPrivileges(
                "future-version",
                "APP_READER",
                List.of(
                        systemPrivilege("CREATE SESSION"),
                        systemPrivilege("FUTURE_SYSTEM_PRIVILEGE"),
                        objectPrivilege("FUTURE_OBJECT_PRIVILEGE")),
                List.of(),
                List.of());

        assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.BLOCKED);
        assertThat(inspection.findings())
                .anyMatch(finding -> finding.contains("FUTURE_SYSTEM_PRIVILEGE"))
                .anyMatch(finding -> finding.contains("FUTURE_OBJECT_PRIVILEGE"));
    }

    private OceanBaseOracleJdbcConnector.GrantedPrivilege systemPrivilege(String name) {
        return new OceanBaseOracleJdbcConnector.GrantedPrivilege(
                name, false, OceanBaseOracleJdbcConnector.PrivilegeKind.SYSTEM);
    }

    private OceanBaseOracleJdbcConnector.GrantedPrivilege objectPrivilege(String name) {
        return new OceanBaseOracleJdbcConnector.GrantedPrivilege(
                name, false, OceanBaseOracleJdbcConnector.PrivilegeKind.OBJECT);
    }

    private Connection oracleConnectionWithSessionPrivilegeFailure(SQLException failure)
            throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet currentUser = mock(ResultSet.class);
        ResultSet userSystemPrivileges = mock(ResultSet.class);
        ResultSet empty = mock(ResultSet.class);

        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductVersion()).thenReturn("4.4");
        when(connection.createStatement()).thenReturn(statement);
        when(currentUser.next()).thenReturn(true, false);
        when(currentUser.getString(1)).thenReturn("APP_READER");
        when(userSystemPrivileges.next()).thenReturn(true, false);
        when(userSystemPrivileges.getString(1)).thenReturn("CREATE SESSION");
        when(userSystemPrivileges.getString(2)).thenReturn("NO");
        when(empty.next()).thenReturn(false);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if ("SELECT USER FROM DUAL".equals(sql)) {
                return currentUser;
            }
            if ("SELECT PRIVILEGE FROM SESSION_PRIVS".equals(sql)) {
                throw failure;
            }
            if (sql.contains("USER_SYS_PRIVS")) {
                return userSystemPrivileges;
            }
            return empty;
        });
        return connection;
    }
}
