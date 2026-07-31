package com.tangguo.aidbgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 固定工具白名单到 REST 网关的抽象，便于在协议测试中隔离网络。
 */
interface GatewayClient {

    JsonNode call(String toolName, ObjectNode arguments) throws GatewayCallException;
}
