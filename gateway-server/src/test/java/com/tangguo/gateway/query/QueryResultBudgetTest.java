package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.api.ApiDtos.QueryResult;
import com.tangguo.gateway.api.ApiDtos.QueryParameter;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCryptoService;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.ConnectorRegistry;
import com.tangguo.gateway.datasource.DataSourceConnectionManager;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.security.ActorContext;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QueryResultBudgetTest {
    private QueryService service;

    @BeforeEach
    void setUp() {
        service = new QueryService(
                mock(DataSourceService.class),
                mock(DataSourceConnectionManager.class),
                mock(ConnectorRegistry.class),
                mock(SqlPolicyService.class),
                mock(QueryRepository.class),
                mock(AuditCryptoService.class),
                mock(AuditService.class),
                mock(ActorContext.class),
                new GatewayProperties(),
                new ObjectMapper());
    }

    @Test
    void truncatesUtf8FieldWithoutBreakingCodePoints() throws Exception {
        ResultSet resultSet = singleTextColumn("payload", "汉字🙂".repeat(100));

        QueryResult result =
                service.readResult(resultSet, "query-1", 10, 16_000, 48, System.nanoTime());

        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows().getFirst().getFirst().toString())
                .endsWith("…[TRUNCATED]")
                .doesNotContain("\uFFFD");
        assertThat(result.byteCount()).isLessThanOrEqualTo(16_000);
    }

    @Test
    void doesNotAddPartialRowWhenResponseBudgetWouldBeExceeded() throws Exception {
        ResultSet resultSet = singleTextColumn("payload", "x".repeat(80));

        QueryResult result =
                service.readResult(resultSet, "query-2", 10, 105, 256, System.nanoTime());

        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rejectsStructuredAndOversizedBindValuesBeforeAuditPayloadEncryption() {
        assertThatThrownBy(() -> service.validateParameters(
                        List.of(new QueryParameter("STRING", Map.of("nested", "value")))))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INVALID_PARAMETER_VALUE"));

        assertThatThrownBy(() -> service.validateParameters(
                        List.of(new QueryParameter("STRING", "x".repeat(262_145)))))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PARAMETER_TOO_LARGE"));
    }

    private ResultSet singleTextColumn(String label, String value) throws Exception {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn(label);
        when(metadata.getColumnName(1)).thenReturn(label);
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnTypeName(1)).thenReturn("VARCHAR");
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getCharacterStream(1)).thenReturn(new StringReader(value));
        return resultSet;
    }
}
