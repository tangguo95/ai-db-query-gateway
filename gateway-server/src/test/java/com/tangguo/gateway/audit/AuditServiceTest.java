package com.tangguo.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.secret.InMemorySecretStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

class AuditServiceTest {
    private JdbcTemplate jdbcTemplate;
    private AuditService auditService;

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
        AuditCryptoService crypto = new AuditCryptoService(new InMemorySecretStore());
        crypto.initializeKeys();
        auditService = new AuditService(jdbcTemplate, new ObjectMapper(), crypto);
        auditService.verifyAtStartup();
    }

    @Test
    void writesEncryptedPayloadAndDetectsTampering() {
        auditService.record(new AuditCommand(
                "admin",
                ActorType.ADMIN,
                "QUERY_REQUESTED",
                "ds-1",
                "q-1",
                "核对订单",
                "fingerprint",
                Map.of("sql", "SELECT secret FROM production"),
                "REQUESTED",
                null,
                null,
                null,
                null));
        auditService.record(AuditCommand.simple(
                "admin", ActorType.ADMIN, "QUERY_EXECUTED", "SUCCESS", Map.of("rows", 1)));

        String encrypted =
                jdbcTemplate.queryForObject("SELECT encrypted_payload FROM audit_event LIMIT 1", String.class);
        assertThat(encrypted).doesNotContain("SELECT secret");
        assertThat(auditService.verifyChain()).isTrue();

        jdbcTemplate.update("UPDATE audit_event SET status = 'TAMPERED' WHERE sequence_no = 1");
        assertThat(auditService.verifyChain()).isFalse();
    }

    @Test
    void refusesFurtherRecordsAfterChainVerificationFails() {
        auditService.record(AuditCommand.simple(
                "admin", ActorType.ADMIN, "QUERY_REQUESTED", "REQUESTED", Map.of()));
        jdbcTemplate.update("UPDATE audit_event SET record_hmac = 'TAMPERED' WHERE sequence_no = 1");

        assertThat(auditService.verifyChain()).isFalse();
        assertThat(auditService.isChainValid()).isFalse();
        long countBefore =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);

        assertThatThrownBy(() -> auditService.record(AuditCommand.simple(
                        "admin", ActorType.ADMIN, "QUERY_EXECUTED", "SUCCESS", Map.of())))
                .isInstanceOfSatisfying(GatewayException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("AUDIT_CHAIN_INVALID");
                    assertThat(exception.status().value()).isEqualTo(503);
                });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class))
                .isEqualTo(countBefore);
    }

    @Test
    void filtersAuditTrailByEventPrefixStatusAndQueryId() {
        String queryId = "7d645ef8-51f1-4f80-b803-baa28fa29f6c";
        auditService.record(new AuditCommand(
                "token:codex",
                ActorType.API_TOKEN,
                "QUERY_REQUESTED",
                "ds-1",
                queryId,
                "核对订单",
                "fingerprint",
                Map.of(),
                "REQUESTED",
                null,
                null,
                null,
                null));
        auditService.record(AuditCommand.simple(
                "admin", ActorType.ADMIN, "TOKEN_CREATED", "SUCCESS", Map.of()));

        var page = auditService.findPage(0, 50, "query", "requested", queryId);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.eventType()).isEqualTo("QUERY_REQUESTED");
            assertThat(item.queryId()).isEqualTo(queryId);
        });
    }

    @Test
    void rejectsMalformedAuditFilters() {
        assertThatThrownBy(() -> auditService.findPage(0, 50, "QUERY%", null, null))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_AUDIT_FILTER"));
        assertThatThrownBy(() -> auditService.findPage(0, 50, null, null, "not-a-uuid"))
                .isInstanceOf(GatewayException.class);
    }
}
