package com.tangguo.gateway.secret;

import java.util.Map;

public record ConnectionSecret(
        String host,
        int port,
        String database,
        String username,
        String password,
        Map<String, String> properties) {

    public ConnectionSecret {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
