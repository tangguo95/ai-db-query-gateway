package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.secret.ConnectionSecret;
import java.sql.Connection;
import java.sql.SQLException;

public interface JdbcConnector {
    boolean supports(DatabaseType databaseType);

    String driverClassName();

    String jdbcUrl(ConnectionSecret secret);

    PrivilegeInspection inspectPrivileges(Connection connection) throws SQLException;

    void beginReadOnly(Connection connection) throws SQLException;
}
