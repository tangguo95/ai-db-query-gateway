package com.tangguo.gateway.security;

import java.time.Instant;
import java.util.Set;

public record TokenContext(
        String tokenId,
        String name,
        Set<String> dataSourceIds,
        Set<String> permissions,
        Instant expiresAt) {

    public boolean permitsServer(String serverId) {
        return permissions.contains("server:execute:" + serverId);
    }

    public boolean permitsDataSource(String dataSourceId) {
        return dataSourceIds.contains("*") || dataSourceIds.contains(dataSourceId);
    }
}
