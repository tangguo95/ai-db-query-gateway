package com.tangguo.gateway.security;

import com.tangguo.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.web.filter.OncePerRequestFilter;

public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {
    private static final String CREATED_AT = "gateway.session.createdAt";
    private final GatewayProperties properties;

    public AbsoluteSessionTimeoutFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Long createdAt = (Long) session.getAttribute(CREATED_AT);
            if (createdAt == null) {
                session.setAttribute(CREATED_AT, session.getCreationTime());
            } else if (Instant.now().toEpochMilli() - createdAt
                    > properties.getSecurity().getAbsoluteSessionTimeout().toMillis()) {
                session.invalidate();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
