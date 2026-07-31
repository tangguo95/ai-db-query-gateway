package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.DatabaseType;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ConnectorRegistry {
    private final List<JdbcConnector> connectors;

    public ConnectorRegistry(List<JdbcConnector> connectors) {
        this.connectors = List.copyOf(connectors);
    }

    public JdbcConnector require(DatabaseType type) {
        return connectors.stream()
                .filter(connector -> connector.supports(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("暂不支持数据库类型：" + type));
    }
}
