package com.tangguo.gateway.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Vue Router 使用 history 模式；直接刷新控制台路由时仍回到同一份自托管 index.html。
 * API、静态资源和任意未知路径不由这里兜底，避免掩盖真实的 404。
 */
@Controller
public class SpaController {

    @GetMapping({
        "/setup",
        "/login",
        "/dashboard",
        "/datasources",
        "/workbench",
        "/approvals",
        "/audits",
        "/tokens",
        "/security"
    })
    String frontendRoute() {
        return "forward:/index.html";
    }
}
