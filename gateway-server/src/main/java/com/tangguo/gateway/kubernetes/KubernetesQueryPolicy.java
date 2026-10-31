package com.tangguo.gateway.kubernetes;

import com.tangguo.gateway.api.GatewayException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.tangguo.gateway.kubernetes.KubernetesDtos.ResourceRequest;
import com.tangguo.gateway.kubernetes.KubernetesDtos.LogsRequest;

@Component
public class KubernetesQueryPolicy {
    private static final Map<String, String> RESOURCES = Map.ofEntries(
            Map.entry("pods", "/api/v1"), Map.entry("services", "/api/v1"), Map.entry("endpoints", "/api/v1"),
            Map.entry("configmaps", "/api/v1"), Map.entry("events", "/api/v1"), Map.entry("persistentvolumeclaims", "/api/v1"),
            Map.entry("namespaces", "/api/v1"), Map.entry("nodes", "/api/v1"), Map.entry("persistentvolumes", "/api/v1"),
            Map.entry("serviceaccounts", "/api/v1"), Map.entry("resourcequotas", "/api/v1"), Map.entry("limitranges", "/api/v1"),
            Map.entry("deployments", "/apis/apps/v1"), Map.entry("statefulsets", "/apis/apps/v1"),
            Map.entry("daemonsets", "/apis/apps/v1"), Map.entry("replicasets", "/apis/apps/v1"),
            Map.entry("jobs", "/apis/batch/v1"), Map.entry("cronjobs", "/apis/batch/v1"),
            Map.entry("ingresses", "/apis/networking.k8s.io/v1"), Map.entry("networkpolicies", "/apis/networking.k8s.io/v1"),
            Map.entry("horizontalpodautoscalers", "/apis/autoscaling/v2"), Map.entry("endpointslices", "/apis/discovery.k8s.io/v1"));
    private static final Set<String> GLOBAL = Set.of("namespaces", "nodes", "persistentvolumes");
    private static final Map<String, Set<String>> FLAGS = Map.of(
            "ls", Set.of("-a", "-l", "-h", "-la", "-al", "-lah", "-alh", "-d"),
            "cat", Set.of("-n"), "head", Set.of(), "tail", Set.of(),
            "ps", Set.of("-ef", "aux", "-e", "-f"), "df", Set.of("-h", "-i"),
            "id", Set.of("-u", "-g"), "uname", Set.of("-a", "-r", "-m"), "pwd", Set.of());

    public List<String> resources() { return RESOURCES.keySet().stream().sorted().toList(); }
    public void name(String value) {
        if (value == null || value.length() > 253 || !value.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) deny("资源名称格式无效");
    }
    public void namespace(List<String> allowed, String namespace) {
        name(namespace);
        if (namespace.length() > 63 || namespace.contains(".")) deny("命名空间格式无效");
        if (!allowed.contains("*") && !allowed.contains(namespace)) deny("未授权该命名空间");
    }
    public String resourcePath(List<String> allowed, ResourceRequest request) {
        String root = RESOURCES.get(request.resource());
        if (root == null) deny("仅支持已列出的资源类型，不支持 Secret、代理、任意 API 路径或自定义资源");
        if (GLOBAL.contains(request.resource())) {
            if (!allowed.contains("*")) deny("查询集群级资源需要管理员配置全部命名空间范围");
        } else {
            namespace(allowed, request.namespace());
            root += "/namespaces/" + request.namespace();
        }
        String path = root + "/" + request.resource();
        if (request.name() != null && !request.name().isBlank()) { name(request.name()); return path + "/" + request.name(); }
        path += "?limit=" + (request.limit() == null ? 50 : request.limit());
        if (request.labelSelector() != null) path += "&labelSelector=" + encode(request.labelSelector());
        if (request.continueToken() != null) path += "&continue=" + encode(request.continueToken());
        return path;
    }
    public String logsPath(List<String> allowed, LogsRequest request) {
        namespace(allowed, request.namespace()); name(request.pod()); name(request.container());
        return "/api/v1/namespaces/" + request.namespace() + "/pods/" + request.pod() + "/log?container=" + encode(request.container())
                + "&tailLines=" + (request.tailLines() == null ? 500 : request.tailLines())
                + "&sinceSeconds=" + (request.sinceSeconds() == null ? 3600 : request.sinceSeconds())
                + "&limitBytes=262144&timestamps=true&previous=" + Boolean.TRUE.equals(request.previous());
    }
    /** 容器命令作为 argv 直接执行，不经 Shell，不支持脚本、管道、重定向或任意选项。 */
    public List<String> containerCommand(List<String> command) {
        if (command == null || command.isEmpty() || command.size() > 30) deny("请填写受限查询命令及参数数组");
        String name = command.getFirst();
        if (!FLAGS.containsKey(name)) deny("容器内仅允许 ls、cat、head、tail、ps、df、id、uname、pwd");
        boolean fileCommand = Set.of("ls", "cat", "head", "tail").contains(name);
        List<String> result = new ArrayList<>();
        result.add(Set.of("head", "tail", "id").contains(name) ? "/usr/bin/" + name : "/bin/" + name);
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg == null || arg.isBlank() || arg.length() > 1024 || arg.chars().anyMatch(Character::isISOControl)) deny("容器参数无效");
            if ((name.equals("head") || name.equals("tail")) && arg.equals("-n")) {
                if (++i >= command.size() || !command.get(i).matches("[1-9][0-9]{0,3}") || Integer.parseInt(command.get(i)) > 2000) deny("行数必须在 1 到 2000 之间");
                result.add("-n"); result.add(command.get(i));
            } else if (FLAGS.get(name).contains(arg)) result.add(arg);
            else if (fileCommand && arg.startsWith("/") && !arg.contains("..") && !arg.startsWith("/dev")
                    && !arg.startsWith("/proc") && !arg.startsWith("/sys") && !arg.matches(".*[;|&<>`$\\\\].*")) result.add(arg);
            else deny("不支持该容器命令参数；文件路径须为绝对路径，不允许 /dev、/proc、/sys");
        }
        if (Set.of("cat", "head", "tail").contains(name) && command.stream().skip(1).noneMatch(a -> a.startsWith("/"))) deny("请指定待查看的文件");
        return result;
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private void deny(String message) { throw new GatewayException(HttpStatus.FORBIDDEN, "KUBERNETES_READ_ONLY_POLICY", message); }
}
