package com.tangguo.gateway.audit;

import com.tangguo.gateway.model.ActorType;
import java.util.Map;

public record AuditCommand(
        String actor,
        ActorType actorType,
        String eventType,
        String dataSourceId,
        String queryId,
        String purpose,
        String sqlFingerprint,
        Map<String, Object> sensitivePayload,
        String status,
        Long durationMs,
        Integer rowCount,
        Long byteCount,
        String errorCode) {

    public AuditCommand {
        sensitivePayload = sensitivePayload == null ? Map.of() : Map.copyOf(sensitivePayload);
    }

    public static AuditCommand simple(
            String actor, ActorType actorType, String eventType, String status, Map<String, Object> payload) {
        return new AuditCommand(
                actor, actorType, eventType, null, null, null, null, payload, status, null, null, null, null);
    }
}
