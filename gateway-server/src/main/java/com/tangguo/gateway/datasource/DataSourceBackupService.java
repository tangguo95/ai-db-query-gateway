package com.tangguo.gateway.datasource;

import com.tangguo.gateway.api.ApiDtos.DataSourceCreateRequest;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.ActorType;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class DataSourceBackupService {
    private final DataSourceRepository repository;
    private final DataSourceConnectionManager connections;
    private final DataSourceService sources;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Validator validator;

    public DataSourceBackupService(DataSourceRepository repository, DataSourceConnectionManager connections,
            DataSourceService sources, AuditService audit, ObjectMapper mapper, Validator validator) {
        this.repository = repository;
        this.connections = connections;
        this.sources = sources;
        this.audit = audit;
        this.mapper = mapper;
        this.validator = validator;
    }

    public record BackupEntry(DataSourceCreateRequest source, int connectionTimeoutSeconds) {}
    public record BackupDocument(int version, List<BackupEntry> sources) {}
    public record ImportItem(String name, String status, String message) {}
    public record ImportResult(List<ImportItem> items) {}

    public synchronized byte[] exportFile(String actor, String password, List<String> ids) {
        record(actor, "DATASOURCE_BACKUP_REQUESTED", "REQUESTED", 0);
        byte[] plain = null;
        try {
            if (ids == null || ids.isEmpty() || ids.size() > 1000 || new HashSet<>(ids).size() != ids.size()) {
                throw new IllegalArgumentException();
            }
            List<BackupEntry> entries = new ArrayList<>();
            for (String id : ids) {
                var config = repository.require(id);
                var secret = connections.secret(config);
                entries.add(new BackupEntry(new DataSourceCreateRequest(config.name(), config.databaseType(),
                        secret.host(), secret.port(), secret.database(), secret.username(), secret.password(),
                        secret.properties(), config.allowCompatibility(), config.queryTimeoutSeconds()),
                        config.connectionTimeoutSeconds()));
            }
            plain = mapper.writeValueAsBytes(new BackupDocument(1, entries));
            byte[] file = BackupCipher.encrypt(plain, password);
            record(actor, "DATASOURCE_BACKUP_EXPORTED", "SUCCESS", entries.size());
            return file;
        } catch (Exception exception) {
            record(actor, "DATASOURCE_BACKUP_FAILED", "FAILED", 0);
            throw new GatewayException(HttpStatus.BAD_REQUEST, "DATASOURCE_BACKUP_FAILED",
                    "备份失败，请检查选择的数据源、本机凭据存储和备份密码");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized ImportResult importFile(String actor, String password, String encodedFile) {
        record(actor, "DATASOURCE_IMPORT_REQUESTED", "REQUESTED", 0);
        byte[] plain = null;
        List<BackupEntry> entries;
        try {
            if (encodedFile == null || encodedFile.length() > 7_000_000) throw new IllegalArgumentException();
            plain = BackupCipher.decrypt(Base64.getDecoder().decode(encodedFile), password);
            var document = mapper.readValue(plain, BackupDocument.class);
            if (document.version() != 1 || document.sources() == null
                    || document.sources().isEmpty() || document.sources().size() > 1000) throw new IllegalArgumentException();
            entries = document.sources();
            // 整个文件完成认证与字段验证后才开始写入。恢复无需先连接 VPN 或生产库。
            Set<String> names = new HashSet<>();
            for (var entry : entries) {
                if (entry == null || entry.source() == null || !validator.validate(entry.source()).isEmpty()
                        || !names.add(entry.source().name().trim())) throw new IllegalArgumentException();
                JdbcTimeouts.connectionTimeoutMillis(entry.connectionTimeoutSeconds());
                var source = entry.source();
                sources.validateEndpoint(source.host(), source.port(), source.properties(), false);
            }
        } catch (Exception exception) {
            record(actor, "DATASOURCE_IMPORT_FAILED", "FAILED", 0);
            throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_DATASOURCE_BACKUP",
                    "无法导入：备份密码错误、文件损坏或文件格式不受支持");
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }

        Set<String> existing = new HashSet<>();
        repository.findAll().forEach(source -> existing.add(source.name()));
        List<ImportItem> results = new ArrayList<>();
        boolean stopped = false;
        for (var entry : entries) {
            String name = entry.source().name().trim();
            if (stopped) {
                results.add(new ImportItem(name, "FAILED", "前一条导入失败，本条尚未处理，可重新导入备份"));
                continue;
            }
            if (existing.contains(name)) {
                results.add(new ImportItem(name, "SKIPPED", "同名数据源已存在，已跳过"));
                continue;
            }
            try {
                sources.createFromBackup(entry.source(), actor, entry.connectionTimeoutSeconds(), false);
                existing.add(name);
                results.add(new ImportItem(name, "IMPORTED", "已导入，连接复检通过后可查询"));
            } catch (RuntimeException exception) {
                // 保留逐条结果，凭据存储故障时用户可重试文件，同名记录会跳过。
                results.add(new ImportItem(name, "FAILED", "导入未完成，请检查本机安全存储或审计状态"));
                stopped = true;
            }
        }
        record(actor, "DATASOURCE_IMPORT_COMPLETED",
                results.size() == entries.size() && results.stream().noneMatch(i -> i.status().equals("FAILED"))
                        ? "SUCCESS" : "FAILED", results.size());
        return new ImportResult(List.copyOf(results));
    }

    private void record(String actor, String event, String status, int count) {
        audit.record(AuditCommand.simple(actor, ActorType.ADMIN, event, status, Map.of("count", count)));
    }
}
