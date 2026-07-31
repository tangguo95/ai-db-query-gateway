package com.tangguo.gateway.config;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeSafetyValidator {
    private final GatewayProperties properties;
    private final Environment environment;

    public RuntimeSafetyValidator(GatewayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validateBindAddress() {
        String address = environment.getProperty("server.address", "127.0.0.1");
        if (isLoopback(address)) {
            return;
        }
        boolean sslEnabled = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        if (!properties.isRemoteEnabled() || !sslEnabled) {
            throw new IllegalStateException("非回环地址启动必须同时显式启用 gateway.remote-enabled 和 TLS");
        }
    }

    private boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
