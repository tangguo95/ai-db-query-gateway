package com.tangguo.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.GatewayProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class BootstrapServiceTest {
    @TempDir
    Path dataDir;

    private BootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE app_setting(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TEXT NOT NULL)");
        GatewayProperties properties = new GatewayProperties();
        properties.setDataDir(dataDir.toString());
        bootstrapService = new BootstrapService(
                new SettingService(jdbcTemplate),
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                properties);
        bootstrapService.prepareBootstrapToken();
    }

    @Test
    void initializesOnlyWithCurrentOneTimeTokenAndArgon2Hash() throws Exception {
        String token = bootstrapService.currentBootstrapTokenForTests();
        Path tokenFile = dataDir.resolve("bootstrap-token");
        assertThat(tokenFile).exists();
        assertThat(Files.readString(tokenFile).trim()).isEqualTo(token);
        assertThatThrownBy(() -> bootstrapService.setup("wrong", "a-very-long-admin-password"))
                .isInstanceOf(GatewayException.class);

        bootstrapService.setup(token, "a-very-long-admin-password");

        assertThat(bootstrapService.isInitialized()).isTrue();
        assertThat(bootstrapService.verifyPassword("a-very-long-admin-password")).isTrue();
        assertThat(bootstrapService.verifyPassword("wrong")).isFalse();
        assertThat(tokenFile).doesNotExist();
        assertThatThrownBy(() -> bootstrapService.setup(token, "another-long-password"))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void changesPasswordOnlyAfterVerifyingTheCurrentPassword() {
        String token = bootstrapService.currentBootstrapTokenForTests();
        bootstrapService.setup(token, "a-very-long-admin-password");

        assertThatThrownBy(() -> bootstrapService.changePassword("wrong-password", "another-long-password"))
                .isInstanceOfSatisfying(GatewayException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_CURRENT_PASSWORD"));

        bootstrapService.changePassword("a-very-long-admin-password", "another-long-password");

        assertThat(bootstrapService.verifyPassword("a-very-long-admin-password")).isFalse();
        assertThat(bootstrapService.verifyPassword("another-long-password")).isTrue();
    }
}
