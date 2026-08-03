package com.tangguo.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    private String dataDir;
    private boolean remoteEnabled;
    private final Secrets secrets = new Secrets();
    private final Query query = new Query();
    private final Security security = new Security();

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isRemoteEnabled() {
        return remoteEnabled;
    }

    public void setRemoteEnabled(boolean remoteEnabled) {
        this.remoteEnabled = remoteEnabled;
    }

    public Secrets getSecrets() {
        return secrets;
    }

    public Query getQuery() {
        return query;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Secrets {
        private String helperPath = "native/macos-keychain/build/keychain-helper";
        private boolean allowInMemory;

        public String getHelperPath() {
            return helperPath;
        }

        public void setHelperPath(String helperPath) {
            this.helperPath = helperPath;
        }

        public boolean isAllowInMemory() {
            return allowInMemory;
        }

        public void setAllowInMemory(boolean allowInMemory) {
            this.allowInMemory = allowInMemory;
        }
    }

    public static class Query {
        private int defaultMaxRows = 200;
        private int hardMaxRows = 1000;
        private int maxSqlBytes = 32768;
        private int maxResponseBytes = 5242880;
        private int maxFieldBytes = 262144;
        private int globalConcurrency = 4;
        private int perDataSourceConcurrency = 2;
        private Duration approvalTtl = Duration.ofMinutes(5);
        /**
         * 高风险 AI 查询是否默认进入网页审批队列；数据库中的管理员开关可覆盖这个默认值。
         */
        private boolean approvalRequired = true;

        public int getDefaultMaxRows() {
            return defaultMaxRows;
        }

        public void setDefaultMaxRows(int defaultMaxRows) {
            this.defaultMaxRows = defaultMaxRows;
        }

        public int getHardMaxRows() {
            return hardMaxRows;
        }

        public void setHardMaxRows(int hardMaxRows) {
            this.hardMaxRows = hardMaxRows;
        }

        public int getMaxSqlBytes() {
            return maxSqlBytes;
        }

        public void setMaxSqlBytes(int maxSqlBytes) {
            this.maxSqlBytes = maxSqlBytes;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getMaxFieldBytes() {
            return maxFieldBytes;
        }

        public void setMaxFieldBytes(int maxFieldBytes) {
            this.maxFieldBytes = maxFieldBytes;
        }

        public int getGlobalConcurrency() {
            return globalConcurrency;
        }

        public void setGlobalConcurrency(int globalConcurrency) {
            this.globalConcurrency = globalConcurrency;
        }

        public int getPerDataSourceConcurrency() {
            return perDataSourceConcurrency;
        }

        public void setPerDataSourceConcurrency(int perDataSourceConcurrency) {
            this.perDataSourceConcurrency = perDataSourceConcurrency;
        }

        public Duration getApprovalTtl() {
            return approvalTtl;
        }

        public void setApprovalTtl(Duration approvalTtl) {
            this.approvalTtl = approvalTtl;
        }

        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        public void setApprovalRequired(boolean approvalRequired) {
            this.approvalRequired = approvalRequired;
        }
    }

    public static class Security {
        private int tokenRatePerMinute = 30;
        private Duration absoluteSessionTimeout = Duration.ofHours(8);

        public int getTokenRatePerMinute() {
            return tokenRatePerMinute;
        }

        public void setTokenRatePerMinute(int tokenRatePerMinute) {
            this.tokenRatePerMinute = tokenRatePerMinute;
        }

        public Duration getAbsoluteSessionTimeout() {
            return absoluteSessionTimeout;
        }

        public void setAbsoluteSessionTimeout(Duration absoluteSessionTimeout) {
            this.absoluteSessionTimeout = absoluteSessionTimeout;
        }
    }
}
