package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.AuditPage;
import com.tangguo.gateway.api.ApiDtos.DashboardView;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.security.ActorContext;
import java.util.LinkedHashMap;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OperationsController {
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final ActorContext actorContext;

    public OperationsController(
            JdbcTemplate jdbcTemplate, AuditService auditService, ActorContext actorContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.actorContext = actorContext;
    }

    @GetMapping("/audits")
    AuditPage audits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String queryId) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("page", page);
        filters.put("size", size);
        if (eventType != null && !eventType.isBlank()) {
            filters.put("eventType", eventType);
        }
        if (status != null && !status.isBlank()) {
            filters.put("status", status);
        }
        if (queryId != null && !queryId.isBlank()) {
            filters.put("queryId", queryId);
        }
        auditService.record(AuditCommand.simple(
                actorContext.actor(),
                ActorType.ADMIN,
                "AUDIT_VIEWED",
                "SUCCESS",
                filters));
        return auditService.findPage(page, size, eventType, status, queryId);
    }

    @GetMapping("/dashboard")
    DashboardView dashboard() {
        return new DashboardView(
                count("SELECT COUNT(*) FROM data_source_config WHERE deleted = 0"),
                count("SELECT COUNT(*) FROM data_source_config WHERE deleted = 0 AND enabled = 1"),
                count("SELECT COUNT(*) FROM data_source_config WHERE deleted = 0 AND read_only_status = 'STRICT'"),
                count(
                        "SELECT COUNT(*) FROM data_source_config WHERE deleted = 0 AND read_only_status = 'COMPATIBILITY'"),
                count("SELECT COUNT(*) FROM data_source_config WHERE deleted = 0 AND read_only_status = 'BLOCKED'"),
                count("SELECT COUNT(*) FROM query_request WHERE status = 'PENDING_APPROVAL'"),
                count("SELECT COUNT(*) FROM query_request WHERE date(created_at) = date('now', 'localtime')"),
                count(
                        "SELECT COUNT(*) FROM query_request WHERE date(created_at) = date('now', 'localtime') AND status IN ('FAILED','TIMED_OUT')"),
                auditService.isChainValid(),
                lastAuditAt());
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private Instant lastAuditAt() {
        String value = jdbcTemplate.queryForObject("SELECT MAX(occurred_at) FROM audit_event", String.class);
        return value == null ? null : Instant.parse(value);
    }
}
