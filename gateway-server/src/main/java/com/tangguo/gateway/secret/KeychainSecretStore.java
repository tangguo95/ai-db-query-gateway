package com.tangguo.gateway.secret;

import com.tangguo.gateway.api.GatewayException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class KeychainSecretStore implements SecretStore {
    private static final Duration HELPER_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_HELPER_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_HELPER_REQUEST_BYTES = 64 * 1024;
    private static final long MAX_HELPER_BINARY_BYTES = 8L * 1024 * 1024;

    private final Path helperPath;
    private final ObjectMapper objectMapper;
    private final byte[] expectedHelperDigest;

    public KeychainSecretStore(Path helperPath, ObjectMapper objectMapper) {
        this.helperPath = helperPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        validateHelperFile();
        this.expectedHelperDigest = helperDigest();
    }

    @Override
    public void put(String account, String value) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", "put");
        request.put("account", account);
        request.put("value", value);
        invoke(request, false);
    }

    @Override
    public Optional<String> get(String account) {
        Map<String, Object> request = Map.of("action", "get", "account", account);
        JsonNode response = invoke(request, true);
        JsonNode value = response.get("value");
        return value == null || !value.isString() || value.stringValue().isEmpty()
                ? Optional.empty()
                : Optional.of(value.stringValue());
    }

    @Override
    public void delete(String account) {
        invoke(Map.of("action", "delete", "account", account), false);
    }

    /**
     * 密钥只通过子进程 stdin 传递，避免出现在命令行参数、环境变量和系统进程列表中。
     */
    private JsonNode invoke(Map<String, Object> request, boolean missingAllowed) {
        validateHelperFile();
        if (!MessageDigest.isEqual(expectedHelperDigest, helperDigest())) {
            throw unavailable("macOS Keychain helper 在服务运行期间发生变化");
        }
        Process process = null;
        byte[] requestBytes = null;
        try {
            requestBytes = objectMapper.writeValueAsBytes(request);
            if (requestBytes.length > MAX_HELPER_REQUEST_BYTES) {
                throw unavailable("写入 macOS Keychain 的秘密超过安全上限");
            }
            ProcessBuilder processBuilder =
                    new ProcessBuilder(helperPath.toString()).redirectErrorStream(false);
            // helper 不需要任何环境变量。清空环境可避免 DYLD/JAVA/代理类变量影响受信子进程。
            processBuilder.environment().clear();
            process = processBuilder.start();
            Process startedProcess = process;
            FutureTask<byte[]> stdoutTask =
                    new FutureTask<>(() -> readAndDrain(startedProcess.getInputStream(), MAX_HELPER_OUTPUT_BYTES));
            FutureTask<byte[]> stderrTask =
                    new FutureTask<>(() -> readAndDrain(startedProcess.getErrorStream(), 0));
            Thread.ofVirtual().name("keychain-helper-stdout").start(stdoutTask);
            Thread.ofVirtual().name("keychain-helper-stderr").start(stderrTask);
            try (var output = process.getOutputStream()) {
                output.write(requestBytes);
            }
            if (!process.waitFor(HELPER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw unavailable("macOS Keychain helper 响应超时");
            }
            byte[] stdout =
                    stdoutTask.get(1, TimeUnit.SECONDS);
            // 始终排空但绝不保留或回显 stderr，防止底层异常带出敏感信息。
            stderrTask.get(1, TimeUnit.SECONDS);
            if (stdout.length > MAX_HELPER_OUTPUT_BYTES) {
                throw unavailable("macOS Keychain helper 返回内容超过安全上限");
            }
            if (process.exitValue() != 0) {
                throw unavailable("macOS Keychain helper 异常退出");
            }
            JsonNode response = objectMapper.readTree(new String(stdout, StandardCharsets.UTF_8));
            if (response == null || !response.isObject() || !response.has("ok")) {
                throw unavailable("macOS Keychain helper 返回格式无效");
            }
            if (response.path("ok").asBoolean(false)) {
                return response;
            }
            JsonNode errorNode = response.get("error");
            String error = errorNode == null || !errorNode.isString()
                    ? "KEYCHAIN_OPERATION_FAILED"
                    : errorNode.stringValue();
            if (missingAllowed && ("NOT_FOUND".equalsIgnoreCase(error) || "ITEM_NOT_FOUND".equalsIgnoreCase(error))) {
                return objectMapper.createObjectNode();
            }
            throw unavailable("macOS Keychain 操作失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("macOS Keychain 操作被中断");
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable("macOS Keychain helper 输出读取失败");
        } catch (IOException exception) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SECRET_STORE_UNAVAILABLE",
                    "macOS Keychain 暂不可用",
                    exception);
        } finally {
            if (requestBytes != null) {
                Arrays.fill(requestBytes, (byte) 0);
            }
            if (process != null && process.isAlive()) {
                terminate(process);
            }
        }
    }

    private GatewayException unavailable(String message) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_STORE_UNAVAILABLE", message);
    }

    private void validateHelperFile() {
        try {
            Path current = helperPath.getRoot();
            for (Path component : helperPath) {
                current = current == null ? component : current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw unavailable("macOS Keychain helper 路径不得包含符号链接");
                }
            }
            if (Files.isSymbolicLink(helperPath)
                    || !Files.isRegularFile(helperPath, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(helperPath)) {
                throw unavailable("macOS Keychain helper 不存在、不可执行或属于符号链接");
            }
            PosixFileAttributes attributes =
                    Files.readAttributes(helperPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String owner = attributes.owner().getName();
            String currentUser = System.getProperty("user.name", "");
            if (!owner.equals(currentUser) && !"root".equals(owner)) {
                throw unavailable("macOS Keychain helper 所有者不可信");
            }
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw unavailable("macOS Keychain helper 不得允许组或其他用户写入");
            }
            Path parent = helperPath.getParent();
            if (parent != null) {
                PosixFileAttributes parentAttributes =
                        Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String parentOwner = parentAttributes.owner().getName();
                if (!parentOwner.equals(currentUser) && !"root".equals(parentOwner)) {
                    throw unavailable("macOS Keychain helper 所在目录所有者不可信");
                }
                Set<PosixFilePermission> parentPermissions = parentAttributes.permissions();
                if (parentPermissions.contains(PosixFilePermission.GROUP_WRITE)
                        || parentPermissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw unavailable("macOS Keychain helper 所在目录不得允许组或其他用户写入");
                }
            }
            long size = Files.size(helperPath);
            if (size <= 0 || size > MAX_HELPER_BINARY_BYTES) {
                throw unavailable("macOS Keychain helper 文件大小异常");
            }
        } catch (IOException exception) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SECRET_STORE_UNAVAILABLE",
                    "无法验证 macOS Keychain helper",
                    exception);
        }
    }

    private byte[] helperDigest() {
        try (InputStream input = Files.newInputStream(helperPath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_HELPER_BINARY_BYTES) {
                    throw unavailable("macOS Keychain helper 文件超过安全上限");
                }
                digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SECRET_STORE_UNAVAILABLE",
                    "无法校验 macOS Keychain helper 完整性",
                    exception);
        }
    }

    private static void terminate(Process process) {
        process.toHandle().descendants().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] readAndDrain(InputStream input, int retainedLimit) throws IOException {
        try (input; ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.max(0, retainedLimit))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (retainedLimit >= 0 && total <= retainedLimit) {
                    int copy = Math.min(count, retainedLimit + 1 - total);
                    if (copy > 0) {
                        retained.write(buffer, 0, copy);
                    }
                }
                total += count;
            }
            return retained.toByteArray();
        }
    }
}
