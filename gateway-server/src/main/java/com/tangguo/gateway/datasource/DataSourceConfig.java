package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.model.ReadOnlyStatus;
import java.time.Instant;

public record DataSourceConfig(
        String id,
        String name,
        DatabaseType databaseType,
        String secretRef,
        long credentialVersion,
        ReadOnlyStatus readOnlyStatus,
        boolean enabled,
        boolean allowCompatibility,
        int queryTimeoutSeconds,
        Instant lastTestedAt,
        String lastTestMessage,
        Instant createdAt,
        Instant updatedAt) {}
