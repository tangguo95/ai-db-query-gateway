package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.ItemsResponse;
import com.tangguo.gateway.api.ApiDtos.QueryCreateRequest;
import com.tangguo.gateway.api.ApiDtos.QueryPreview;
import com.tangguo.gateway.api.ApiDtos.QueryView;
import com.tangguo.gateway.model.QueryStatus;
import com.tangguo.gateway.query.QueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queries")
public class QueryController {
    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping
    QueryView create(@Valid @RequestBody QueryCreateRequest request) {
        return queryService.create(request);
    }

    @PostMapping("/preview")
    QueryPreview preview(@Valid @RequestBody QueryCreateRequest request) {
        return queryService.preview(request);
    }

    @GetMapping
    ItemsResponse<QueryView> list(
            @RequestParam(required = false) QueryStatus status,
            @RequestParam(defaultValue = "100") int limit) {
        return new ItemsResponse<>(queryService.list(status, limit));
    }

    @GetMapping("/{id}")
    QueryView get(@PathVariable String id) {
        return queryService.get(id);
    }

    @GetMapping("/{id}/result")
    QueryView result(@PathVariable String id) {
        return queryService.getResultForAudit(id);
    }

    @PostMapping("/{id}/approve")
    QueryView approve(@PathVariable String id) {
        return queryService.approve(id);
    }

    @PostMapping("/{id}/execute")
    QueryView execute(@PathVariable String id) {
        return queryService.execute(id);
    }

    @PostMapping("/{id}/cancel")
    QueryView cancel(@PathVariable String id) {
        return queryService.cancel(id);
    }
}
