package com.tangguo.gateway.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.secret.ConnectionSecret;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlJdbcConnectorTestcontainersTest {
    private static final String DATABASE = "gateway_test";
    private static final String READER = "gateway_reader";
    private static final String COMPATIBLE = "gateway_compatible";
    private static final String BLOCKED = "gateway_blocked";
    private static final String TEST_PASSWORD = "test-" + UUID.randomUUID();
    private static final String ROOT_PASSWORD = "root-" + UUID.randomUUID();

    private final MySqlJdbcConnector connector = new MySqlJdbcConnector();
    private MySQLContainer mysql;

    @BeforeAll
    void startMysqlAndCreatePrivilegeFixtures() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "MySQL Testcontainers 集成测试需要可用的 Docker daemon");
        mysql = new MySQLContainer("mysql:8.4.6")
                .withDatabaseName(DATABASE)
                .withUsername("root")
                .withPassword(ROOT_PASSWORD);
        mysql.start();

        try (Connection connection =
                        DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE order_probe (id BIGINT PRIMARY KEY, note VARCHAR(64) NOT NULL)");
            statement.execute("INSERT INTO order_probe(id, note) VALUES (1, 'existing')");

            createUser(statement, READER);
            statement.execute("GRANT SELECT, SHOW VIEW ON `" + DATABASE + "`.* TO '" + READER + "'@'%'");

            createUser(statement, COMPATIBLE);
            statement.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON `" + DATABASE + "`.* TO '" + COMPATIBLE + "'@'%'");

            createUser(statement, BLOCKED);
            statement.execute("GRANT SELECT ON `" + DATABASE + "`.* TO '" + BLOCKED + "'@'%'");
            statement.execute("GRANT FILE ON *.* TO '" + BLOCKED + "'@'%'");
        }
    }

    @AfterAll
    void stopMysql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void classifiesSelectOnlyAccountAsStrict() throws SQLException {
        try (Connection connection = connect(READER)) {
            PrivilegeInspection inspection = connector.inspectPrivileges(connection);

            assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.STRICT);
            assertThat(inspection.account()).startsWith(READER + "@");
            assertThat(inspection.databaseVersion()).startsWith("8.4.");
            assertThat(inspection.findings()).isEmpty();
        }
    }

    @Test
    void classifiesBusinessDmlAccountAsCompatibility() throws SQLException {
        try (Connection connection = connect(COMPATIBLE)) {
            PrivilegeInspection inspection = connector.inspectPrivileges(connection);

            assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.COMPATIBILITY);
            assertThat(inspection.findings())
                    .anyMatch(finding -> finding.startsWith("检测到写权限："));
        }
    }

    @Test
    void classifiesFilePrivilegeAsBlocked() throws SQLException {
        try (Connection connection = connect(BLOCKED)) {
            PrivilegeInspection inspection = connector.inspectPrivileges(connection);

            assertThat(inspection.status()).isEqualTo(ReadOnlyStatus.BLOCKED);
            assertThat(inspection.findings()).contains("检测到高危权限：FILE");
        }
    }

    @Test
    void databaseAllowsReaderSelectButRejectsInsert() throws SQLException {
        try (Connection connection = connect(READER);
                Statement statement = connection.createStatement()) {
            try (ResultSet resultSet =
                    statement.executeQuery("SELECT note FROM order_probe WHERE id = 1")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("existing");
            }

            assertThatThrownBy(
                            () -> statement.executeUpdate("INSERT INTO order_probe(id, note) VALUES (2, 'blocked')"))
                    .isInstanceOfSatisfying(SQLException.class, exception -> {
                        assertThat(exception.getSQLState()).isEqualTo("42000");
                        assertThat(exception.getMessage()).containsIgnoringCase("denied");
                    });

            try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM order_probe")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }
        }
    }

    private Connection connect(String username) throws SQLException {
        ConnectionSecret secret = new ConnectionSecret(
                mysql.getHost(),
                mysql.getMappedPort(MySQLContainer.MYSQL_PORT),
                DATABASE,
                username,
                TEST_PASSWORD,
                Map.of("tlsMode", "REQUIRED"));
        return DriverManager.getConnection(connector.jdbcUrl(secret), username, TEST_PASSWORD);
    }

    private void createUser(Statement statement, String username) throws SQLException {
        statement.execute("CREATE USER '" + username + "'@'%' IDENTIFIED BY '" + TEST_PASSWORD + "'");
    }
}
