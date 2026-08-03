package com.tangguo.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.QueryStatus;
import com.tangguo.gateway.model.ReadOnlyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
    private ApiDtos() {}

    public record SetupStatus(boolean initialized, boolean bootstrapTokenRequired, String csrfHeaderName) {}

    public record SetupRequest(
            @NotBlank @Size(max = 128) String bootstrapToken,
            @NotBlank @Size(min = 12, max = 256) String password) {}

    public record LoginRequest(@NotBlank @Size(max = 256) String password) {}

    public record CurrentUser(boolean authenticated, String username, List<String> roles) {}

    public record QueryApprovalPolicyView(boolean approvalRequired) {}

    public record QueryApprovalPolicyUpdateRequest(boolean approvalRequired) {}

    public record DataSourceCreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull DatabaseType databaseType,
            @NotBlank @Size(max = 255) String host,
            @Min(1) @Max(65535) int port,
            @NotBlank @Size(max = 128) String database,
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 1024) String password,
            Map<String, String> properties,
            boolean allowCompatibility,
            @Min(5) @Max(30) Integer queryTimeoutSeconds) {}

    public record DataSourceUpdateRequest(
            @Size(min = 1, max = 100) String name,
            @Size(min = 1, max = 255) String host,
            @Min(1) @Max(65535) Integer port,
            @Size(min = 1, max = 128) String database,
            @Size(min = 1, max = 128) String username,
            @Size(min = 1, max = 1024) String password,
            Map<String, String> properties,
            Boolean allowCompatibility,
            @Min(5) @Max(30) Integer queryTimeoutSeconds,
            Boolean enabled) {}

    public record DataSourceView(
            String id,
            String name,
            DatabaseType databaseType,
            ReadOnlyStatus readOnlyStatus,
            boolean enabled,
            boolean allowCompatibility,
            int queryTimeoutSeconds,
            Instant lastTestedAt,
            String lastTestMessage,
            Instant createdAt,
            Instant updatedAt) {}

    public record DataSourceTestResult(
            boolean reachable,
            ReadOnlyStatus readOnlyStatus,
            boolean enabled,
            String databaseVersion,
            String account,
            List<String> findings,
            String message) {}

    public record SchemaView(String name, boolean system) {}

    public record TableView(String schema, String name, String type) {}

    public record ColumnView(
            String schema,
            String table,
            String name,
            int jdbcType,
            String databaseType,
            Integer size,
            Integer scale,
            boolean nullable,
            Integer ordinalPosition) {}

    public record QueryParameter(@NotBlank @Size(max = 32) String type, Object value) {}

    public record QueryCreateRequest(
            @NotBlank @Size(max = 64) String dataSourceId,
            @NotBlank @Size(max = 32768) String sql,
            @Valid @Size(max = 1000) List<@NotNull QueryParameter> parameters,
            @NotBlank @Size(max = 500) String purpose,
            @Min(1) @Max(1000) Integer maxRows,
            @Pattern(
                            regexp =
                                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                            message = "requestId 必须是规范 UUID")
                    String requestId) {}

    public record QueryPreview(
            String dataSourceId,
            ReadOnlyStatus readOnlyStatus,
            List<String> schemas,
            List<String> tables,
            List<String> riskReasons,
            int effectiveMaxRows,
            int parameterCount,
            String sqlFingerprint) {}

    public record QueryColumn(String label, String name, int jdbcType, String typeName, boolean nullable) {}

    public record QueryResult(
            List<QueryColumn> columns,
            List<List<Object>> rows,
            boolean truncated,
            long durationMs,
            int rowCount,
            long byteCount) {}

    public record QueryView(
            String queryId,
            QueryStatus status,
            String dataSourceId,
            String purpose,
            int effectiveMaxRows,
            List<String> riskReasons,
            Instant approvalExpiresAt,
            Instant createdAt,
            String errorCode,
            String sql,
            List<QueryParameter> parameters,
            QueryResult result) {}

    public record ItemsResponse<T>(List<T> items) {}

    public record TokenCreateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Size(min = 1) List<@NotBlank @Size(max = 64) String> dataSourceIds,
            @Min(1) @Max(365) Integer expiresInDays,
            @AssertTrue(message = "必须明确确认查询结果可能发送给云端 AI")
                    @JsonProperty("confirmCloudDataRisk")
                    boolean confirmCloudDataRisk) {}

    public record TokenScopeUpdateRequest(
            @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 64) String> dataSourceIds,
            @AssertTrue(message = "必须明确确认查询结果可能发送给云端 AI")
                    @JsonProperty("confirmCloudDataRisk")
                    boolean confirmCloudDataRisk) {}

    public record TokenCreated(
            String id,
            String name,
            String token,
            List<String> dataSourceIds,
            List<String> permissions,
            Instant expiresAt,
            Instant createdAt) {}

    public record TokenView(
            String id,
            String name,
            List<String> dataSourceIds,
            List<String> permissions,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt) {}

    public record AuditView(
            long sequenceNo,
            String eventId,
            Instant occurredAt,
            String actor,
            String actorType,
            String eventType,
            String dataSourceId,
            String dataSourceName,
            String queryId,
            String purpose,
            String sqlFingerprint,
            String status,
            Long durationMs,
            Integer rowCount,
            Long byteCount,
            String errorCode,
            boolean chainValid) {}

    public record AuditPage(List<AuditView> items, int page, int size, long total, boolean chainValid) {}

    public record DashboardView(
            long dataSourceCount,
            long enabledDataSourceCount,
            long strictDataSourceCount,
            long compatibilityDataSourceCount,
            long blockedDataSourceCount,
            long pendingApprovalCount,
            long queryCountToday,
            long failedQueryCountToday,
            boolean auditChainValid,
            Instant lastAuditAt) {}
}
