package com.tangguo.gateway.tray;

import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reads the local actuator endpoint without requiring authentication. */
final class GatewayStatusClient {
    enum State {
        RUNNING,
        UNHEALTHY,
        STOPPED,
        UNKNOWN
    }

    record Snapshot(State state, int httpStatus, String detail) {
        static Snapshot running(int status) {
            return new Snapshot(State.RUNNING, status, "HTTP " + status);
        }

        static Snapshot unhealthy(int status) {
            return new Snapshot(State.UNHEALTHY, status, "HTTP " + status);
        }

        static Snapshot stopped(String detail) {
            return new Snapshot(State.STOPPED, -1, detail);
        }

        static Snapshot unknown(String detail) {
            return new Snapshot(State.UNKNOWN, -1, detail);
        }
    }

    private final HttpClient client;
    private final HttpRequest request;

    GatewayStatusClient(URI healthUri) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
    }

    Snapshot check() {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return Snapshot.running(status);
            }
            return Snapshot.unhealthy(status);
        } catch (ConnectException exception) {
            return Snapshot.stopped("连接被拒绝");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Snapshot.unknown("状态检查被中断");
        } catch (Exception exception) {
            return Snapshot.unknown("状态检查不可用");
        }
    }
}
