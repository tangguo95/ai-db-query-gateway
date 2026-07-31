package com.tangguo.gateway.query;

import java.util.List;
import java.util.Set;

public record SqlAnalysis(
        String fingerprint,
        Set<String> schemas,
        Set<String> tables,
        List<String> riskReasons,
        int parameterCount) {}
