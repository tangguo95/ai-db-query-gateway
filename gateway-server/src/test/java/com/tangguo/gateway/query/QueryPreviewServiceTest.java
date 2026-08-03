package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.api.ApiDtos.QueryCreateRequest;
import com.tangguo.gateway.api.ApiDtos.QueryParameter;
import com.tangguo.gateway.api.ApiDtos.QueryPreview;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditCryptoService;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.ConnectorRegistry;
import com.tangguo.gateway.datasource.DataSourceConfig;
import com.tangguo.gateway.datasource.DataSourceConnectionManager;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class QueryPreviewServiceTest {
    private DataSourceService dataSourceService;
    private DataSourceConnectionManager connections;
    private ConnectorRegistry connectors;
    private SqlPolicyService sqlPolicy;
    private QueryRepository queryRepository;
    private AuditService auditService;
    private ActorContext actorContext;
    private QueryService service;

    @BeforeEach
    void setUp() {
        dataSourceService = mock(DataSourceService.class);
        connections = mock(DataSourceConnectionManager.class);
        connectors = mock(ConnectorRegistry.class);
        sqlPolicy = mock(SqlPolicyService.class);
        queryRepository = mock(QueryRepository.class);
        auditService = mock(AuditService.class);
        actorContext = mock(ActorContext.class);
        when(actorContext.actor()).thenReturn("admin");
        when(actorContext.actorType()).thenReturn(ActorType.ADMIN);

        service = new QueryService(
                dataSourceService,
                connections,
                connectors,
                sqlPolicy,
                queryRepository,
                mock(AuditCryptoService.class),
                auditService,
                actorContext,
                new GatewayProperties(),
                mock(QueryApprovalPolicyService.class),
                new ObjectMapper());
    }

    @Test
    void previewsPolicyWithoutPersistingOrOpeningBusinessConnection() {
        String sql = "SELECT * FROM sales.orders WHERE id = ?";
        QueryCreateRequest request = new QueryCreateRequest(
                "ds-1",
                sql,
                List.of(new QueryParameter("LONG", "42")),
                "核对订单",
                500,
                "0b06b0a7-f58e-4fcb-8730-10d2940259aa");
        when(dataSourceService.requireEnabled("ds-1")).thenReturn(dataSource());
        when(sqlPolicy.analyze(DatabaseType.MYSQL, sql, 500))
                .thenReturn(new SqlAnalysis(
                        "fingerprint",
                        Set.of("sales"),
                        Set.of("sales.orders"),
                        List.of("SELECT_ALL", "MAX_ROWS_OVER_AUTO_LIMIT"),
                        1));

        QueryPreview preview = service.preview(request);

        assertThat(preview.dataSourceId()).isEqualTo("ds-1");
        assertThat(preview.readOnlyStatus()).isEqualTo(ReadOnlyStatus.STRICT);
        assertThat(preview.schemas()).containsExactly("sales");
        assertThat(preview.tables()).containsExactly("sales.orders");
        assertThat(preview.riskReasons()).containsExactly("SELECT_ALL", "MAX_ROWS_OVER_AUTO_LIMIT");
        assertThat(preview.effectiveMaxRows()).isEqualTo(500);
        assertThat(preview.parameterCount()).isEqualTo(1);
        assertThat(preview.sqlFingerprint()).isEqualTo("fingerprint");
        verifyNoInteractions(connections, connectors, queryRepository);

        ArgumentCaptor<AuditCommand> auditCaptor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(auditService, org.mockito.Mockito.times(2)).record(auditCaptor.capture());
        List<AuditCommand> audits = auditCaptor.getAllValues();
        assertThat(audits.get(0).eventType()).isEqualTo("QUERY_PREVIEW_REQUESTED");
        assertThat(audits.get(0).status()).isEqualTo("REQUESTED");
        assertThat(audits.get(0).sqlFingerprint()).isNull();
        assertThat(audits.get(0).sensitivePayload())
                .containsEntry("sql", sql)
                .containsEntry("parameters", request.parameters());
        assertThat(audits.get(1).eventType()).isEqualTo("QUERY_PREVIEW_SUCCEEDED");
        assertThat(audits.get(1).status()).isEqualTo("SUCCESS");
        assertThat(audits.get(1).sqlFingerprint()).isEqualTo("fingerprint");
        assertThat(audits.get(1).sensitivePayload()).doesNotContainKey("sql").doesNotContainKey("parameters");
    }

    @Test
    void auditsAndRejectsPlaceholderMismatchWithoutPersistenceOrConnection() {
        String sql = "SELECT id FROM sales.orders WHERE id = ? AND tenant_id = ?";
        QueryCreateRequest request = new QueryCreateRequest(
                "ds-1",
                sql,
                List.of(new QueryParameter("LONG", 42)),
                "核对订单",
                200,
                null);
        when(dataSourceService.requireEnabled("ds-1")).thenReturn(dataSource());
        when(sqlPolicy.analyze(DatabaseType.MYSQL, sql, 200))
                .thenReturn(new SqlAnalysis(
                        "fingerprint", Set.of("sales"), Set.of("sales.orders"), List.of(), 2));

        assertThatThrownBy(() -> service.preview(request))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PARAMETER_COUNT_MISMATCH"));

        verifyNoInteractions(connections, connectors, queryRepository);
        ArgumentCaptor<AuditCommand> auditCaptor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(auditService, org.mockito.Mockito.times(2)).record(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .extracting(AuditCommand::eventType)
                .containsExactly("QUERY_PREVIEW_REQUESTED", "QUERY_PREVIEW_REJECTED");
        AuditCommand rejection = auditCaptor.getAllValues().get(1);
        assertThat(rejection.sqlFingerprint()).isEqualTo("fingerprint");
        assertThat(rejection.errorCode()).isEqualTo("PARAMETER_COUNT_MISMATCH");
        assertThat(rejection.sensitivePayload()).containsEntry("reason", "PARAMETER_COUNT_MISMATCH");
    }

    @Test
    void rejectsInvalidParameterBeforePolicyAnalysis() {
        QueryCreateRequest request = new QueryCreateRequest(
                "ds-1",
                "SELECT id FROM sales.orders WHERE id = ?",
                List.of(new QueryParameter("OBJECT", "42")),
                "核对订单",
                200,
                null);
        when(dataSourceService.requireEnabled("ds-1")).thenReturn(dataSource());

        assertThatThrownBy(() -> service.preview(request))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("UNSUPPORTED_PARAMETER_TYPE"));

        verify(sqlPolicy, never()).analyze(DatabaseType.MYSQL, request.sql(), 200);
        verifyNoInteractions(connections, connectors, queryRepository);
        ArgumentCaptor<AuditCommand> auditCaptor = ArgumentCaptor.forClass(AuditCommand.class);
        verify(auditService, org.mockito.Mockito.times(2)).record(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .extracting(AuditCommand::eventType)
                .containsExactly("QUERY_PREVIEW_REQUESTED", "QUERY_PREVIEW_REJECTED");
    }

    private DataSourceConfig dataSource() {
        Instant now = Instant.now();
        return new DataSourceConfig(
                "ds-1",
                "生产库",
                DatabaseType.MYSQL,
                "keychain-ref",
                1L,
                ReadOnlyStatus.STRICT,
                true,
                false,
                10,
                now,
                "ok",
                now,
                now);
    }
}
