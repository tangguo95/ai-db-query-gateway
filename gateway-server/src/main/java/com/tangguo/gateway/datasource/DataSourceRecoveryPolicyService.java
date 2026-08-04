package com.tangguo.gateway.datasource;

import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.security.SettingService;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 管理隔离数据源是否允许后台自动发起连接检查。
 *
 * <p>该策略只影响连接检查，不会重放任何生产查询。网页中的管理员开关会写入本地 SQLite，
 * 重启后继续生效；启动配置只作为尚未保存网页设置时的默认值。</p>
 */
@Service
public class DataSourceRecoveryPolicyService {
    static final String AUTO_RETRY_CONNECTION_CHECKS_KEY = "datasource.auto-retry-connection-checks";
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final SettingService settings;
    private final GatewayProperties properties;

    public DataSourceRecoveryPolicyService(SettingService settings, GatewayProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    public boolean autoRetryConnectionChecks() {
        return settings.get(AUTO_RETRY_CONNECTION_CHECKS_KEY)
                .map(String::trim)
                .filter(value -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
                .map(Boolean::parseBoolean)
                .orElse(properties.getDataSource().isAutoRetryConnectionChecks());
    }

    public void setAutoRetryConnectionChecks(boolean enabled) {
        settings.put(AUTO_RETRY_CONNECTION_CHECKS_KEY, Boolean.toString(enabled));
    }

    public int retryIntervalSeconds() {
        return (int) RETRY_INTERVAL.toSeconds();
    }

    public int maxBackoffMinutes() {
        return (int) MAX_BACKOFF.toMinutes();
    }
}
