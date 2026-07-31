package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.ColumnView;
import com.tangguo.gateway.api.ApiDtos.DataSourceView;
import com.tangguo.gateway.api.ApiDtos.ItemsResponse;
import com.tangguo.gateway.api.ApiDtos.QueryCreateRequest;
import com.tangguo.gateway.api.ApiDtos.QueryView;
import com.tangguo.gateway.api.ApiDtos.SchemaView;
import com.tangguo.gateway.api.ApiDtos.TableView;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.query.QueryService;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.security.TokenContext;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final DataSourceService dataSourceService;
    private final QueryService queryService;
    private final ActorContext actorContext;
    private final AuditService auditService;

    public AiController(
            DataSourceService dataSourceService,
            QueryService queryService,
            ActorContext actorContext,
            AuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.queryService = queryService;
        this.actorContext = actorContext;
        this.auditService = auditService;
    }

    @GetMapping("/data-sources")
    ItemsResponse<DataSourceView> dataSources() {
        TokenContext token = actorContext.requireToken();
        var items = dataSourceService.list().stream()
                .filter(dataSource -> dataSource.enabled() && token.permitsDataSource(dataSource.id()))
                .toList();
        auditMetadata("AI_DATASOURCES_LISTED", null, Map.of("itemCount", items.size()), "SUCCESS", null);
        return new ItemsResponse<>(items);
    }

    @GetMapping("/data-sources/{id}/schemas")
    ItemsResponse<SchemaView> schemas(@PathVariable String id) {
        assertScope(id);
        auditMetadata("AI_SCHEMAS_REQUESTED", id, Map.of(), "REQUESTED", null);
        return new ItemsResponse<>(dataSourceService.schemas(id));
    }

    @GetMapping("/data-sources/{id}/schemas/{schema}/tables")
    ItemsResponse<TableView> tables(@PathVariable String id, @PathVariable String schema) {
        assertScope(id);
        auditMetadata("AI_TABLES_REQUESTED", id, Map.of("schema", schema), "REQUESTED", null);
        return new ItemsResponse<>(dataSourceService.tables(id, schema));
    }

    @GetMapping("/data-sources/{id}/schemas/{schema}/tables/{table}")
    ItemsResponse<ColumnView> describe(
            @PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        assertScope(id);
        auditMetadata(
                "AI_TABLE_DESCRIPTION_REQUESTED",
                id,
                Map.of("schema", schema, "table", table),
                "REQUESTED",
                null);
        return new ItemsResponse<>(dataSourceService.columns(id, schema, table));
    }

    @PostMapping("/queries")
    QueryView create(@Valid @RequestBody QueryCreateRequest request) {
        assertScope(request.dataSourceId());
        return queryService.create(request);
    }

    @GetMapping("/queries/{queryId}")
    QueryView get(@PathVariable String queryId) {
        return queryService.get(queryId);
    }

    @PostMapping("/queries/{queryId}/execute")
    QueryView execute(@PathVariable String queryId) {
        return queryService.execute(queryId);
    }

    @PostMapping("/queries/{queryId}/cancel")
    QueryView cancel(@PathVariable String queryId) {
        return queryService.cancel(queryId);
    }

    private void assertScope(String dataSourceId) {
        if (!actorContext.requireToken().permitsDataSource(dataSourceId)) {
            auditMetadata(
                    "AI_METADATA_SCOPE_REJECTED",
                    dataSourceId,
                    Map.of(),
                    "REJECTED",
                    "DATASOURCE_SCOPE_DENIED");
            throw new GatewayException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "DATASOURCE_SCOPE_DENIED",
                    "访问令牌无权使用该数据源");
        }
    }

    private void auditMetadata(
            String eventType,
            String dataSourceId,
            Map<String, Object> payload,
            String status,
            String errorCode) {
        auditService.record(new AuditCommand(
                actorContext.actor(),
                ActorType.API_TOKEN,
                eventType,
                dataSourceId,
                null,
                null,
                null,
                payload,
                status,
                null,
                null,
                null,
                errorCode));
    }
}
