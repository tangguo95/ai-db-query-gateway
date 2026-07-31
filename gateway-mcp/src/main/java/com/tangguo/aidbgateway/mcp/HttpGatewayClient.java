package com.tangguo.aidbgateway.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 使用 JDK HttpClient 调用本机 REST 网关，不启用重定向，避免 Authorization 被转发。
 */
final class HttpGatewayClient implements GatewayClient {

    private static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException exception) {
            // 适配器明确禁止代理，不存在需要降级或重试的代理连接。
        }
    };

    private final GatewayConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    HttpGatewayClient(GatewayConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(NO_PROXY)
                .build());
    }

    HttpGatewayClient(GatewayConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public JsonNode call(String toolName, ObjectNode arguments) throws GatewayCallException {
        if (!config.hasToken()) {
            throw new GatewayCallException(
                    "MISSING_API_TOKEN",
                    "未配置 AI_DB_GATEWAY_TOKEN，拒绝访问数据库网关"
            );
        }

        RequestSpec requestSpec = switch (toolName) {
            case "list_data_sources" -> RequestSpec.get("/api/ai/data-sources");
            case "list_schemas" -> RequestSpec.get(
                    "/api/ai/data-sources/" + requiredSegment(arguments, "dataSourceId") + "/schemas"
            );
            case "list_tables" -> RequestSpec.get(
                    "/api/ai/data-sources/" + requiredSegment(arguments, "dataSourceId")
                            + "/schemas/" + requiredSegment(arguments, "schema") + "/tables"
            );
            case "describe_table" -> RequestSpec.get(
                    "/api/ai/data-sources/" + requiredSegment(arguments, "dataSourceId")
                            + "/schemas/" + requiredSegment(arguments, "schema")
                            + "/tables/" + requiredSegment(arguments, "table")
            );
            case "execute_read_query" -> {
                String requestId = UUID.randomUUID().toString();
                ObjectNode body = arguments.deepCopy();
                body.put("requestId", requestId);
                yield RequestSpec.cancellablePost("/api/ai/queries", body, requestId);
            }
            case "get_query_request" -> RequestSpec.get(
                    "/api/ai/queries/" + requiredSegment(arguments, "queryId")
            );
            case "execute_approved_query" -> RequestSpec.post(
                    "/api/ai/queries/" + requiredSegment(arguments, "queryId") + "/execute",
                    objectMapper.createObjectNode()
            );
            case "cancel_query" -> RequestSpec.post(
                    "/api/ai/queries/" + requiredSegment(arguments, "queryId") + "/cancel",
                    objectMapper.createObjectNode()
            );
            default -> throw new GatewayCallException("UNKNOWN_TOOL", "不支持的 MCP 工具");
        };

        return send(requestSpec);
    }

    private JsonNode send(RequestSpec requestSpec) throws GatewayCallException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.resolve(requestSpec.path()))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.token());

        if (requestSpec.body() == null) {
            builder.GET();
        } else {
            try {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(requestSpec.body()),
                                StandardCharsets.UTF_8
                        ));
            } catch (JsonProcessingException exception) {
                throw new GatewayCallException("INVALID_TOOL_ARGUMENTS", "无法序列化工具参数");
            }
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] responseBytes;
            try (InputStream body = response.body()) {
                responseBytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (responseBytes.length > MAX_RESPONSE_BYTES) {
                throw new GatewayCallException("GATEWAY_RESPONSE_TOO_LARGE", "网关响应超过 MCP 适配器上限");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 不转发上游错误正文，避免其中意外包含 SQL、参数或其他敏感信息。
                throw new GatewayCallException(
                        "GATEWAY_HTTP_" + response.statusCode(),
                        "数据库网关拒绝了请求（HTTP " + response.statusCode() + "）"
                );
            }
            if (responseBytes.length == 0) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBytes);
        } catch (GatewayCallException exception) {
            throw exception;
        } catch (ConnectException exception) {
            throw new GatewayCallException("GATEWAY_UNAVAILABLE", "无法连接本机数据库网关");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new GatewayCallException("GATEWAY_TIMEOUT", "等待本机数据库网关响应超时");
        } catch (InterruptedException exception) {
            if (requestSpec.cancellationQueryId() != null) {
                cancelBestEffort(requestSpec.cancellationQueryId());
            }
            Thread.currentThread().interrupt();
            throw new GatewayCallException("REQUEST_INTERRUPTED", "数据库网关调用已中断");
        } catch (IOException exception) {
            throw new GatewayCallException("GATEWAY_IO_ERROR", "读取本机数据库网关响应失败");
        } catch (RuntimeException exception) {
            throw new GatewayCallException("GATEWAY_CLIENT_ERROR", "数据库网关请求构造失败");
        }
    }

    private static String requiredSegment(ObjectNode arguments, String fieldName) throws GatewayCallException {
        JsonNode value = arguments.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new GatewayCallException("INVALID_TOOL_ARGUMENTS", "缺少必填参数：" + fieldName);
        }
        return URLEncoder.encode(value.textValue(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void cancelBestEffort(String queryId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(
                                config.resolve("/api/ai/queries/" + queryId + "/cancel"))
                        .timeout(Duration.ofSeconds(2))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.token())
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
            } catch (IOException | RuntimeException ignored) {
                // 原始 MCP 请求已经取消；补偿取消只能尽力而为，且不得泄露上游异常。
            } catch (InterruptedException ignored) {
                // 清除补偿请求自身的中断状态，方法返回前由调用者恢复原中断标志。
                Thread.interrupted();
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.interrupted();
                }
            }
        }
    }

    private record RequestSpec(String path, JsonNode body, String cancellationQueryId) {

        private static RequestSpec get(String path) {
            return new RequestSpec(path, null, null);
        }

        private static RequestSpec post(String path, JsonNode body) {
            return new RequestSpec(path, body, null);
        }

        private static RequestSpec cancellablePost(String path, JsonNode body, String queryId) {
            return new RequestSpec(path, body, queryId);
        }
    }
}
