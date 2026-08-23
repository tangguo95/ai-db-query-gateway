package com.tangguo.gateway.datasource;

/**
 * 将数据源级连接等待时间同时应用到 Hikari 和 JDBC URL。
 *
 * <p>部分 VPN/RDS 链路 TCP 已建立，但数据库认证握手仍可能耗时几十秒；只调 Hikari
 * 或只调 JDBC 任一侧都会继续提前失败。默认值保持原有 5 秒连接等待和 30 秒读超时。
 */
final class JdbcTimeouts {
    private static final int MIN_SECONDS = 5;
    private static final int MAX_SECONDS = 120;
    private static final long DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000L;
    private static final long SOCKET_GRACE_MILLIS = 15_000L;

    private JdbcTimeouts() {}

    static long connectionTimeoutMillis(int seconds) {
        validate(seconds);
        return seconds * 1_000L;
    }

    static String apply(String jdbcUrl, int connectionTimeoutSeconds) {
        long connectionTimeoutMillis = connectionTimeoutMillis(connectionTimeoutSeconds);
        long socketTimeoutMillis = Math.max(
                DEFAULT_SOCKET_TIMEOUT_MILLIS, connectionTimeoutMillis + SOCKET_GRACE_MILLIS);
        return replaceParameter(
                replaceParameter(jdbcUrl, "connectTimeout", connectionTimeoutMillis),
                "socketTimeout",
                socketTimeoutMillis);
    }

    private static String replaceParameter(String jdbcUrl, String name, long value) {
        return jdbcUrl.replaceAll("([?&]" + name + "=)\\d+", "$1" + value);
    }

    private static void validate(int seconds) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("数据源连接超时必须在 5-120 秒之间");
        }
    }
}
