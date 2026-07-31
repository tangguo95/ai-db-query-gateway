package com.tangguo.gateway.config;

import com.tangguo.gateway.security.AbsoluteSessionTimeoutFilter;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.security.ApiTokenService;
import com.tangguo.gateway.security.BearerTokenFilter;
import com.tangguo.gateway.security.RequestSafetyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiTokenService tokenService,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            AuditService auditService,
            ActorContext actorContext)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").path("/"));
        RequestMatcher bearerRequest = request -> {
            String header = request.getHeader("Authorization");
            return header != null && header.startsWith("Bearer ");
        };
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/favicon.ico",
                                "/api/setup/status",
                                "/api/setup",
                                "/api/auth/login",
                                "/actuator/health")
                        .permitAll()
                        .requestMatchers("/api/ai/**")
                        .hasRole("AI")
                        .requestMatchers("/api/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .permitAll())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(bearerRequest))
                .cors(cors -> cors.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(
                                    response.getOutputStream(),
                                    Map.of("status", 401, "code", "UNAUTHENTICATED", "message", "请先登录"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(
                                    response.getOutputStream(),
                                    Map.of("status", 403, "code", "ACCESS_DENIED", "message", "无权执行该操作"));
                        }))
                .addFilterBefore(
                        new RequestSafetyFilter(properties, auditService, actorContext),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new AbsoluteSessionTimeoutFilter(properties), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new BearerTokenFilter(tokenService, objectMapper), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
