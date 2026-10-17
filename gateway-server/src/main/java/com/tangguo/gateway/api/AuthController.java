package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.CurrentUser;
import com.tangguo.gateway.api.ApiDtos.LoginRequest;
import com.tangguo.gateway.api.ApiDtos.SetupRequest;
import com.tangguo.gateway.api.ApiDtos.SetupStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    @GetMapping("/setup/status")
    SetupStatus setupStatus(CsrfToken csrfToken) {
        csrfToken.getToken();
        return new SetupStatus(true, false, csrfToken.getHeaderName());
    }

    // 保留旧接口路径并明确告知调用方，避免旧客户端误以为仍需设置网页登录密码。
    @PostMapping("/setup")
    void setup(@Valid @RequestBody SetupRequest request) {
        throw new GatewayException(HttpStatus.GONE, "LOCAL_LOGIN_REMOVED", "本机免登录模式无需初始化管理员密码");
    }

    @PostMapping("/auth/login")
    CurrentUser login(@Valid @RequestBody LoginRequest request) {
        throw new GatewayException(HttpStatus.GONE, "LOCAL_LOGIN_REMOVED", "本机免登录模式无需登录，请直接打开控制台");
    }

    @PostMapping("/auth/logout")
    void logout(HttpServletRequest request) {
        throw new GatewayException(HttpStatus.GONE, "LOCAL_LOGIN_REMOVED", "本机免登录模式无需退出登录");
    }

    @GetMapping("/auth/me")
    CurrentUser currentUser(CsrfToken csrfToken) {
        // 首次打开页面时同时建立 CSRF cookie；写接口仍必须回传对应请求头。
        csrfToken.getToken();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return new CurrentUser(
                authentication != null && authentication.isAuthenticated(),
                authentication == null ? null : authentication.getName(),
                authentication == null ? List.of() : authentication.getAuthorities().stream()
                        .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                        .toList());
    }
}
