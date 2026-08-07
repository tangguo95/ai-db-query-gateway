package com.tangguo.gateway;

import com.tangguo.gateway.config.FileSecurity;
import com.tangguo.gateway.config.GatewayDataDirectory;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.secret.SecretStoreCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (SecretStoreCli.isCommand(args)) {
            int exitCode = SecretStoreCli.run(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }

        Path dataDir = GatewayDataDirectory.resolve();
        Files.createDirectories(dataDir);
        FileSecurity.setOwnerOnlyDirectory(dataDir);
        System.setProperty("gateway.data-dir", dataDir.toAbsolutePath().normalize().toString());
        SpringApplication.run(GatewayApplication.class, args);
    }
}
