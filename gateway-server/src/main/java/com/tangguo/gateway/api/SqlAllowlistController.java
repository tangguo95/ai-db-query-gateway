package com.tangguo.gateway.api;

import com.tangguo.gateway.audit.*;
import com.tangguo.gateway.model.*;
import com.tangguo.gateway.query.*;
import com.tangguo.gateway.security.ActorContext;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/settings/sql-allowlist")
public class SqlAllowlistController {
    private final SqlAllowlistService rules;
    private final AuditService audit;
    private final ActorContext actor;
    public SqlAllowlistController(SqlAllowlistService rules, AuditService audit, ActorContext actor) {
        this.rules = rules;
        this.audit = audit;
        this.actor = actor;
    }
    public record RuleRequest(List<String> names) {}
    public record RuleView(List<String> builtInFunctions, List<String> customFunctions,
            List<String> supportedHints, List<String> hints) {}

    @GetMapping
    RuleView get(@RequestParam(required=false) String dataSourceId,
            @RequestParam(defaultValue="OCEANBASE_ORACLE") DatabaseType databaseType) {
        return new RuleView(SqlPolicyService.builtInFunctions(),
                rules.functions(dataSourceId).stream().sorted().toList(),
                SqlAllowlistService.SUPPORTED_HINTS.stream().sorted().toList(),
                rules.hints(databaseType).stream().sorted().toList());
    }

    @PutMapping("/functions/{sourceId}")
    @Transactional
    void functions(@PathVariable String sourceId, @RequestBody RuleRequest request) {
        var before = rules.functions(sourceId);
        rules.saveFunctions(sourceId, request.names());
        audit.record(AuditCommand.simple(actor.actor(), ActorType.ADMIN, "SQL_FUNCTION_ALLOWLIST_UPDATED",
                "SUCCESS", Map.of("dataSourceId", sourceId, "before", before, "after", rules.functions(sourceId))));
    }

    @PutMapping("/hints/{type}")
    @Transactional
    void hints(@PathVariable DatabaseType type, @RequestBody RuleRequest request) {
        var before = rules.hints(type);
        rules.saveHints(type, request.names());
        audit.record(AuditCommand.simple(actor.actor(), ActorType.ADMIN, "SQL_HINT_ALLOWLIST_UPDATED",
                "SUCCESS", Map.of("databaseType", type.name(), "before", before, "after", rules.hints(type))));
    }
}
