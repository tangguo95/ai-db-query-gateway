package com.tangguo.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.model.ActorType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSafetyFilterTest {
    private AuditService auditService;
    private ActorContext actorContext;
    private RequestSafetyFilter filter;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        actorContext = mock(ActorContext.class);
        when(actorContext.actor()).thenReturn("anonymous");
        when(actorContext.actorType()).thenReturn(ActorType.ANONYMOUS);
        filter = new RequestSafetyFilter(new GatewayProperties(), auditService, actorContext);
    }

    @Test
    void rejectsOriginFromDifferentLoopbackPortBeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/setup");
        request.setScheme("http");
        request.setServerPort(8765);
        request.addHeader("Host", "localhost:8765");
        request.addHeader("Origin", "http://localhost:9999");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_NOT_ALLOWED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void returnsStable503WhenPreRequestAuditIsUnavailable() throws Exception {
        doThrow(new GatewayException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AUDIT_UNAVAILABLE",
                        "audit failed"))
                .when(auditService)
                .record(any());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        request.setServerPort(8765);
        request.addHeader("Host", "127.0.0.1:8765");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUDIT_UNAVAILABLE");
        verify(chain, never()).doFilter(any(), any());
    }
}
