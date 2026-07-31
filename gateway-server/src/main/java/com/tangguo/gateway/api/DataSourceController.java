package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.ColumnView;
import com.tangguo.gateway.api.ApiDtos.DataSourceCreateRequest;
import com.tangguo.gateway.api.ApiDtos.DataSourceTestResult;
import com.tangguo.gateway.api.ApiDtos.DataSourceUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.DataSourceView;
import com.tangguo.gateway.api.ApiDtos.SchemaView;
import com.tangguo.gateway.api.ApiDtos.TableView;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.security.ActorContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {
    private final DataSourceService dataSourceService;
    private final ActorContext actorContext;

    public DataSourceController(DataSourceService dataSourceService, ActorContext actorContext) {
        this.dataSourceService = dataSourceService;
        this.actorContext = actorContext;
    }

    @GetMapping
    List<DataSourceView> list() {
        return dataSourceService.list();
    }

    @PostMapping
    DataSourceView create(@Valid @RequestBody DataSourceCreateRequest request) {
        return dataSourceService.create(request, actorContext.actor());
    }

    @GetMapping("/{id}")
    DataSourceView get(@PathVariable String id) {
        return dataSourceService.get(id);
    }

    @PutMapping("/{id}")
    DataSourceView update(@PathVariable String id, @Valid @RequestBody DataSourceUpdateRequest request) {
        return dataSourceService.update(id, request, actorContext.actor());
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable String id) {
        dataSourceService.delete(id, actorContext.actor());
    }

    @PostMapping("/{id}/test")
    DataSourceTestResult test(@PathVariable String id) {
        return dataSourceService.test(id, actorContext.actor());
    }

    @GetMapping("/{id}/metadata/schemas")
    List<SchemaView> schemas(@PathVariable String id) {
        return dataSourceService.schemas(id);
    }

    @GetMapping("/{id}/metadata/tables")
    List<TableView> tables(@PathVariable String id, @RequestParam String schema) {
        return dataSourceService.tables(id, schema);
    }

    @GetMapping("/{id}/metadata/columns")
    List<ColumnView> columns(
            @PathVariable String id, @RequestParam String schema, @RequestParam String table) {
        return dataSourceService.columns(id, schema, table);
    }
}
