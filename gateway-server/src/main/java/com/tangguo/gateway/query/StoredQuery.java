package com.tangguo.gateway.query;

import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.QueryStatus;
import java.time.Instant;
import java.util.List;

public record StoredQuery(
        String id,
        String actor,
        ActorType actorType,
        String dataSourceId,
        String sqlCipher,
        String parametersCipher,
        String sqlFingerprint,
        String purpose,
        int requestedMaxRows,
        int effectiveMaxRows,
        QueryStatus status,
        List<String> riskReasons,
        Instant approvalExpiresAt,
        Instant approvedAt,
        Instant consumedAt,
        String errorCode,
        Instant createdAt,
        Instant updatedAt) {}
