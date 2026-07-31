package com.tangguo.aidbgateway.mcp;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 进程配置。网关地址强制为回环地址，防止作用域令牌被误发到远程主机。
 */
record GatewayConfig(URI baseUri, String token) {

    static final String DEFAULT_URL = "http://127.0.0.1:8765";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    GatewayConfig {
        validateBaseUri(baseUri);
        token = token == null ? "" : token.strip();
    }

    static GatewayConfig fromEnvironment(Map<String, String> environment) {
        String configuredUrl = environment.getOrDefault("AI_DB_GATEWAY_URL", DEFAULT_URL);
        if (configuredUrl == null || configuredUrl.isBlank()) {
            configuredUrl = DEFAULT_URL;
        }
        String configuredToken = environment.getOrDefault("AI_DB_GATEWAY_TOKEN", "");
        return new GatewayConfig(URI.create(configuredUrl.strip()), configuredToken);
    }

    boolean hasToken() {
        return !token.isBlank();
    }

    URI resolve(String apiPath) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + apiPath);
    }

    @Override
    public String toString() {
        return "GatewayConfig[baseUri=" + baseUri + ", token=<redacted>]";
    }

    private static void validateBaseUri(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            throw new IllegalArgumentException("AI_DB_GATEWAY_URL 必须是绝对地址");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("AI_DB_GATEWAY_URL 只允许 http/https");
        }
        String host = uri.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host == null || !LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("AI_DB_GATEWAY_URL 只允许本机回环地址");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("AI_DB_GATEWAY_URL 不允许用户信息、查询参数或片段");
        }
    }
}
