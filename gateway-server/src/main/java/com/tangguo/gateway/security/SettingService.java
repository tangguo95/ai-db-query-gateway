package com.tangguo.gateway.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettingService {
    private final JdbcTemplate jdbcTemplate;

    public SettingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> get(String key) {
        return jdbcTemplate
                .query(
                        "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                        resultSet -> resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.<String>empty(),
                        key);
    }

    public void put(String key, String value) {
        jdbcTemplate.update(
                """
                INSERT INTO app_setting(setting_key, setting_value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value,
                                                       updated_at = excluded.updated_at
                """,
                key,
                value,
                Instant.now().toString());
    }

    public void delete(String key) {
        jdbcTemplate.update("DELETE FROM app_setting WHERE setting_key = ?", key);
    }
}
