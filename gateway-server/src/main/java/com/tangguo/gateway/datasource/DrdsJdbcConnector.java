package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.secret.ConnectionSecret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * DRDS 的老 MySQL 兼容层连接器。
 *
 * <p>独立保留 Connector/J 5.1 的驱动类和 URL 参数，避免为了兼容 DRDS 改变普通 MySQL
 * 数据源的 Connector/J 8.4 行为。查询执行、只读事务和权限复检仍复用 MySQL 连接器逻辑。
 */
@Component
public class DrdsJdbcConnector extends MySqlJdbcConnector {

    @Override
    public boolean supports(DatabaseType databaseType) {
        return databaseType == DatabaseType.DRDS_MYSQL;
    }

    @Override
    public String driverClassName() {
        return "com.mysql.jdbc.Driver";
    }

    @Override
    public String jdbcUrl(ConnectionSecret secret) {
        String tlsMode = secret.properties().getOrDefault("tlsMode", "REQUIRED");
        String tlsParameters = switch (tlsMode) {
            case "DISABLED" -> "useSSL=false";
            case "REQUIRED" -> "useSSL=true&requireSSL=true&verifyServerCertificate=false";
            default -> "useSSL=true&requireSSL=true&verifyServerCertificate=true";
        };
        return "jdbc:mysql://" + host(secret.host()) + ":" + secret.port() + "/"
                + encodePath(secret.database())
                + "?" + tlsParameters
                + "&allowPublicKeyRetrieval=false"
                + "&allowMultiQueries=false&useUnicode=true&characterEncoding=UTF-8"
                + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
                + "&useCursorFetch=true&useServerPrepStmts=true"
                + "&connectTimeout=5000&socketTimeout=30000";
    }

    private String host(String value) {
        return value.contains(":") ? "[" + value + "]" : value;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
