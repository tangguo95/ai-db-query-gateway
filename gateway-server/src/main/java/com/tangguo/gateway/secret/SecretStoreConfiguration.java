package com.tangguo.gateway.secret;

import com.tangguo.gateway.config.GatewayProperties;
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
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            throw new IllegalStateException("非 macOS 环境必须在非生产环境显式启用内存凭据存储");
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
