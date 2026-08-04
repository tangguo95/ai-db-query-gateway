package com.tangguo.gateway.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.security.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DataSourceRecoveryPolicyServiceTest {
    private JdbcTemplate jdbcTemplate;
    private DataSourceRecoveryPolicyService policy;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                "CREATE TABLE app_setting(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TEXT NOT NULL)");
        policy = new DataSourceRecoveryPolicyService(new SettingService(jdbcTemplate), new GatewayProperties());
    }

    @Test
    void defaultsToDisabledAndPersistsAdministratorOverride() {
        assertThat(policy.autoRetryConnectionChecks()).isFalse();

        policy.setAutoRetryConnectionChecks(true);

        assertThat(policy.autoRetryConnectionChecks()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                        String.class,
                        DataSourceRecoveryPolicyService.AUTO_RETRY_CONNECTION_CHECKS_KEY))
                .isEqualTo("true");
        assertThat(policy.retryIntervalSeconds()).isEqualTo(60);
        assertThat(policy.maxBackoffMinutes()).isEqualTo(15);
    }

    @Test
    void ignoresMalformedPersistedValueAndUsesConfiguredDefault() {
        jdbcTemplate.update(
                "INSERT INTO app_setting(setting_key, setting_value, updated_at) VALUES (?, ?, ?)",
                DataSourceRecoveryPolicyService.AUTO_RETRY_CONNECTION_CHECKS_KEY,
                "not-a-boolean",
                "now");

        assertThat(policy.autoRetryConnectionChecks()).isFalse();
    }
}
