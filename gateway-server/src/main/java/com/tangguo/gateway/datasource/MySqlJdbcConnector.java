package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import com.tangguo.gateway.secret.ConnectionSecret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MySqlJdbcConnector implements JdbcConnector {
    private static final Set<String> BLOCKED_PRIVILEGES = Set.of(
            "ALL",
            "ALL PRIVILEGES",
            "FILE",
            "EXECUTE",
            "SUPER",
            "SYSTEM_USER",
            "SYSTEM_VARIABLES_ADMIN",
            "PERSIST_RO_VARIABLES_ADMIN",
            "CONNECTION_ADMIN",
            "BINLOG_ADMIN",
            "REPLICATION_SLAVE_ADMIN",
            "GROUP_REPLICATION_ADMIN",
            "SET_USER_ID",
            "PROXY",
            "CREATE USER",
            "CREATE ROLE",
            "DROP ROLE",
            "ROLE_ADMIN",
            "RELOAD",
            "SHUTDOWN");
    private static final Set<String> WRITE_PRIVILEGES = Set.of(
            "INSERT",
            "UPDATE",
            "DELETE",
            "CREATE",
            "ALTER",
            "DROP",
            "TRIGGER",
            "EVENT",
            "INDEX",
            "REFERENCES",
            "LOCK TABLES",
            "CREATE VIEW",
            "CREATE ROUTINE",
            "ALTER ROUTINE",
            "CREATE TEMPORARY TABLES");
    private static final Set<String> READ_ONLY_PRIVILEGES = Set.of("USAGE", "SELECT", "SHOW VIEW");

    @Override
    public boolean supports(DatabaseType databaseType) {
        return databaseType == DatabaseType.MYSQL || databaseType == DatabaseType.OCEANBASE_MYSQL;
    }

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String jdbcUrl(ConnectionSecret secret) {
        String database = encodePath(secret.database());
        String tlsMode = secret.properties().getOrDefault("tlsMode", "REQUIRED");
        return "jdbc:mysql://" + host(secret.host()) + ":" + secret.port() + "/" + database
                + "?sslMode=" + tlsMode + "&allowPublicKeyRetrieval=false"
                + "&allowMultiQueries=false&useUnicode=true&characterEncoding=UTF-8"
                + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false"
                + "&useCursorFetch=true&useServerPrepStmts=true"
                + "&connectTimeout=5000&socketTimeout=30000";
    }

    @Override
    public PrivilegeInspection inspectPrivileges(Connection connection) throws SQLException {
        String version = connection.getMetaData().getDatabaseProductVersion();
        String account = "";
        List<String> grants = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet userResult = statement.executeQuery("SELECT CURRENT_USER()")) {
            if (userResult.next()) {
                account = userResult.getString(1);
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SHOW GRANTS")) {
            while (resultSet.next()) {
                grants.add(resultSet.getString(1));
            }
        }
        return classifyGrants(version, account, grants);
    }

    PrivilegeInspection classifyGrants(String version, String account, List<String> grants) {
        String upperAccount = account == null ? "" : account.toUpperCase(Locale.ROOT);
        List<String> findings = new ArrayList<>();
        if (upperAccount.startsWith("ROOT@") || upperAccount.startsWith("SYS@")) {
            findings.add("当前账号属于数据库管理员账号");
            return new PrivilegeInspection(ReadOnlyStatus.BLOCKED, version, account, findings);
        }
        if (grants == null || grants.isEmpty()) {
            findings.add("SHOW GRANTS 未返回可验证授权");
            return new PrivilegeInspection(ReadOnlyStatus.BLOCKED, version, account, findings);
        }

        boolean blocked = false;
        for (String rawGrant : grants) {
            String grant = rawGrant == null
                    ? ""
                    : rawGrant.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (!grant.startsWith("GRANT ")) {
                findings.add("检测到无法识别的授权输出");
                blocked = true;
                continue;
            }
            if (grant.contains(" WITH GRANT OPTION") || grant.contains(" WITH ADMIN OPTION")) {
                findings.add("检测到授权转授能力");
                blocked = true;
            }
            int onIndex = grant.indexOf(" ON ");
            if (onIndex < 0) {
                // MySQL 不带 USING 的 SHOW GRANTS 不展开角色内容。任何角色授权都不能
                // 在不知道其嵌套/mandatory 权限时判定为 STRICT。
                findings.add("检测到未展开的角色或未知授权");
                blocked = true;
                continue;
            }
            String privilegeClause = grant.substring("GRANT ".length(), onIndex);
            for (String token : splitPrivilegeClause(privilegeClause)) {
                String privilege = basePrivilege(token);
                if (READ_ONLY_PRIVILEGES.contains(privilege)) {
                    continue;
                }
                if (WRITE_PRIVILEGES.contains(privilege)) {
                    findings.add("检测到写权限：" + privilege);
                    continue;
                }
                if (BLOCKED_PRIVILEGES.contains(privilege)) {
                    findings.add("检测到高危权限：" + privilege);
                } else {
                    // 动态权限可由数据库组件新增。未识别权限不得默认成为 STRICT。
                    findings.add("检测到未知或未允许权限：" + privilege);
                }
                blocked = true;
            }
        }
        if (blocked) {
            return new PrivilegeInspection(ReadOnlyStatus.BLOCKED, version, account, findings);
        }
        return new PrivilegeInspection(
                findings.isEmpty() ? ReadOnlyStatus.STRICT : ReadOnlyStatus.COMPATIBILITY,
                version,
                account,
                findings);
    }

    private List<String> splitPrivilegeClause(String clause) {
        List<String> privileges = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < clause.length(); index++) {
            char current = clause.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && depth > 0) {
                depth--;
            } else if (current == ',' && depth == 0) {
                privileges.add(clause.substring(start, index).trim());
                start = index + 1;
            }
        }
        privileges.add(clause.substring(start).trim());
        return privileges;
    }

    private String basePrivilege(String token) {
        int columnList = token.indexOf('(');
        return (columnList < 0 ? token : token.substring(0, columnList)).trim();
    }

    @Override
    public void beginReadOnly(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET TRANSACTION READ ONLY");
        }
    }

    private String host(String value) {
        return value.contains(":") ? "[" + value + "]" : value;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
