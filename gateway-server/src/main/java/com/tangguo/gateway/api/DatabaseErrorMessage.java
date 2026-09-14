package com.tangguo.gateway.api;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 用异常类型和数据库代码生成诊断说明，禁止将可能包含凭据的驱动消息原样返回。 */
final class DatabaseErrorMessage {
    private DatabaseErrorMessage() {}

    static String describe(Throwable cause) {
        SQLException sql = null;
        String reason = "";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = cause; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException exception) sql = exception;
            if (current instanceof java.net.ConnectException) reason = "数据库连接被拒绝，请检查目标服务或本地端口转发";
            if (current instanceof java.net.UnknownHostException) reason = "数据库主机解析失败，请检查 DNS 或 VPN";
            if (current instanceof java.net.NoRouteToHostException) reason = "无法路由到数据库，请检查 VPN 路由";
            if (current instanceof java.net.SocketTimeoutException) reason = "等待数据库网络响应超时，请检查链路和数据库负载";
            if (current instanceof javax.net.ssl.SSLException) reason = "数据库 TLS 握手或证书校验失败";
        }
        if (sql == null) return reason;
        String state = sql.getSQLState();
        int code = sql.getErrorCode();
        if (reason.isEmpty()) {
            if (sql instanceof java.sql.SQLTimeoutException || code == 1317 || code == 3024
                    || "57014".equals(state)) reason = "数据库操作超时或已被取消";
            else if (code == 1045 || code == 1017 || "28000".equals(state)) reason = "数据库账号认证失败，请检查凭据";
            else if (code == 1146 || code == 942 || "42S02".equals(state)) reason = "表或视图不存在，或当前账号不可见";
            else if (code == 1054 || code == 904 || "42S22".equals(state)) reason = "查询引用了不存在或无效的字段";
            else if (code == 1044 || code == 1142 || code == 1143 || code == 1031
                    || "42501".equals(state)) reason = "数据库账号没有执行该查询或访问对象的权限";
            else if (code == 1064 || code == 900 || code == 933 || "42000".equals(state))
                reason = "数据库拒绝了 SQL 语法、方言或相关访问权限";
            else if (state != null && state.startsWith("08")) reason = "数据库连接中断或协议握手失败";
            else if (code == 1205 || code == 1213) reason = "数据库锁等待超时或发生死锁";
            else if (sql instanceof java.sql.SQLTransientConnectionException)
                reason = "等待连接池可用连接超时，可能连接建立缓慢或连接已占满";
            else reason = "数据库执行失败，可使用下面的数据库错误码进一步定位";
        }
        return reason + " [SQLState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "未知")
                + ", 数据库错误码=" + code + "]";
    }
}
