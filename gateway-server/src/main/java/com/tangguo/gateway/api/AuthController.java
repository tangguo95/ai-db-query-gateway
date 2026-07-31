package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.CurrentUser;
import com.tangguo.gateway.api.ApiDtos.LoginRequest;
import com.tangguo.gateway.api.ApiDtos.SetupRequest;
import com.tangguo.gateway.api.ApiDtos.SetupStatus;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.security.BootstrapService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final BootstrapService bootstrapService;
    private final AuditService auditService;
    private final HttpSessionSecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();
    private final ConcurrentHashMap<String, LoginWindow> loginWindows = new ConcurrentHashMap<>();

    public AuthController(BootstrapService bootstrapService, AuditService auditService) {
        this.bootstrapService = bootstrapService;
        this.auditService = auditService;
    }

    @GetMapping("/setup/status")
    SetupStatus setupStatus(CsrfToken csrfToken) {
        csrfToken.getToken();
        boolean initialized = bootstrapService.isInitialized();
        return new SetupStatus(initialized, !initialized, csrfToken.getHeaderName());
    }

    @PostMapping("/setup")
    void setup(@Valid @RequestBody SetupRequest request) {
        auditService.record(AuditCommand.simple(
                "anonymous", ActorType.ANONYMOUS, "ADMIN_SETUP_REQUESTED", "REQUESTED", Map.of()));
        bootstrapService.setup(request.bootstrapToken(), request.password());
        auditService.record(AuditCommand.simple(
                "admin", ActorType.ADMIN, "ADMIN_SETUP", "SUCCESS", Map.of()));
    }

    @PostMapping("/auth/login")
    CurrentUser login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        String client = servletRequest.getRemoteAddr();
        enforceLoginRate(client);
        auditService.record(AuditCommand.simple(
                "anonymous",
                ActorType.ANONYMOUS,
                "LOGIN_REQUESTED",
                "REQUESTED",
                Map.of("remoteAddress", client)));
        if (!bootstrapService.isInitialized() || !bootstrapService.verifyPassword(request.password())) {
            auditService.record(AuditCommand.simple(
                    "anonymous",
                    ActorType.ANONYMOUS,
                    "LOGIN_FAILED",
                    "REJECTED",
                    Map.of("remoteAddress", client)));
            throw new GatewayException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "管理员密码错误");
        }
        loginWindows.remove(client);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        context.setAuthentication(authentication);
        auditService.record(AuditCommand.simple("admin", ActorType.ADMIN, "LOGIN_SUCCESS", "SUCCESS", Map.of()));
        SecurityContextHolder.setContext(context);
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        contextRepository.saveContext(context, servletRequest, servletResponse);
        return new CurrentUser(true, "admin", List.of("ADMIN"));
    }

    @PostMapping("/auth/logout")
    void logout(HttpServletRequest request) {
        auditService.record(AuditCommand.simple("admin", ActorType.ADMIN, "LOGOUT", "SUCCESS", Map.of()));
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/auth/me")
    CurrentUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return new CurrentUser(
                authentication != null && authentication.isAuthenticated(),
                authentication == null ? null : authentication.getName(),
                authentication == null
                        ? List.of()
                        : authentication.getAuthorities().stream()
                                .map(authority -> authority.getAuthority().replace("ROLE_", ""))
                                .toList());
    }

    private void enforceLoginRate(String client) {
        long window = Instant.now().getEpochSecond() / 300;
        LoginWindow loginWindow = loginWindows.compute(
                client,
                (key, previous) -> previous == null || previous.window != window
                        ? new LoginWindow(window)
                        : previous);
        if (loginWindow.attempts.incrementAndGet() > 10) {
            throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED", "登录尝试过于频繁");
        }
    }

    private static final class LoginWindow {
        private final long window;
        private final AtomicInteger attempts = new AtomicInteger();

        private LoginWindow(long window) {
            this.window = window;
        }
    }
}
