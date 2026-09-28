package com.tangguo.gateway.server;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ServerDtos {
    private ServerDtos() {}

    // 连接信息仅用于管理员配置和安全存储，不进入 MCP 返回对象。
    public record Endpoint(
            @NotBlank @Size(max = 253) String host,
            @Min(1) @Max(65535) int port,
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Pattern(regexp = "PASSWORD|PRIVATE_KEY") String authType,
            @Size(max = 4096) String password,
            @Size(max = 32768) String privateKey,
            @Size(max = 4096) String passphrase,
            @Size(max = 100) String fingerprint) {}

    public record Connection(@NotNull @Valid Endpoint target, @Valid Endpoint jump) {}

    public record SaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Valid Connection connection) {}

    public record ConnectionTestResult(boolean reachable, String message) {}

    public record AccessRequest(boolean fullAccess, Boolean confirmFullAccess) {}
    public record ServerView(String id, String name, boolean enabled, boolean fullAccess,
                             String lastTestMessage, String updatedAt) {}
    public record ExecuteRequest(
            @NotBlank @Size(max = 128) String serverId,
            @NotBlank @Size(max = 16384) String command,
            @NotBlank @Size(max = 500) String purpose,
            @Min(1) @Max(30) Integer timeoutSeconds) {}
    public record ExecuteResult(String executionId, String status, Integer exitCode,
                                String stdout, String stderr, boolean truncated,
                                long durationMs, String message) {}
}
