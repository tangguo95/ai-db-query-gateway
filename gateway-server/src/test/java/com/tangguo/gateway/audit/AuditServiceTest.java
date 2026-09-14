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
    void includesAutoApprovedRiskQueriesInApprovalFilter() {
        auditService.record(AuditCommand.simple(
                "token:codex", ActorType.API_TOKEN, "QUERY_POLICY_AUTO_APPROVED", "APPROVED", Map.of()));

        var page = auditService.findPage(0, 50, "approval", null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(item -> assertThat(item.eventType()).isEqualTo("QUERY_POLICY_AUTO_APPROVED"));
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

    @Test
    void dateRangeUsesInclusiveStartExclusiveEndAndSameExportFilter() {
        for (int i = 0; i < 3; i++) {
            auditService.record(AuditCommand.simple(
                    "admin", ActorType.ADMIN, "QUERY_APPROVED", "APPROVED", Map.of()));
        }
        // 仅修改测试数据的时间，用来覆盖不同 ISO 小数秒格式和时间边界。
        jdbcTemplate.update("UPDATE audit_event SET occurred_at = '2026-08-01T00:00:00Z' WHERE sequence_no=1");
        jdbcTemplate.update("UPDATE audit_event SET occurred_at = '2026-08-31T23:59:59.999Z' WHERE sequence_no=2");
        jdbcTemplate.update("UPDATE audit_event SET occurred_at = '2026-09-01T00:00:00Z' WHERE sequence_no=3");
        var from = java.time.Instant.parse("2026-08-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-09-01T00:00:00Z");
        var page = auditService.findPage(0, 1, "APPROVAL", "APPROVED", null, from, to);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(auditService.exportRecords("APPROVAL", "APPROVED", null, from, to))
                .extracting(item -> item.sequenceNo()).containsExactly(2L, 1L);
        assertThatThrownBy(() -> auditService.findPage(0, 25, null, null, null, to, from))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void excelIsValidXmlAndTreatsUserTextAsText() throws Exception {
        auditService.record(new AuditCommand("admin", ActorType.ADMIN, "QUERY_APPROVED", null, null,
                "=1+1 <核对>&", null, Map.of(), "APPROVED", 12L, 3, 40L, null));
        byte[] workbook = AuditExcel.write(auditService.findPage(0, 25, null, null, null).items());
        int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] xml = zip.readAllBytes();
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(xml));
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    String sheet = new String(xml, StandardCharsets.UTF_8);
                    assertThat(sheet).contains("=1+1 &lt;核对&gt;&amp;", "审批通过", "<v>12</v>")
                            .doesNotContain("<f>");
                }
                entries++;
            }
        }
        assertThat(entries).isEqualTo(5);
    }
}
