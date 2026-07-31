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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OceanBaseOracleJdbcConnector implements JdbcConnector {
    private static final Set<String> READ_ONLY_SYSTEM_PRIVILEGES = Set.of(
            "CREATE SESSION",
            "SELECT ANY TABLE",
            "SELECT ANY DICTIONARY",
            "SELECT ANY TRANSACTION",
            "SHOW PROCESS");
    private static final Set<String> COMPATIBILITY_SYSTEM_PRIVILEGES = Set.of(
            "ALTER SESSION",
            "CREATE TABLE",
            "CREATE VIEW",
            "CREATE PROCEDURE",
            "CREATE SYNONYM",
            "CREATE SEQUENCE",
            "CREATE TRIGGER",
            "CREATE TYPE",
            "SELECT ANY SEQUENCE");
    private static final Set<String> HIGH_RISK_SYSTEM_PRIVILEGES = Set.of(
            "ALTER SYSTEM",
            "ALTER DATABASE",
            "BECOME USER",
            "CREATE USER",
            "ALTER USER",
            "DROP USER",
            "CREATE ROLE",
            "ALTER ANY ROLE",
            "DROP ANY ROLE",
            "GRANT ANY ROLE",
            "GRANT ANY PRIVILEGE",
            "GRANT ANY OBJECT PRIVILEGE",
            "AUDIT ANY",
            "EXECUTE ANY PROCEDURE",
            "EXECUTE ANY TYPE",
            "CREATE ANY DIRECTORY",
            "DROP ANY DIRECTORY",
            "CREATE DATABASE LINK",
            "CREATE PUBLIC DATABASE LINK",
            "DROP DATABASE LINK",
            "CREATE PUBLIC SYNONYM",
            "DROP PUBLIC SYNONYM",
            "CREATE TABLESPACE",
            "ALTER TABLESPACE",
            "DROP TABLESPACE",
            "PURGE DBA_RECYCLEBIN",
            "DEBUG CONNECT SESSION",
            "DEBUG ANY PROCEDURE",
            "BACKUP ANY TABLE",
            "LOCK ANY TABLE",
            "FLASHBACK ANY TABLE",
            "INSERT ANY TABLE",
            "UPDATE ANY TABLE",
            "DELETE ANY TABLE",
            "CREATE ANY TABLE",
            "ALTER ANY TABLE",
            "DROP ANY TABLE",
            "COMMENT ANY TABLE",
            "CREATE ANY INDEX",
            "ALTER ANY INDEX",
            "DROP ANY INDEX",
            "CREATE ANY VIEW",
            "DROP ANY VIEW",
            "CREATE ANY PROCEDURE",
            "ALTER ANY PROCEDURE",
            "DROP ANY PROCEDURE",
            "CREATE ANY SYNONYM",
            "DROP ANY SYNONYM",
            "CREATE ANY SEQUENCE",
            "ALTER ANY SEQUENCE",
            "DROP ANY SEQUENCE",
            "CREATE ANY TRIGGER",
            "ALTER ANY TRIGGER",
            "DROP ANY TRIGGER",
            "CREATE ANY TYPE",
            "ALTER ANY TYPE",
            "DROP ANY TYPE",
            "CREATE ANY OUTLINE",
            "ALTER ANY OUTLINE",
            "DROP ANY OUTLINE");
    private static final Set<String> READ_ONLY_OBJECT_PRIVILEGES = Set.of("SELECT", "READ");
    private static final Set<String> COMPATIBILITY_OBJECT_PRIVILEGES =
            Set.of("INSERT", "UPDATE", "DELETE", "ALTER", "INDEX", "REFERENCE", "REFERENCES");
    private static final Set<String> HIGH_RISK_OBJECT_PRIVILEGES =
            Set.of("EXECUTE", "DEBUG", "WRITE", "ENQUEUE", "DEQUEUE");
    private static final Set<String> HIGH_RISK_ROLES = Set.of(
            "DBA",
            "EXP_FULL_DATABASE",
            "IMP_FULL_DATABASE",
            "DATAPUMP_EXP_FULL_DATABASE",
            "DATAPUMP_IMP_FULL_DATABASE",
            "SCHEDULER_ADMIN",
            "AQ_ADMINISTRATOR_ROLE");
    private static final Set<String> HIGH_RISK_OWNED_OBJECT_TYPES = Set.of(
            "DATABASE LINK",
            "PROCEDURE",
            "FUNCTION",
            "PACKAGE",
            "PACKAGE BODY",
            "JAVA SOURCE",
            "JAVA CLASS",
            "LIBRARY",
            "JOB");

    @Override
    public boolean supports(DatabaseType databaseType) {
        return databaseType == DatabaseType.OCEANBASE_ORACLE;
    }

    @Override
    public String driverClassName() {
        return "com.oceanbase.jdbc.Driver";
    }

    @Override
    public String jdbcUrl(ConnectionSecret secret) {
        String tlsMode = secret.properties().getOrDefault("tlsMode", "REQUIRED");
        String tlsParameters = switch (tlsMode) {
            case "DISABLED" -> "useSSL=false";
            case "REQUIRED" -> "useSSL=true&verifyServerCertificate=false";
            default -> "useSSL=true&verifyServerCertificate=true";
        };
        return "jdbc:oceanbase://" + host(secret.host()) + ":" + secret.port() + "/"
                + URLEncoder.encode(secret.database(), StandardCharsets.UTF_8).replace("+", "%20")
                + "?" + tlsParameters + "&connectTimeout=5000&socketTimeout=30000";
    }

    @Override
    public PrivilegeInspection inspectPrivileges(Connection connection) throws SQLException {
        String version = connection.getMetaData().getDatabaseProductVersion();
        String account = "";
        List<GrantedPrivilege> privileges = new ArrayList<>();
        List<GrantedRole> roles = new ArrayList<>();
        List<String> ownedObjectTypes = new ArrayList<>();
        boolean sessionPrivilegesUnavailable = false;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT USER FROM DUAL")) {
            if (resultSet.next()) {
                account = resultSet.getString(1);
            }
        }
        try {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet =
                            statement.executeQuery("SELECT PRIVILEGE FROM SESSION_PRIVS")) {
                while (resultSet.next()) {
                    privileges.add(new GrantedPrivilege(
                            resultSet.getString(1), false, PrivilegeKind.SYSTEM));
                }
            }
        } catch (SQLException exception) {
            if (!isMissingPrivilegeView(exception)) {
                throw exception;
            }
            // 部分 OceanBase Oracle 租户不提供 SESSION_PRIVS。此时仍完整检查
            // USER/ROLE 的系统与对象授权视图，任何其它 SQL 错误继续失败隔离。
            sessionPrivilegesUnavailable = true;
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT PRIVILEGE, ADMIN_OPTION FROM USER_SYS_PRIVS")) {
            while (resultSet.next()) {
                privileges.add(new GrantedPrivilege(
                        resultSet.getString(1),
                        "YES".equalsIgnoreCase(resultSet.getString(2)),
                        PrivilegeKind.SYSTEM));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT GRANTED_ROLE, ADMIN_OPTION FROM USER_ROLE_PRIVS")) {
            while (resultSet.next()) {
                roles.add(new GrantedRole(
                        resultSet.getString(1),
                        "YES".equalsIgnoreCase(resultSet.getString(2))));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT PRIVILEGE, ADMIN_OPTION FROM ROLE_SYS_PRIVS")) {
            while (resultSet.next()) {
                privileges.add(new GrantedPrivilege(
                        resultSet.getString(1),
                        "YES".equalsIgnoreCase(resultSet.getString(2)),
                        PrivilegeKind.SYSTEM));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT PRIVILEGE, GRANTABLE FROM USER_TAB_PRIVS "
                                + "WHERE GRANTEE IN (USER, 'PUBLIC')")) {
            while (resultSet.next()) {
                privileges.add(new GrantedPrivilege(
                        resultSet.getString(1),
                        "YES".equalsIgnoreCase(resultSet.getString(2)),
                        PrivilegeKind.OBJECT));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT PRIVILEGE, GRANTABLE FROM ROLE_TAB_PRIVS")) {
            while (resultSet.next()) {
                privileges.add(new GrantedPrivilege(
                        resultSet.getString(1),
                        "YES".equalsIgnoreCase(resultSet.getString(2)),
                        PrivilegeKind.OBJECT));
            }
        }
        try (Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT DISTINCT OBJECT_TYPE FROM USER_OBJECTS")) {
            while (resultSet.next()) {
                ownedObjectTypes.add(resultSet.getString(1));
            }
        }

        PrivilegeInspection inspection =
                classifyPrivileges(version, account, privileges, roles, ownedObjectTypes);
        if (!sessionPrivilegesUnavailable) {
            return inspection;
        }
        List<String> findings = new ArrayList<>(inspection.findings());
        findings.add("SESSION_PRIVS 不可用，已使用用户与角色授权视图完成复检");
        return new PrivilegeInspection(
                inspection.status(), inspection.databaseVersion(), inspection.account(), List.copyOf(findings));
    }

    /**
     * 只有明确列入只读白名单的权限才能得到 STRICT。OceanBase 支持角色嵌套和新增权限，
     * 因此任何未知授权都必须失败关闭，不能沿用“未命中写权限即只读”的判断。
     */
    PrivilegeInspection classifyPrivileges(
            String version,
            String account,
            List<GrantedPrivilege> privileges,
            List<GrantedRole> roles,
            List<String> ownedObjectTypes) {
        Set<String> findings = new LinkedHashSet<>();
        boolean blocked = false;
        boolean compatibility = false;
        String normalizedAccount = normalize(account);
        if (normalizedAccount.isEmpty()) {
            findings.add("无法确认当前数据库账号");
            blocked = true;
        } else if ("SYS".equals(normalizedAccount) || "SYSTEM".equals(normalizedAccount)) {
            findings.add("当前账号属于数据库管理员账号");
            blocked = true;
        }

        if (privileges == null || privileges.isEmpty()) {
            findings.add("权限视图未返回可验证授权");
            blocked = true;
        } else {
            for (GrantedPrivilege privilege : privileges) {
                if (privilege == null || privilege.kind() == null) {
                    findings.add("检测到无法识别的权限记录");
                    blocked = true;
                    continue;
                }
                String name = normalize(privilege.name());
                if (name.isEmpty()) {
                    findings.add("检测到空权限名称");
                    blocked = true;
                    continue;
                }
                if (privilege.grantable()) {
                    findings.add("检测到高危权限：" + name + " (GRANTABLE/ADMIN OPTION)");
                    blocked = true;
                }
                if (privilege.kind() == PrivilegeKind.OBJECT) {
                    if (READ_ONLY_OBJECT_PRIVILEGES.contains(name)) {
                        continue;
                    }
                    if (COMPATIBILITY_OBJECT_PRIVILEGES.contains(name)) {
                        findings.add("检测到写权限：" + name);
                        compatibility = true;
                    } else {
                        findings.add((HIGH_RISK_OBJECT_PRIVILEGES.contains(name)
                                        ? "检测到高危对象权限："
                                        : "检测到未知或未允许对象权限：")
                                + name);
                        blocked = true;
                    }
                    continue;
                }
                if (READ_ONLY_SYSTEM_PRIVILEGES.contains(name)) {
                    continue;
                }
                if (COMPATIBILITY_SYSTEM_PRIVILEGES.contains(name)) {
                    findings.add("检测到非只读系统权限：" + name);
                    compatibility = true;
                } else {
                    findings.add((HIGH_RISK_SYSTEM_PRIVILEGES.contains(name)
                                    ? "检测到高危系统权限："
                                    : "检测到未知或未允许系统权限：")
                            + name);
                    blocked = true;
                }
            }
        }

        if (roles != null) {
            for (GrantedRole role : roles) {
                if (role == null) {
                    findings.add("检测到无法识别的角色记录");
                    blocked = true;
                    continue;
                }
                String name = normalize(role.name());
                if (name.isEmpty()) {
                    findings.add("检测到空角色名称");
                    blocked = true;
                } else if (role.adminOption() || HIGH_RISK_ROLES.contains(name)) {
                    findings.add("检测到高危角色：" + name
                            + (role.adminOption() ? " (ADMIN OPTION)" : ""));
                    blocked = true;
                }
            }
        }

        if (ownedObjectTypes != null) {
            for (String rawType : ownedObjectTypes) {
                String objectType = normalize(rawType);
                if (objectType.isEmpty()) {
                    findings.add("检测到无法识别的自有对象类型");
                    blocked = true;
                } else if (HIGH_RISK_OWNED_OBJECT_TYPES.contains(objectType)) {
                    findings.add("当前账号拥有高风险数据库对象：" + objectType);
                    blocked = true;
                } else {
                    // Schema 所有者无需显式授权即可修改或删除自有对象，不能判为严格只读。
                    findings.add("当前账号拥有可修改数据库对象：" + objectType);
                    compatibility = true;
                }
            }
        }

        ReadOnlyStatus status = blocked
                ? ReadOnlyStatus.BLOCKED
                : compatibility ? ReadOnlyStatus.COMPATIBILITY : ReadOnlyStatus.STRICT;
        return new PrivilegeInspection(
                status, version, account, List.copyOf(findings));
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private boolean isMissingPrivilegeView(SQLException exception) {
        return exception.getErrorCode() == 942
                || "42S02".equalsIgnoreCase(exception.getSQLState());
    }

    enum PrivilegeKind {
        SYSTEM,
        OBJECT
    }

    record GrantedPrivilege(String name, boolean grantable, PrivilegeKind kind) {}

    record GrantedRole(String name, boolean adminOption) {}
}
