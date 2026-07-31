package com.tangguo.gateway;

import com.tangguo.gateway.config.GatewayProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) throws IOException {
        String configured = System.getenv("GATEWAY_DATA_DIR");
        Path dataDir = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), "Library", "Application Support", "AI DB Query Gateway")
                : Path.of(configured);
        Files.createDirectories(dataDir);
        try {
            Files.setPosixFilePermissions(
                    dataDir,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // 生产目标是 macOS/APFS；非 POSIX 文件系统只用于显式的非生产测试。
        }
        System.setProperty("gateway.data-dir", dataDir.toAbsolutePath().normalize().toString());
        SpringApplication.run(GatewayApplication.class, args);
    }
}
