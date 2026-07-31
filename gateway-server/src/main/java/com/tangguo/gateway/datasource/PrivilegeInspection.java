package com.tangguo.gateway.datasource;

import com.tangguo.gateway.model.ReadOnlyStatus;
import java.util.List;

public record PrivilegeInspection(
        ReadOnlyStatus status, String databaseVersion, String account, List<String> findings) {}
