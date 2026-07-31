package com.tangguo.gateway.security;

import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.model.ActorType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestSafetyFilter extends OncePerRequestFilter {
    private final GatewayProperties properties;
    private final AuditService auditService;
    private final ActorContext actorContext;

    public RequestSafetyFilter(
            GatewayProperties properties, AuditService auditService, ActorContext actorContext) {
        this.properties = properties;
        this.auditService = auditService;
        this.actorContext = actorContext;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; worker-src 'self'; "
                        + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");

        boolean apiRequest = request.getRequestURI().startsWith("/api/");
        long started = System.nanoTime();
        if (apiRequest
                && !recordHttpEventOrFail(
                    "HTTP_API_REQUESTED",
                    request,
                    "REQUESTED",
                    null,
                    null,
                    response)) {
            return;
        }

        String host = stripPort(request.getHeader("Host"));
        boolean hostHeaderValid;
        try {
            hostPort(request.getHeader("Host"), request.getServerPort());
            hostHeaderValid = !host.isBlank();
        } catch (IllegalArgumentException exception) {
            hostHeaderValid = false;
        }
        if (!hostHeaderValid || (!properties.isRemoteEnabled() && !isLoopbackHost(host))) {
            if (apiRequest
                    && !recordHttpEventOrFail(
                        "HTTP_API_COMPLETED",
                        request,
                        "REJECTED",
                        HttpServletResponse.SC_FORBIDDEN,
                        elapsedMillis(started),
                        response)) {
                return;
            }
            reject(response, "HOST_NOT_ALLOWED", "Host 不在本机允许范围");
            return;
        }
        if (request.getRequestURI().startsWith("/api/ai/")) {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                if (apiRequest
                        && !recordHttpEventOrFail(
                            "HTTP_API_COMPLETED",
                            request,
                            "REJECTED",
                            HttpServletResponse.SC_UNAUTHORIZED,
                            elapsedMillis(started),
                            response)) {
                    return;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                response.getWriter()
                        .write("{\"timestamp\":\"" + Instant.now()
                                + "\",\"status\":401,\"code\":\"BEARER_TOKEN_REQUIRED\","
                                + "\"message\":\"AI 接口仅接受 Bearer Token\"}");
                return;
            }
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            try {
                URI originUri = URI.create(origin);
                String originHost = originUri.getHost();
                int originPort = effectivePort(originUri.getScheme(), originUri.getPort());
                int requestPort = hostPort(request.getHeader("Host"), request.getServerPort());
                if (originHost == null
                        || originUri.getUserInfo() != null
                        || !request.getScheme().equalsIgnoreCase(originUri.getScheme())
                        || originPort != requestPort
                        || (!originHost.equalsIgnoreCase(host)
                                && !(isLoopbackHost(originHost) && isLoopbackHost(host)))) {
                    if (apiRequest
                            && !recordHttpEventOrFail(
                                "HTTP_API_COMPLETED",
                                request,
                                "REJECTED",
                                HttpServletResponse.SC_FORBIDDEN,
                                elapsedMillis(started),
                                response)) {
                        return;
                    }
                    reject(response, "ORIGIN_NOT_ALLOWED", "跨来源请求已拒绝");
                    return;
                }
            } catch (IllegalArgumentException exception) {
                if (apiRequest
                        && !recordHttpEventOrFail(
                            "HTTP_API_COMPLETED",
                            request,
                            "REJECTED",
                            HttpServletResponse.SC_FORBIDDEN,
                            elapsedMillis(started),
                            response)) {
                    return;
                }
                reject(response, "ORIGIN_NOT_ALLOWED", "Origin 格式无效");
                return;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            if (response.getStatus() < 400) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            throw exception;
        } finally {
            if (apiRequest) {
                int status = response.getStatus();
                recordHttpEventOrFail(
                        "HTTP_API_COMPLETED",
                        request,
                        status < 400 ? "SUCCESS" : "REJECTED",
                        status,
                        elapsedMillis(started),
                        response);
            }
        }
    }

    private String stripPort(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end > 0 ? hostHeader.substring(1, end) : hostHeader;
        }
        int colon = hostHeader.indexOf(':');
        return colon < 0 ? hostHeader : hostHeader.substring(0, colon);
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host)
                || "::1".equals(host);
    }

    private int hostPort(String hostHeader, int fallback) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return fallback;
        }
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            if (end >= 0 && end + 1 < hostHeader.length() && hostHeader.charAt(end + 1) == ':') {
                return checkedPort(Integer.parseInt(hostHeader.substring(end + 2)));
            }
            return fallback;
        }
        int first = hostHeader.indexOf(':');
        int last = hostHeader.lastIndexOf(':');
        return first > 0 && first == last
                ? checkedPort(Integer.parseInt(hostHeader.substring(first + 1)))
                : fallback;
    }

    private int effectivePort(String scheme, int explicitPort) {
        if (explicitPort >= 0) {
            return explicitPort;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private int checkedPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid Host port");
        }
        return port;
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":403,\"code\":\"" + code
                        + "\",\"message\":\"" + message + "\"}");
    }

    private void recordHttpEvent(
            String eventType,
            HttpServletRequest request,
            String status,
            Integer responseStatus,
            Long durationMs) {
        String actor = actorContext.actor();
        ActorType actorType = actorContext.actorType();
        auditService.record(new AuditCommand(
                actor,
                actorType,
                eventType,
                null,
                null,
                null,
                null,
                Map.of(
                        "method",
                        request.getMethod(),
                        "path",
                        request.getRequestURI(),
                        "responseStatus",
                        responseStatus == null ? 0 : responseStatus),
                status,
                durationMs,
                null,
                null,
                responseStatus != null && responseStatus >= 400 ? "HTTP_" + responseStatus : null));
    }

    private boolean recordHttpEventOrFail(
            String eventType,
            HttpServletRequest request,
            String status,
            Integer responseStatus,
            Long durationMs,
            HttpServletResponse response) {
        try {
            recordHttpEvent(eventType, request, status, responseStatus, durationMs);
            return true;
        } catch (GatewayException exception) {
            if (!response.isCommitted()) {
                response.resetBuffer();
                response.setStatus(exception.status().value());
                response.setContentType("application/json");
                response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                try {
                    response.getWriter()
                            .write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":"
                                    + exception.status().value() + ",\"code\":\"" + exception.code()
                                    + "\",\"message\":\"审计不可用，操作已中止\"}");
                } catch (IOException ignored) {
                    // 容器已无法写出稳定错误体；审计链仍保持失败关闭。
                }
            }
            return false;
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
