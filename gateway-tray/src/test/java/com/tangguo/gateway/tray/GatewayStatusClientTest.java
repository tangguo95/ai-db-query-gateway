package com.tangguo.gateway.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayStatusClientTest {
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void reportsHealthyGateway() {
        server.createContext("/actuator/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        GatewayStatusClient.Snapshot snapshot = client().check();

        assertEquals(GatewayStatusClient.State.RUNNING, snapshot.state());
        assertEquals(200, snapshot.httpStatus());
    }

    @Test
    void reportsUnhealthyGateway() {
        server.createContext("/actuator/health", exchange -> exchange.sendResponseHeaders(503, -1));
        server.start();

        GatewayStatusClient.Snapshot snapshot = client().check();

        assertEquals(GatewayStatusClient.State.UNHEALTHY, snapshot.state());
        assertEquals(503, snapshot.httpStatus());
    }

    private GatewayStatusClient client() {
        return new GatewayStatusClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/actuator/health"));
    }
}
