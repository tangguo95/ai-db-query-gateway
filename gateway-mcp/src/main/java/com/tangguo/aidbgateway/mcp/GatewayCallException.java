package com.tangguo.aidbgateway.mcp;

/**
 * 可安全返回给 MCP 调用方的网关错误。
 *
 * <p>只携带稳定错误码和不含上游响应正文的说明，避免异常链泄露令牌、SQL 或查询结果。</p>
 */
final class GatewayCallException extends Exception {

    private final String code;

    GatewayCallException(String code, String safeMessage) {
        super(safeMessage, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
