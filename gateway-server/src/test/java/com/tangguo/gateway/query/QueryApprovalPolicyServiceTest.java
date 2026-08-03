package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.security.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class QueryApprovalPolicyServiceTest {
    private JdbcTemplate jdbcTemplate;
    private QueryApprovalPolicyService policy;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE app_setting(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TEXT NOT NULL)");
        GatewayProperties properties = new GatewayProperties();
        policy = new QueryApprovalPolicyService(new SettingService(jdbcTemplate), properties);
    }

    @Test
    void defaultsToApprovalAndPersistsAdministratorOverride() {
        assertThat(policy.approvalRequired()).isTrue();

        policy.setApprovalRequired(false);

        assertThat(policy.approvalRequired()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                        String.class,
                        QueryApprovalPolicyService.APPROVAL_REQUIRED_KEY))
                .isEqualTo("false");
    }

    @Test
    void ignoresMalformedPersistedValueAndUsesConfiguredDefault() {
        jdbcTemplate.update(
                "INSERT INTO app_setting(setting_key, setting_value, updated_at) VALUES (?, ?, ?)",
                QueryApprovalPolicyService.APPROVAL_REQUIRED_KEY,
                "not-a-boolean",
                "now");

        assertThat(policy.approvalRequired()).isTrue();
    }
}
