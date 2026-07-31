package com.tangguo.aidbgateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 本地 MCP STDIO 适配器入口。
 *
 * <p>该进程的 stdout 只承载 MCP 协议消息，stderr 保持静默，避免令牌或数据库响应
 * 被桌面客户端收集到普通日志中。</p>
 */
public final class GatewayMcpApplication {

    private GatewayMcpApplication() {
    }

    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        GatewayClient gatewayClient;
        try {
            GatewayConfig config = GatewayConfig.fromEnvironment(System.getenv());
            gatewayClient = new HttpGatewayClient(config, objectMapper);
        } catch (RuntimeException ignored) {
            // 配置错误也不写 stderr；握手仍可完成，实际工具调用返回稳定安全错误。
            gatewayClient = (toolName, arguments) -> {
                throw new GatewayCallException(
                        "INVALID_GATEWAY_URL",
                        "AI_DB_GATEWAY_URL 不是合法的本机回环地址"
                );
            };
        }
        McpServer server = new McpServer(objectMapper, gatewayClient);
        server.run(System.in, System.out);
    }
}
