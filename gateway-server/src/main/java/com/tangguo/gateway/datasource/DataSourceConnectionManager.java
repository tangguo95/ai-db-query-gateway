package com.tangguo.gateway.datasource;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.secret.ConnectionSecret;
import com.tangguo.gateway.secret.SecretStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class DataSourceConnectionManager {
    private final DataSourceRepository repository;
    private final SecretStore secretStore;
    private final ObjectMapper objectMapper;
    private final ConnectorRegistry connectorRegistry;
    private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceConnectionManager(
            DataSourceRepository repository,
            SecretStore secretStore,
            ObjectMapper objectMapper,
            ConnectorRegistry connectorRegistry) {
        this.repository = repository;
        this.secretStore = secretStore;
        this.objectMapper = objectMapper;
        this.connectorRegistry = connectorRegistry;
    }

    public Connection connection(DataSourceConfig config) throws SQLException {
        return pools.computeIfAbsent(config.id(), ignored -> createPool(config)).getConnection();
    }

    public ConnectionSecret secret(DataSourceConfig config) {
        String json = secretStore.get(config.secretRef())
                .orElseThrow(() -> new GatewayException(
                        HttpStatus.SERVICE_UNAVAILABLE, "DATASOURCE_SECRET_MISSING", "数据源凭据不可用"));
        try {
            return objectMapper.readValue(json, ConnectionSecret.class);
        } catch (JacksonException exception) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATASOURCE_SECRET_INVALID",
                    "数据源凭据损坏",
                    exception);
        }
    }

    public void invalidate(String dataSourceId) {
        Optional.ofNullable(pools.remove(dataSourceId)).ifPresent(HikariDataSource::close);
    }

    @PreDestroy
    void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private HikariDataSource createPool(DataSourceConfig config) {
        ConnectionSecret secret = secret(config);
        JdbcConnector connector = connectorRegistry.require(config.databaseType());
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("gateway-ds-" + config.id().substring(0, 8));
        hikari.setDriverClassName(connector.driverClassName());
        hikari.setJdbcUrl(connector.jdbcUrl(secret));
        hikari.setUsername(secret.username());
        hikari.setPassword(secret.password());
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(0);
        hikari.setAutoCommit(false);
        hikari.setReadOnly(true);
        hikari.setConnectionTimeout(5_000);
        hikari.setValidationTimeout(3_000);
        hikari.setIdleTimeout(60_000);
        hikari.setMaxLifetime(300_000);
        return new HikariDataSource(hikari);
    }
}
