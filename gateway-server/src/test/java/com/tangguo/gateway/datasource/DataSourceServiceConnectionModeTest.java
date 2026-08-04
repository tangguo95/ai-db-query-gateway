package com.tangguo.gateway.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.secret.ConnectionSecret;
import com.tangguo.gateway.secret.SecretStore;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DataSourceServiceConnectionModeTest {

    @Test
    void connectionTestDoesNotInspectDatabasePrivileges() throws Exception {
        DataSourceRepository repository = mock(DataSourceRepository.class);
        SecretStore secretStore = mock(SecretStore.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        DataSourceConnectionManager connections = mock(DataSourceConnectionManager.class);
        ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
        AuditService auditService = mock(AuditService.class);
        DataSourceRecoveryPolicyService recoveryPolicy = mock(DataSourceRecoveryPolicyService.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Instant now = Instant.parse("2026-07-30T10:00:00Z");
        DataSourceConfig isolated = config(ReadOnlyStatus.UNKNOWN, false, now);
        DataSourceConfig enabled = config(ReadOnlyStatus.COMPATIBILITY, true, now);

        when(repository.require("ds-1")).thenReturn(isolated, enabled);
        when(repository.beginInspection("ds-1", 1)).thenReturn(true);
        when(repository.updateTestResult(
                        eq("ds-1"),
                        eq(1L),
                        eq(ReadOnlyStatus.COMPATIBILITY),
                        any(String.class),
                        any(Instant.class)))
                .thenReturn(true);
        when(connections.connection(isolated)).thenReturn(connection);
        when(connections.secret(isolated))
                .thenReturn(new ConnectionSecret(
                        "db.example.com",
                        3306,
                        "orders",
                        "app_user",
                        "secret",
                        Map.of("tlsMode", "REQUIRED")));
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductVersion()).thenReturn("8.4");
        when(metadata.getUserName()).thenReturn("app_user");

        DataSourceService service = new DataSourceService(
                repository,
                secretStore,
                objectMapper,
                connections,
                connectorRegistry,
                auditService,
                recoveryPolicy);

        var result = service.test("ds-1", "admin");

        assertThat(result.readOnlyStatus()).isEqualTo(ReadOnlyStatus.COMPATIBILITY);
        assertThat(result.enabled()).isTrue();
        assertThat(result.findings())
                .contains("查询操作由网关统一执行只读控制");
        assertThat(result.message())
                .isEqualTo("连接检查通过，查询操作由网关统一执行只读控制");
        verify(repository)
                .updateTestResult(
                        eq("ds-1"),
                        eq(1L),
                        eq(ReadOnlyStatus.COMPATIBILITY),
                        any(String.class),
                        any(Instant.class));
        verifyNoInteractions(connectorRegistry);
    }

    private DataSourceConfig config(ReadOnlyStatus status, boolean enabled, Instant now) {
        return new DataSourceConfig(
                "ds-1",
                "orders",
                DatabaseType.MYSQL,
                "secret-ref",
                1,
                status,
                enabled,
                false,
                10,
                now,
                "state",
                now,
                now);
    }
}
