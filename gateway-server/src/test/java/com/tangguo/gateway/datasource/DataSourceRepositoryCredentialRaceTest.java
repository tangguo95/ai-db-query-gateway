package com.tangguo.gateway.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DataSourceRepositoryCredentialRaceTest {
    private DataSourceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        executeMigration(jdbcTemplate, "/db/migration/V1__initial_schema.sql");
        executeMigration(jdbcTemplate, "/db/migration/V2__credential_version.sql");
        repository = new DataSourceRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-30T09:00:00Z");
        repository.insert(new DataSourceConfig(
                "ds-1",
                "orders",
                DatabaseType.MYSQL,
                "secret-old",
                1,
                ReadOnlyStatus.STRICT,
                true,
                false,
                10,
                now,
                "strict",
                now,
                now));
    }

    @Test
    void staleConnectionInspectionCannotEnableRotatedCredentials() {
        assertThat(repository.rotateSecret("ds-1", 1, "secret-new")).isTrue();

        assertThat(repository.updateTestResult(
                        "ds-1",
                        1,
                        ReadOnlyStatus.STRICT,
                        "stale strict result",
                        Instant.now()))
                .isFalse();

        DataSourceConfig current = repository.require("ds-1");
        assertThat(current.credentialVersion()).isEqualTo(2);
        assertThat(current.secretRef()).isEqualTo("secret-new");
        assertThat(current.readOnlyStatus()).isEqualTo(ReadOnlyStatus.UNKNOWN);
        assertThat(current.enabled()).isFalse();
    }

    @Test
    void unknownStatusCannotBeEnabledByOrdinaryUpdate() {
        assertThat(repository.rotateSecret("ds-1", 1, "secret-new")).isTrue();

        assertThat(repository.updateBasics("ds-1", 2, "orders", true, 10, true)).isTrue();

        assertThat(repository.require("ds-1").enabled()).isFalse();
    }

    @Test
    void connectionInspectionIsolatesBeforeOpeningDatabaseConnection() {
        assertThat(repository.beginInspection("ds-1", 1)).isTrue();

        DataSourceConfig isolated = repository.require("ds-1");
        assertThat(isolated.enabled()).isFalse();
        assertThat(isolated.readOnlyStatus()).isEqualTo(ReadOnlyStatus.UNKNOWN);
        assertThat(isolated.lastTestMessage()).contains("临时隔离");

        assertThat(repository.updateTestResult(
                        "ds-1",
                        1,
                        ReadOnlyStatus.STRICT,
                        "strict",
                        Instant.now()))
                .isTrue();
        assertThat(repository.require("ds-1").enabled()).isTrue();
    }

    @Test
    void applicationOnlyCompatibilityEnablesWithoutPrivilegeAcceptance() {
        assertThat(repository.updateTestResult(
                        "ds-1",
                        1,
                        ReadOnlyStatus.COMPATIBILITY,
                        "application only",
                        Instant.now()))
                .isTrue();

        DataSourceConfig current = repository.require("ds-1");
        assertThat(current.readOnlyStatus()).isEqualTo(ReadOnlyStatus.COMPATIBILITY);
        assertThat(current.enabled()).isTrue();
        assertThat(current.allowCompatibility()).isFalse();
    }

    private void executeMigration(JdbcTemplate jdbcTemplate, String resource) throws Exception {
        String migration = new String(
                getClass().getResourceAsStream(resource).readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
                jdbcTemplate.execute(statement);
            }
        }
    }
}
