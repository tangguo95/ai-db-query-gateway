package com.tangguo.gateway.kubernetes;

import com.tangguo.gateway.api.GatewayException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

/** 仅输出选定 context 的内嵌认证材料，禁止 kubeconfig 启动本机插件或读取任意文件。 */
@Component
public class KubeconfigPolicy {
    private final ObjectMapper mapper;
    public KubeconfigPolicy(ObjectMapper mapper) { this.mapper = mapper; }
    public record Prepared(String context, String json) {}

    public Prepared prepare(String text, String selected) {
        try {
            if (text == null || text.isBlank() || text.length() > 32768) throw invalid();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(30);
            options.setCodePointLimit(32768);
            Map<String, Object> root = map(new Yaml(new SafeConstructor(options)).load(text));
            String context = selected == null || selected.isBlank() ? string(root.get("current-context")) : selected.trim();
            Map<String, Object> ctx = entry(root, "contexts", context, "context");
            only(ctx, Set.of("cluster", "user", "namespace", "extensions"));
            String cluster = string(ctx.get("cluster"));
            String user = string(ctx.get("user"));
            Map<String, Object> clusterConfig = entry(root, "clusters", cluster, "cluster");
            only(clusterConfig, Set.of("server", "certificate-authority-data", "insecure-skip-tls-verify", "tls-server-name", "disable-compression"));
            URI server = URI.create(string(clusterConfig.get("server")));
            if (!"https".equals(server.getScheme()) || server.getHost() == null || server.getUserInfo() != null
                    || server.getQuery() != null || server.getFragment() != null) throw invalid();
            Map<String, Object> auth = entry(root, "users", user, "user");
            only(auth, Set.of("token", "client-certificate-data", "client-key-data"));
            if (!auth.containsKey("token") && !(auth.containsKey("client-certificate-data") && auth.containsKey("client-key-data"))) throw invalid();
            for (Object value : auth.values()) string(value);
            String json = mapper.writeValueAsString(Map.of("apiVersion", "v1", "kind", "Config", "current-context", context,
                    "clusters", List.of(Map.of("name", cluster, "cluster", clusterConfig)),
                    "users", List.of(Map.of("name", user, "user", auth)),
                    "contexts", List.of(Map.of("name", context, "context", Map.of("cluster", cluster, "user", user)))));
            if (json.getBytes(StandardCharsets.UTF_8).length > 24000) throw invalid();
            return new Prepared(context, json);
        } catch (Exception exception) {
            // YAML 异常可能包含原文及凭据，不附带原始异常或文本。
            throw invalid();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> result) || result.keySet().stream().anyMatch(k -> !(k instanceof String))) throw invalid();
        return (Map<String, Object>) result;
    }
    private Map<String, Object> entry(Map<String, Object> root, String key, String name, String field) {
        if (!(root.get(key) instanceof List<?> entries)) throw invalid();
        var matches = entries.stream().map(this::map).filter(e -> name.equals(e.get("name"))).toList();
        if (matches.size() != 1) throw invalid();
        return map(matches.getFirst().get(field));
    }
    private String string(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0) throw invalid();
        return text;
    }
    private void only(Map<String, Object> value, Set<String> keys) {
        if (!keys.containsAll(value.keySet())) throw invalid();
    }
    private GatewayException invalid() {
        return new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_KUBECONFIG",
                "kubeconfig 无效或不受支持：请选择有效 context，使用 HTTPS、内嵌证书及 Token/客户端证书认证；不支持 exec/auth-provider 插件、本机文件引用或代理配置，配置须小于 24 KiB（规范化后）。");
    }
}
