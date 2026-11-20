package com.tangguo.gateway.kubernetes;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.FileSecurity;
import com.tangguo.gateway.config.GatewayDataDirectory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class KubectlRunner {
    private static final int MAX_BYTES = 1024 * 1024;
    private final String configuredPath;
    public KubectlRunner(@Value("${gateway.kubernetes.kubectl-path:}") String configuredPath) { this.configuredPath = configuredPath; }

    public String run(String config, List<String> arguments) {
        Path directory = null;
        Process process = null;
        try {
            String binary = binary();
            directory = Files.createTempDirectory("gateway-kube-");
            FileSecurity.setOwnerOnlyDirectory(directory);
            Path file = Files.createFile(directory.resolve("config"));
            FileSecurity.setOwnerOnlyFile(file);
            Files.writeString(file, config, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(binary, "--kubeconfig", file.toString(),
                    "--cache-dir", directory.resolve("cache").toString(), "--request-timeout=15s"));
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            // 不继承本机 kubeconfig、代理或云凭据环境；不调用 Shell，也不开放插件命令。
            if (GatewayDataDirectory.isWindows()) {
                // Windows 进程启动和证书加载依赖少量系统变量，其余环境仍保持清空。
                for (String name : List.of("SystemRoot", "SystemDrive", "windir", "TEMP", "TMP",
                        "PATHEXT", "COMSPEC", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE")) {
                    String value = System.getenv(name);
                    if (value != null && !value.isBlank()) builder.environment().put(name, value);
                }
            }
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            Capture stdout = new Capture(); Capture stderr = new Capture();
            FutureTask<Void> out = new FutureTask<>(() -> { stdout.read(running.getInputStream()); return null; });
            FutureTask<Void> err = new FutureTask<>(() -> { stderr.read(running.getErrorStream()); return null; });
            Thread.startVirtualThread(out); Thread.startVirtualThread(err);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (stdout.overflow || stderr.overflow) throw failure("KUBERNETES_OUTPUT_LIMIT", "输出超过 1 MiB，请减少条数、缩小时间范围或指定单个资源");
                if (System.nanoTime() > deadline) throw failure("KUBERNETES_TIMEOUT", "查询超时，未自动重试；若为容器查询，断开不能保证远程进程已停止");
            }
            out.get(2, TimeUnit.SECONDS); err.get(2, TimeUnit.SECONDS);
            if (stdout.overflow || stderr.overflow) throw failure("KUBERNETES_OUTPUT_LIMIT", "输出超过 1 MiB，请缩小查询范围");
            if (process.exitValue() != 0) {
                String detail = stderr.text();
                if (detail.contains("Forbidden") || detail.contains("forbidden")) throw failure("KUBERNETES_FORBIDDEN", "集群拒绝访问，请检查 kubeconfig 的 RBAC 权限");
                if (detail.contains("NotFound") || detail.contains("not found")) throw failure("KUBERNETES_NOT_FOUND", "资源、容器或容器内查询工具不存在");
                throw failure("KUBERNETES_QUERY_FAILED", "查询失败，请检查网络、证书、凭据、资源名称及集群权限");
            }
            return stdout.text();
        } catch (GatewayException exception) { throw exception; }
        catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure("KUBERNETES_CLIENT_ERROR", "Kubernetes 客户端执行失败，未自动重试");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(p -> p.destroyForcibly());
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) {
                // Windows 上 Defender 或延迟关闭可能短暂占用临时文件，清理失败不应掩盖查询结果。
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Exception ignored) {
                            file.toFile().deleteOnExit();
                        }
                    }
                } catch (Exception ignored) {
                    directory.toFile().deleteOnExit();
                }
            }
        }
    }

    private String binary() {
        if (!configuredPath.isBlank()) {
            Path path = Path.of(configuredPath);
            if (path.isAbsolute() && Files.isExecutable(path)) return path.toString();
            throw failure("KUBECTL_UNAVAILABLE", "配置的 kubectl 路径不可用");
        }
        List<String> candidates = new ArrayList<>(List.of("/Applications/Docker.app/Contents/Resources/bin/kubectl", "/opt/homebrew/bin/kubectl", "/usr/local/bin/kubectl", "/usr/bin/kubectl"));
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        // Windows 环境变量通常保存为 "Path"，而 Java 的 System.getenv() 映射区分大小写。
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null) pathVariable = "";
        for (String directory : pathVariable.split(java.io.File.pathSeparator)) {
            if (!directory.isBlank()) candidates.add(Path.of(directory, windows ? "kubectl.exe" : "kubectl").toString());
        }
        return candidates.stream().map(Path::of).filter(p -> p.isAbsolute() && Files.isRegularFile(p) && Files.isExecutable(p))
                .map(Path::toString).findFirst().orElseThrow(() -> failure("KUBECTL_UNAVAILABLE", "未找到 kubectl，请配置 gateway.kubernetes.kubectl-path 为已有客户端的绝对路径"));
    }
    private GatewayException failure(String code, String message) { return new GatewayException(HttpStatus.BAD_GATEWAY, code, message); }
    private static final class Capture {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean overflow;
        void read(InputStream input) throws Exception {
            try (input) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    int accepted = Math.min(count, MAX_BYTES - bytes.size());
                    bytes.write(buffer, 0, accepted);
                    if (accepted < count) overflow = true;
                }
            }
        }
        String text() { return bytes.toString(StandardCharsets.UTF_8); }
    }
}
