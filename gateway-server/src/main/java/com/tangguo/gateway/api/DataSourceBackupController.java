package com.tangguo.gateway.api;

import com.tangguo.gateway.datasource.DataSourceBackupService;
import com.tangguo.gateway.security.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/datasources/backup")
public class DataSourceBackupController {
    private final DataSourceBackupService backups;
    private final ActorContext actor;
    public DataSourceBackupController(DataSourceBackupService backups, ActorContext actor) {
        this.backups = backups;
        this.actor = actor;
    }
    public record ExportRequest(@NotNull @Size(min=12, max=256) String password,
            @NotEmpty @Size(max=1000) List<@NotBlank String> dataSourceIds) {}
    public record ImportRequest(@NotNull @Size(min=12, max=256) String password,
            @NotBlank @Size(max=7_000_000) String file) {}

    @PostMapping("/export")
    ResponseEntity<byte[]> exportFile(@Valid @RequestBody ExportRequest request) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=datasources.gwbackup")
                .header("Cache-Control", "no-store")
                .body(backups.exportFile(actor.actor(), request.password(), request.dataSourceIds()));
    }

    @PostMapping("/import")
    DataSourceBackupService.ImportResult importFile(@Valid @RequestBody ImportRequest request) {
        return backups.importFile(actor.actor(), request.password(), request.file());
    }
}
