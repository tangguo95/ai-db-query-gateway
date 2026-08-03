package com.tangguo.gateway.query;

import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.security.SettingService;
import org.springframework.stereotype.Service;

/**
 * 管理高风险 AI 查询是否需要网页一次性审批。
 *
 * <p>默认值来自启动配置，管理员在网页中修改后写入本地 SQLite 的 app_setting，重启后继续生效。
 * 该开关只影响审批分支，不会跳过 SQL AST、只读连接、超时或结果资源限制。</p>
 */
@Service
public class QueryApprovalPolicyService {
    static final String APPROVAL_REQUIRED_KEY = "query.approval.required";

    private final SettingService settings;
    private final GatewayProperties properties;

    public QueryApprovalPolicyService(SettingService settings, GatewayProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    public boolean approvalRequired() {
        return settings.get(APPROVAL_REQUIRED_KEY)
                .map(String::trim)
                .filter(value -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
                .map(Boolean::parseBoolean)
                .orElse(properties.getQuery().isApprovalRequired());
    }

    public void setApprovalRequired(boolean approvalRequired) {
        settings.put(APPROVAL_REQUIRED_KEY, Boolean.toString(approvalRequired));
    }
}
