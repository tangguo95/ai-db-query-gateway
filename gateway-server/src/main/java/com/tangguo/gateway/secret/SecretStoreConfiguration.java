package com.tangguo.gateway.secret;

import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.config.GatewayDataDirectory;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecretStoreConfiguration {

    @Bean
    SecretStore secretStore(GatewayProperties properties, ObjectMapper objectMapper, Environment environment) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (properties.getSecrets().isAllowInMemory()) {
            if (production) {
                throw new IllegalStateException("生产 profile 禁止使用内存凭据存储");
            }
            return new InMemorySecretStore();
        }
        if (GatewayDataDirectory.isWindows()) {
            Path dataDirectory = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
            return new WindowsDpapiSecretStore(dataDirectory.resolve("secrets"));
        }
        if (!GatewayDataDirectory.isMacOs()) {
            throw new IllegalStateException("当前系统没有可用的安全凭据存储；仅支持 macOS Keychain 或 Windows DPAPI");
        }
        Path path = Path.of(properties.getSecrets().getHelperPath());
        if (!path.isAbsolute()) {
            Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            Path direct = current.resolve(path).normalize();
            path = direct.toFile().exists() ? direct : current.resolve("..").resolve(path).normalize();
        }
        return new KeychainSecretStore(path, objectMapper);
    }
}
