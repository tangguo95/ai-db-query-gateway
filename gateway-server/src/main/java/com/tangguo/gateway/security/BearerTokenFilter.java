package com.tangguo.gateway.security;

import com.tangguo.gateway.api.GatewayException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class BearerTokenFilter extends OncePerRequestFilter {
    private final ApiTokenService tokenService;
    private final ObjectMapper objectMapper;

    public BearerTokenFilter(ApiTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            TokenContext token = tokenService.authenticate(authorization.substring(7).trim());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "token:" + token.name(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_AI")));
            authentication.setDetails(token);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (GatewayException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(exception.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    Map.of(
                            "timestamp", Instant.now().toString(),
                            "status", exception.status().value(),
                            "code", exception.code(),
                            "message", exception.getMessage()));
        }
    }
}
