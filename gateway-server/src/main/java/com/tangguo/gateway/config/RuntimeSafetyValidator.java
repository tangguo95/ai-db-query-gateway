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
        if (isLoopback(address) && !properties.isRemoteEnabled()) {
            return;
        }
        throw new IllegalStateException("本机免登录模式仅允许回环监听，不能启用远程模式");
    }

    private boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
