package com.tangguo.gateway.kubernetes;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.secret.SecretStore;
import com.tangguo.gateway.security.ActorContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ClusterView;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ContainerRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.LogsRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.QueryResult;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ResourceRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.SaveRequest;

@Service
public class KubernetesService {
    private final JdbcTemplate jdbc;
    private final SecretStore secrets;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final ActorContext actor;
    private final KubeconfigPolicy configPolicy;
    private final KubernetesQueryPolicy policy;
    private final KubectlRunner runner;
    private final Semaphore capacity = new Semaphore(2);
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastQuery = new ConcurrentHashMap<>();

    public KubernetesService(JdbcTemplate jdbc, SecretStore secrets, ObjectMapper mapper, AuditService audit,
                             ActorContext actor, KubeconfigPolicy configPolicy, KubernetesQueryPolicy policy, KubectlRunner runner) {
        this.jdbc=jdbc; this.secrets=secrets; this.mapper=mapper; this.audit=audit;
        this.actor=actor; this.configPolicy=configPolicy; this.policy=policy; this.runner=runner;
    }
    public List<ClusterView> list() {
        return jdbc.query("SELECT * FROM kubernetes_config ORDER BY name", (rs,n) -> new ClusterView(rs.getString("id"), rs.getString("name"),
                rs.getString("context_name"), mapper.readValue(rs.getString("namespaces"), new TypeReference<List<String>>() {}),
                rs.getBoolean("allow_container_query"), rs.getBoolean("enabled"), rs.getString("updated_at")));
    }
    public List<String> resources() { return policy.resources(); }
    private ClusterView get(String id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new GatewayException(HttpStatus.NOT_FOUND, "KUBERNETES_NOT_FOUND", "集群配置不存在"));
    }
    private String config(String id) {
        return secrets.get("gateway.kubernetes." + id).orElseThrow(() -> bad("kubeconfig 不可用，请重新上传"));
    }
    private KubeconfigPolicy.Prepared prepare(String id, SaveRequest request) {
        if (request.namespaces().contains("*") && request.namespaces().size() != 1) throw bad("全部命名空间请仅填写 *");
        for (String namespace : request.namespaces()) if (!namespace.equals("*")) policy.namespace(List.of("*"), namespace);
        String input = request.kubeconfig();
        if (id != null) {
            get(id);
            if (input == null || input.isBlank()) input = config(id);
        }
        return configPolicy.prepare(input, request.context());
    }
    public void test(String id, SaveRequest request) {
        var prepared = prepare(id, request);
        runLocked(id == null ? "draft" : id, () -> {
            event("KUBERNETES_TEST_REQUESTED", "REQUESTED", Map.of("name", request.name()));
            checkConnection(prepared.json());
            event("KUBERNETES_TESTED", "SUCCESS", Map.of("name", request.name()));
            return null;
        });
    }
    private void checkConnection(String config) {
        var version = mapper.readTree(runner.run(config, List.of("get", "--raw", "/version")));
        if (!version.has("major") || !version.has("minor")) throw bad("目标未返回有效 Kubernetes 版本信息");
    }
    public ClusterView save(String id, SaveRequest request) {
        String key = id == null ? UUID.randomUUID().toString() : id;
        return runLocked(key, () -> {
            var prepared = prepare(id, request);
            event("KUBERNETES_SAVE_REQUESTED", "REQUESTED", Map.of("clusterId", key, "name", request.name(), "allowContainerQuery", request.allowContainerQuery()));
            checkConnection(prepared.json());
            String reference = "gateway.kubernetes." + key;
            if (id == null) {
                secrets.put(reference, prepared.json());
                try {
                    jdbc.update("INSERT INTO kubernetes_config(id,name,secret_ref,context_name,namespaces,allow_container_query,updated_at) VALUES(?,?,?,?,?,?,?)",
                            key, request.name(), reference, prepared.context(), mapper.writeValueAsString(request.namespaces()), request.allowContainerQuery(), Instant.now().toString());
                } catch (RuntimeException e) { secrets.delete(reference); throw e; }
            } else {
                jdbc.update("UPDATE kubernetes_config SET enabled=0 WHERE id=?", key);
                secrets.put(reference, prepared.json());
                jdbc.update("UPDATE kubernetes_config SET name=?,context_name=?,namespaces=?,allow_container_query=?,enabled=1,updated_at=? WHERE id=?",
                        request.name(), prepared.context(), mapper.writeValueAsString(request.namespaces()), request.allowContainerQuery(), Instant.now().toString(), key);
            }
            event("KUBERNETES_SAVED", "SUCCESS", Map.of("clusterId", key, "name", request.name(), "namespaces", request.namespaces(), "allowContainerQuery", request.allowContainerQuery()));
            return get(key);
        });
    }
    public void delete(String id) {
        runLocked(id, () -> {
            get(id);
            event("KUBERNETES_DELETE_REQUESTED", "REQUESTED", Map.of("clusterId", id));
            jdbc.update("UPDATE kubernetes_config SET enabled=0 WHERE id=?", id);
            secrets.delete("gateway.kubernetes." + id);
            String permission = "kubernetes:read:" + id;
            jdbc.update("UPDATE api_token SET permissions=(SELECT json_group_array(value) FROM json_each(api_token.permissions) WHERE value<>?) WHERE EXISTS(SELECT 1 FROM json_each(api_token.permissions) WHERE value=?)", permission, permission);
            jdbc.update("DELETE FROM kubernetes_config WHERE id=?", id);
            lastQuery.remove(id);
            event("KUBERNETES_DELETED", "SUCCESS", Map.of("clusterId", id));
            return null;
        });
    }
    public QueryResult resources(ResourceRequest request) {
        return query(request.clusterId(), request.purpose(), "resources", request, () -> {
            String path = policy.resourcePath(get(request.clusterId()).namespaces(), request);
            String result = runner.run(config(request.clusterId()), List.of("get", "--raw", path));
            var tree = mapper.readTree(result);
            var continuation = tree.path("metadata").path("continue");
            String next = continuation.isString() ? continuation.stringValue() : "";
            Object object = mapper.readValue(result, Object.class);
            checkDepth(object, 0);
            String yaml = new Yaml().dump(object);
            if (yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) throw bad("YAML 输出超过 1 MiB，请缩小查询范围");
            return new QueryResult("yaml", yaml, next, 0);
        });
    }
    public QueryResult logs(LogsRequest request) {
        return query(request.clusterId(), request.purpose(), "logs", request, () -> {
            String path = policy.logsPath(get(request.clusterId()).namespaces(), request);
            return new QueryResult("text", runner.run(config(request.clusterId()), List.of("get", "--raw", path)), "", 0);
        });
    }
    public QueryResult container(ContainerRequest request) {
        return query(request.clusterId(), request.purpose(), "container", request, () -> {
            ClusterView cluster = get(request.clusterId());
            if (!cluster.allowContainerQuery()) throw bad("容器内查询未开启；请优先使用 Pod 日志查询");
            policy.namespace(cluster.namespaces(), request.namespace()); policy.name(request.pod()); policy.name(request.container());
            List<String> command = new ArrayList<>(List.of("exec", "--namespace", request.namespace(), "--container", request.container(), request.pod(), "--"));
            command.addAll(policy.containerCommand(request.command()));
            return new QueryResult("text", runner.run(config(request.clusterId()), command), "", 0);
        });
    }
    private void checkDepth(Object value, int depth) {
        if (depth > 40) throw bad("资源嵌套层级过深，请缩小查询范围");
        if (value instanceof Map<?, ?> values) values.values().forEach(v -> checkDepth(v, depth + 1));
        else if (value instanceof List<?> values) values.forEach(v -> checkDepth(v, depth + 1));
    }

    private QueryResult query(String id, String purpose, String action, Object request, Supplier<QueryResult> operation) {
        if (actor.isAi() && !actor.requireToken().permitsKubernetes(id)) throw new GatewayException(HttpStatus.FORBIDDEN, "KUBERNETES_SCOPE_DENIED", "令牌没有该集群的访问权限");
        return runLocked(id, () -> {
            ClusterView cluster = get(id);
            if (!cluster.enabled()) throw bad("集群配置未启用");
            long now = System.nanoTime();
            Long previous = lastQuery.get(id);
            if (previous != null && now - previous < 1_000_000_000L) throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS, "KUBERNETES_RATE_LIMIT", "同一集群每秒最多查询一次，请稍后再试");
            lastQuery.put(id, now);
            event("KUBERNETES_QUERY_REQUESTED", "REQUESTED", Map.of("clusterId", id, "purpose", purpose, "action", action, "request", request, "clusterName", cluster.name()));
            try {
                QueryResult result = operation.get();
                long duration = (System.nanoTime() - now) / 1_000_000;
                event("KUBERNETES_QUERY_FINISHED", "SUCCESS", Map.of("clusterId", id, "action", action, "durationMs", duration));
                return new QueryResult(result.format(), result.content(), result.continueToken(), duration);
            } catch (GatewayException e) {
                if (!e.code().startsWith("AUDIT_")) event("KUBERNETES_QUERY_FAILED", "FAILED", Map.of("clusterId", id, "action", action, "errorCode", e.code()));
                throw e;
            }
        });
    }
    private <T> T runLocked(String id, Supplier<T> operation) {
        if (!busy.add(id)) throw bad("该集群正在执行操作，请稍后再试");
        boolean acquired = capacity.tryAcquire();
        try {
            if (!acquired) throw bad("Kubernetes 查询并发已达上限");
            return operation.get();
        } finally { if (acquired) capacity.release(); busy.remove(id); }
    }
    private void event(String type, String status, Map<String, Object> detail) {
        audit.record(new AuditCommand(actor.actor(), actor.actorType(), type, null, null,
                "Kubernetes " + detail.getOrDefault("name", detail.getOrDefault("clusterName", detail.getOrDefault("clusterId", "")))
                        + (detail.containsKey("purpose") ? "：" + detail.get("purpose") : ""),
                null, detail, status, detail.get("durationMs") instanceof Number duration ? duration.longValue() : null, null, null, null));
    }
    private GatewayException bad(String message) { return new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_KUBERNETES_REQUEST", message); }
}
