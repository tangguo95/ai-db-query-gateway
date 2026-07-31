package com.tangguo.aidbgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGatewayClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void mapsAllToolsToLockedRestEndpointsAndForwardsBearerToken() throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> captureAndRespond(exchange, requests));
        server.start();

        GatewayConfig config = new GatewayConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "scope-token"
        );
        HttpGatewayClient client = new HttpGatewayClient(config, objectMapper);

        client.call("list_data_sources", objectMapper.createObjectNode());
        client.call("list_schemas", args("dataSourceId", "source/一"));
        client.call("list_tables", args(
                "dataSourceId", "source-1",
                "schema", "order data"
        ));
        client.call("describe_table", args(
                "dataSourceId", "source-1",
                "schema", "orders",
                "table", "order/item"
        ));
        ObjectNode query = args(
                "dataSourceId", "source-1",
                "sql", "select 1",
                "purpose", "连通性核对"
        );
        client.call("execute_read_query", query);
        client.call("get_query_request", args("queryId", "query-1"));
        client.call("execute_approved_query", args("queryId", "query-1"));
        JsonNode cancelResult = client.call("cancel_query", args("queryId", "query-1"));

        assertEquals(List.of(
                "GET /api/ai/data-sources",
                "GET /api/ai/data-sources/source%2F%E4%B8%80/schemas",
                "GET /api/ai/data-sources/source-1/schemas/order%20data/tables",
                "GET /api/ai/data-sources/source-1/schemas/orders/tables/order%2Fitem",
                "POST /api/ai/queries",
                "GET /api/ai/queries/query-1",
                "POST /api/ai/queries/query-1/execute",
                "POST /api/ai/queries/query-1/cancel"
        ), requests.stream().map(request -> request.method() + " " + request.rawPath()).toList());
        requests.forEach(request -> assertEquals("Bearer scope-token", request.authorization()));
        JsonNode queryBody = objectMapper.readTree(requests.get(4).body());
        assertEquals("source-1", queryBody.path("dataSourceId").textValue());
        assertEquals("select 1", queryBody.path("sql").textValue());
        assertEquals("连通性核对", queryBody.path("purpose").textValue());
        String requestId = queryBody.path("requestId").textValue();
        assertEquals(requestId, UUID.fromString(requestId).toString());
        assertFalse(query.has("requestId"));
        assertEquals("query-1", cancelResult.path("queryId").textValue());
    }

    @Test
    void compensatesInterruptedReadQueryWithTheGeneratedRequestId() throws Exception {
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch cancelReceived = new CountDownLatch(1);
        CountDownLatch releaseQueryResponse = new CountDownLatch(1);
        AtomicReference<JsonNode> queryBody = new AtomicReference<>();
        AtomicReference<CapturedRequest> cancelRequest = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mcp-http-test-", 0).factory());
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            if ("/api/ai/queries".equals(rawPath)) {
                queryBody.set(objectMapper.readTree(body));
                queryStarted.countDown();
                try {
                    releaseQueryResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            } else if (rawPath.startsWith("/api/ai/queries/") && rawPath.endsWith("/cancel")) {
                cancelRequest.set(new CapturedRequest(
                        exchange.getRequestMethod(),
                        rawPath,
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        new String(body, StandardCharsets.UTF_8)
                ));
                cancelReceived.countDown();
            }
            respond(exchange, "{\"queryId\":\"query-1\",\"ok\":true}");
        });
        server.start();

        GatewayConfig config = new GatewayConfig(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "scope-token"
        );
        HttpGatewayClient client = new HttpGatewayClient(config, objectMapper);
        AtomicReference<GatewayCallException> failure = new AtomicReference<>();
        AtomicBoolean interruptedFlagRestored = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                client.call("execute_read_query", args(
                        "dataSourceId", "source-1",
                        "sql", "select sleep(10)",
                        "purpose", "验证 MCP 中断补偿"
                ));
            } catch (GatewayCallException exception) {
                failure.set(exception);
                interruptedFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            assertTrue(queryStarted.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(cancelReceived.await(5, TimeUnit.SECONDS));
            releaseQueryResponse.countDown();
            caller.join(5_000);

            assertFalse(caller.isAlive());
            assertNotNull(failure.get());
            assertEquals("REQUEST_INTERRUPTED", failure.get().code());
            assertTrue(interruptedFlagRestored.get());

            String requestId = queryBody.get().path("requestId").textValue();
            assertEquals(requestId, UUID.fromString(requestId).toString());
            assertNotNull(cancelRequest.get());
            assertEquals("POST", cancelRequest.get().method());
            assertEquals("/api/ai/queries/" + requestId + "/cancel",
                    cancelRequest.get().rawPath());
            assertEquals("Bearer scope-token", cancelRequest.get().authorization());
            assertEquals("{}", cancelRequest.get().body());
        } finally {
            releaseQueryResponse.countDown();
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(2_000);
            }
        }
    }

    @Test
    void refusesCallWithoutTokenBeforeOpeningHttpConnection() {
        GatewayConfig config = new GatewayConfig(URI.create("http://127.0.0.1:1"), "");
        HttpGatewayClient client = new HttpGatewayClient(config, objectMapper);

        GatewayCallException exception = assertThrows(
                GatewayCallException.class,
                () -> client.call("list_data_sources", objectMapper.createObjectNode())
        );
        assertEquals("MISSING_API_TOKEN", exception.code());
    }

    @Test
    void rejectsNonLoopbackGatewayUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayConfig(URI.create("https://database.example.com"), "token"));
    }

    @Test
    void acceptsIpv6LoopbackAndRedactsTokenFromConfigurationText() {
        GatewayConfig config = new GatewayConfig(
                URI.create("http://[::1]:8765"),
                "top-secret-token"
        );

        assertEquals(URI.create("http://[::1]:8765/api/ai/data-sources"),
                config.resolve("/api/ai/data-sources"));
        assertFalse(config.toString().contains("top-secret-token"));
        assertEquals("GatewayConfig[baseUri=http://[::1]:8765, token=<redacted>]",
                config.toString());
    }

    private ObjectNode args(String... keyValues) {
        ObjectNode arguments = objectMapper.createObjectNode();
        for (int index = 0; index < keyValues.length; index += 2) {
            arguments.put(keyValues[index], keyValues[index + 1]);
        }
        return arguments;
    }

    private void captureAndRespond(HttpExchange exchange, List<CapturedRequest> requests) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        requests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(requestBody, StandardCharsets.UTF_8)
        ));
        respond(exchange, "{\"queryId\":\"query-1\",\"ok\":true}");
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String method, String rawPath, String authorization, String body) {
    }
}
