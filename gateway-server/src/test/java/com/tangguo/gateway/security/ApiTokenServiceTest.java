package com.tangguo.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tangguo.gateway.api.ApiDtos.TokenCreateRequest;
import com.tangguo.gateway.api.ApiDtos.TokenScopeUpdateRequest;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

class ApiTokenServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ApiTokenService service;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        String migration = new String(
                getClass()
                        .getResourceAsStream("/db/migration/V1__initial_schema.sql")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
                jdbcTemplate.execute(statement);
            }
        }
        service = new ApiTokenService(
                jdbcTemplate,
                new ObjectMapper(),
                new GatewayProperties(),
                mock(AuditService.class));
    }

    @Test
    void onlyEnabledReadOnlyDataSourcesCanBeAddedToTokenScope() {
        insertDataSource("strict", "STRICT", true, false);
        insertDataSource("unknown", "UNKNOWN", false, false);

        var created = service.create(request("strict"), "admin");
        assertThat(created.dataSourceIds()).containsExactly("strict");

        assertThatThrownBy(() -> service.create(request("unknown"), "admin"))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DATASOURCE_NOT_AVAILABLE"));
    }

    @Test
    void applicationOnlyCompatibilityDataSourceDoesNotRequirePrivilegeAcceptance() {
        insertDataSource("compatible-accepted", "COMPATIBILITY", true, true);
        insertDataSource("compatible-denied", "COMPATIBILITY", true, false);

        assertThat(service.create(request("compatible-accepted"), "admin").dataSourceIds())
                .containsExactly("compatible-accepted");
        assertThat(service.create(request("compatible-denied"), "admin").dataSourceIds())
                .containsExactly("compatible-denied");
    }

    @Test
    void updatesScopeWithoutChangingRawTokenHashOrExpiry() {
        insertDataSource("strict", "STRICT", true, false);
        insertDataSource("new-source", "COMPATIBILITY", true, false);
        var created = service.create(request("strict"), "admin");
        String originalHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM api_token WHERE id = ?", String.class, created.id());

        var updated = service.updateScope(
                created.id(),
                new TokenScopeUpdateRequest(List.of("strict", "new-source", "new-source"), true),
                "admin");

        assertThat(updated.dataSourceIds()).containsExactly("strict", "new-source");
        assertThat(updated.expiresAt()).isEqualTo(created.expiresAt());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT token_hash FROM api_token WHERE id = ?", String.class, created.id()))
                .isEqualTo(originalHash);
        assertThat(service.authenticate(created.token()).dataSourceIds())
                .containsExactlyInAnyOrder("strict", "new-source");
    }

    @Test
    void rejectsUnavailableDataSourceAndKeepsPreviousScope() {
        insertDataSource("strict", "STRICT", true, false);
        insertDataSource("isolated", "COMPATIBILITY", false, false);
        var created = service.create(request("strict"), "admin");

        assertThatThrownBy(() -> service.updateScope(
                        created.id(),
                        new TokenScopeUpdateRequest(List.of("strict", "isolated"), true),
                        "admin"))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("DATASOURCE_NOT_AVAILABLE"));
        assertThat(service.list().getFirst().dataSourceIds()).containsExactly("strict");
    }

    @Test
    void expiredTokenScopeCannotBeChanged() {
        insertDataSource("strict", "STRICT", true, false);
        var created = service.create(request("strict"), "admin");
        jdbcTemplate.update(
                "UPDATE api_token SET expires_at = ? WHERE id = ?",
                Instant.parse("2020-01-01T00:00:00Z").toString(),
                created.id());

        assertThatThrownBy(() -> service.updateScope(
                        created.id(),
                        new TokenScopeUpdateRequest(List.of("strict"), true),
                        "admin"))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("TOKEN_EXPIRED"));
    }

    private TokenCreateRequest request(String dataSourceId) {
        return new TokenCreateRequest("test-token", List.of(dataSourceId), 30, true);
    }

    private void insertDataSource(String id, String status, boolean enabled, boolean allowCompatibility) {
        jdbcTemplate.update(
                """
                INSERT INTO data_source_config(
                    id, name, database_type, secret_ref, read_only_status, enabled, deleted,
                    allow_compatibility, query_timeout_seconds, created_at, updated_at
                ) VALUES (?, ?, 'MYSQL', ?, ?, ?, 0, ?, 10, '2026-07-30T00:00:00Z', '2026-07-30T00:00:00Z')
                """,
                id,
                id,
                "secret-" + id,
                status,
                enabled ? 1 : 0,
                allowCompatibility ? 1 : 0);
    }
}
