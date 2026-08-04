package com.tangguo.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tangguo.gateway.api.ApiDtos.AdminProfileUpdateRequest;
import com.tangguo.gateway.api.GatewayException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AdminProfileServiceTest {
    private AdminProfileService profileService;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE app_setting(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TEXT NOT NULL)");
        profileService = new AdminProfileService(new SettingService(jdbcTemplate));
    }

    @Test
    void storesDisplayNameAndAnimatedGifAsLocalProfileData() {
        String gif = "data:image/gif;base64," + Base64.getEncoder().encodeToString(new byte[] {71, 73, 70, 56});

        var updated = profileService.update("admin", new AdminProfileUpdateRequest("数据管理员", gif));

        assertThat(updated.username()).isEqualTo("admin");
        assertThat(updated.displayName()).isEqualTo("数据管理员");
        assertThat(updated.avatarDataUrl()).isEqualTo(gif);
    }

    @Test
    void rejectsRemoteOrUnsupportedAvatarContent() {
        assertThatThrownBy(() -> profileService.update(
                        "admin", new AdminProfileUpdateRequest("管理员", "https://example.com/avatar.gif")))
                .isInstanceOfSatisfying(GatewayException.class, exception ->
                        assertThat(exception.code()).isEqualTo("INVALID_AVATAR"));
    }

    @Test
    void rejectsAvatarLargerThanFiveMebibytes() {
        String payload = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(new byte[5 * 1_048_576 + 1]);

        assertThatThrownBy(() -> profileService.update(
                        "admin", new AdminProfileUpdateRequest("管理员", payload)))
                .isInstanceOfSatisfying(GatewayException.class, exception ->
                        assertThat(exception.code()).isEqualTo("AVATAR_TOO_LARGE"));
    }
}
