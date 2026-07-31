package com.tangguo.gateway.security;

import com.tangguo.gateway.api.ApiDtos.TokenCreateRequest;
import com.tangguo.gateway.api.ApiDtos.TokenCreated;
import com.tangguo.gateway.api.ApiDtos.TokenScopeUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.TokenView;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.model.ActorType;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApiTokenService {
    private static final List<String> DEFAULT_PERMISSIONS = List.of("metadata:read", "query:execute");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final AuditService auditService;
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    public ApiTokenService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.auditService = auditService;
    }

    public TokenCreated create(TokenCreateRequest request, String actor) {
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "TOKEN_CREATE_REQUESTED",
                "REQUESTED",
                java.util.Map.of("name", request.name(), "dataSourceIds", request.dataSourceIds())));
        validateDataSourceScope(request.dataSourceIds());
        String id = UUID.randomUUID().toString();
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String rawToken = "gwy_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(
                request.expiresInDays() == null ? 30 : request.expiresInDays(), ChronoUnit.DAYS);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO api_token(id, name, token_hash, data_source_scope, permissions,
                                          expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    request.name(),
                    BootstrapService.sha256(rawToken),
                    objectMapper.writeValueAsString(request.dataSourceIds()),
                    objectMapper.writeValueAsString(DEFAULT_PERMISSIONS),
                    expiresAt.toString(),
                    now.toString());
        } catch (JacksonException exception) {
            throw new IllegalStateException("令牌作用域序列化失败", exception);
        }
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "TOKEN_CREATED",
                "SUCCESS",
                java.util.Map.of("tokenId", id, "name", request.name(), "dataSourceIds", request.dataSourceIds())));
        return new TokenCreated(
                id, request.name(), rawToken, request.dataSourceIds(), DEFAULT_PERMISSIONS, expiresAt, now);
    }

    public List<TokenView> list() {
        return jdbcTemplate.query(
                """
                SELECT id, name, data_source_scope, permissions, expires_at, last_used_at, created_at
                  FROM api_token ORDER BY created_at DESC
                """,
                (resultSet, rowNum) -> new TokenView(
                        resultSet.getString("id"),
                        resultSet.getString("name"),
                        readList(resultSet.getString("data_source_scope")),
                        readList(resultSet.getString("permissions")),
                        Instant.parse(resultSet.getString("expires_at")),
                        instant(resultSet.getString("last_used_at")),
                        Instant.parse(resultSet.getString("created_at"))));
    }

    /**
     * 只替换令牌的数据源作用域；摘要、权限和有效期保持不变，因此已配置的 MCP 无需换 Token。
     */
    public TokenView updateScope(String id, TokenScopeUpdateRequest request, String actor) {
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "TOKEN_SCOPE_UPDATE_REQUESTED",
                "REQUESTED",
                java.util.Map.of("tokenId", id, "dataSourceIds", request.dataSourceIds())));
        TokenView current = findToken(id);
        if (!current.expiresAt().isAfter(Instant.now())) {
            throw new GatewayException(
                    HttpStatus.CONFLICT,
                    "TOKEN_EXPIRED",
                    "已过期的访问令牌不能调整数据源范围");
        }
        if (!request.confirmCloudDataRisk()) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "CLOUD_DATA_RISK_NOT_CONFIRMED",
                    "必须明确确认查询结果可能发送给云端 AI");
        }
        List<String> dataSourceIds = request.dataSourceIds().stream().distinct().toList();
        validateDataSourceScope(dataSourceIds);
        try {
            int changed = jdbcTemplate.update(
                    "UPDATE api_token SET data_source_scope = ? WHERE id = ?",
                    objectMapper.writeValueAsString(dataSourceIds),
                    id);
            if (changed == 0) {
                throw new GatewayException(HttpStatus.NOT_FOUND, "TOKEN_NOT_FOUND", "访问令牌不存在");
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("令牌作用域序列化失败", exception);
        }
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "TOKEN_SCOPE_UPDATED",
                "SUCCESS",
                java.util.Map.of(
                        "tokenId",
                        id,
                        "previousDataSourceIds",
                        current.dataSourceIds(),
                        "dataSourceIds",
                        dataSourceIds)));
        return findToken(id);
    }

    public void delete(String id, String actor) {
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "TOKEN_DELETE_REQUESTED",
                "REQUESTED",
                java.util.Map.of("tokenId", id)));
        int changed = jdbcTemplate.update("DELETE FROM api_token WHERE id = ?", id);
        if (changed == 0) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "TOKEN_NOT_FOUND", "访问令牌不存在");
        }
        rateWindows.remove(id);
        auditService.record(AuditCommand.simple(
                actor, ActorType.ADMIN, "TOKEN_DELETED", "SUCCESS", java.util.Map.of("tokenId", id)));
    }

    public TokenContext authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith("gwy_") || rawToken.length() > 128) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "访问令牌无效");
        }
        List<TokenContext> contexts = jdbcTemplate.query(
                """
                SELECT id, name, data_source_scope, permissions, expires_at
                  FROM api_token WHERE token_hash = ?
                """,
                (resultSet, rowNum) -> new TokenContext(
                        resultSet.getString("id"),
                        resultSet.getString("name"),
                        Set.copyOf(readList(resultSet.getString("data_source_scope"))),
                        Set.copyOf(readList(resultSet.getString("permissions"))),
                        Instant.parse(resultSet.getString("expires_at"))),
                BootstrapService.sha256(rawToken));
        if (contexts.isEmpty()) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "访问令牌无效");
        }
        TokenContext context = contexts.getFirst();
        if (!context.expiresAt().isAfter(Instant.now())) {
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "访问令牌已过期");
        }
        enforceRateLimit(context.tokenId());
        jdbcTemplate.update(
                "UPDATE api_token SET last_used_at = ? WHERE id = ?", Instant.now().toString(), context.tokenId());
        return context;
    }

    private void enforceRateLimit(String tokenId) {
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = rateWindows.compute(
                tokenId,
                (key, previous) -> previous == null || previous.minute != minute
                        ? new RateWindow(minute)
                        : previous);
        if (window.count.incrementAndGet() > properties.getSecurity().getTokenRatePerMinute()) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "TOKEN_RATE_LIMITED", "访问令牌请求过于频繁");
        }
    }

    private void validateDataSourceScope(List<String> dataSourceIds) {
        for (String dataSourceId : dataSourceIds) {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                      FROM data_source_config
                     WHERE id = ?
                       AND deleted = 0
                       AND enabled = 1
                       AND read_only_status IN ('STRICT', 'COMPATIBILITY')
                    """,
                    Integer.class,
                    dataSourceId);
            if (count == null || count == 0) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST,
                        "DATASOURCE_NOT_AVAILABLE",
                        "令牌只能绑定已启用且通过连接检查的数据源");
            }
        }
    }

    private TokenView findToken(String id) {
        List<TokenView> tokens = jdbcTemplate.query(
                """
                SELECT id, name, data_source_scope, permissions, expires_at, last_used_at, created_at
                  FROM api_token
                 WHERE id = ?
                """,
                (resultSet, rowNum) -> new TokenView(
                        resultSet.getString("id"),
                        resultSet.getString("name"),
                        readList(resultSet.getString("data_source_scope")),
                        readList(resultSet.getString("permissions")),
                        Instant.parse(resultSet.getString("expires_at")),
                        instant(resultSet.getString("last_used_at")),
                        Instant.parse(resultSet.getString("created_at"))),
                id);
        if (tokens.isEmpty()) {
            throw new GatewayException(HttpStatus.NOT_FOUND, "TOKEN_NOT_FOUND", "访问令牌不存在");
        }
        return tokens.getFirst();
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("本地令牌作用域数据损坏", exception);
        }
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static final class RateWindow {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private RateWindow(long minute) {
            this.minute = minute;
        }
    }
}
