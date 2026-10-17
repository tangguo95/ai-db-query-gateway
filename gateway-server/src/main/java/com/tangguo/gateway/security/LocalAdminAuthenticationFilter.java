package com.tangguo.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 本机网页使用固定管理员身份；AI 接口及携带认证头的请求仍交给令牌认证。 */
public class LocalAdminAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (request.getRequestURI().startsWith("/api/")
                && !request.getRequestURI().startsWith("/api/ai/")
                && (authorization == null || authorization.isBlank())) {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "仅允许本机访问控制台");
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
