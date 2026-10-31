package com.tangguo.gateway.api;

import com.tangguo.gateway.kubernetes.KubernetesService;
import com.tangguo.gateway.security.ActorContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ClusterView;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ContainerRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.LogsRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.QueryResult;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ResourceRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.SaveRequest;

@RestController
public class KubernetesController {
    private final KubernetesService service;
    private final ActorContext actor;
    public KubernetesController(KubernetesService service, ActorContext actor) { this.service=service; this.actor=actor; }
    @GetMapping("/api/kubernetes") public List<ClusterView> list() { return service.list(); }
    @GetMapping("/api/kubernetes/resource-types") public List<String> types() { return service.resources(); }
    @PostMapping("/api/kubernetes/test") public void test(@Valid @RequestBody SaveRequest request) { service.test(null, request); }
    @PostMapping("/api/kubernetes/{id}/test") public void test(@PathVariable String id, @Valid @RequestBody SaveRequest request) { service.test(id, request); }
    @PostMapping("/api/kubernetes") public ClusterView create(@Valid @RequestBody SaveRequest request) { return service.save(null, request); }
    @PutMapping("/api/kubernetes/{id}") public ClusterView update(@PathVariable String id, @Valid @RequestBody SaveRequest request) { return service.save(id, request); }
    @DeleteMapping("/api/kubernetes/{id}") public void delete(@PathVariable String id) { service.delete(id); }
    @GetMapping("/api/ai/kubernetes") public List<Map<String, Object>> aiList() {
        var token=actor.requireToken();
        return service.list().stream().filter(c -> c.enabled() && token.permitsKubernetes(c.id()))
                .map(c -> Map.<String,Object>of("id", c.id(), "name", c.name(), "namespaces", c.namespaces(),
                        "allowContainerQuery", c.allowContainerQuery(), "resources", service.resources())).toList();
    }
    @PostMapping({"/api/kubernetes/resources", "/api/ai/kubernetes/resources"}) public QueryResult resources(@Valid @RequestBody ResourceRequest request) { return service.resources(request); }
    @PostMapping({"/api/kubernetes/logs", "/api/ai/kubernetes/logs"}) public QueryResult logs(@Valid @RequestBody LogsRequest request) { return service.logs(request); }
    @PostMapping({"/api/kubernetes/container", "/api/ai/kubernetes/container"}) public QueryResult container(@Valid @RequestBody ContainerRequest request) { return service.container(request); }
}
