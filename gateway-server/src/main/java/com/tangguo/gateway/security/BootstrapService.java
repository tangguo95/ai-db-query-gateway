package com.tangguo.gateway.security;

import com.tangguo.gateway.config.GatewayProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class BootstrapService {
    private static final String ADMIN_HASH = "admin_password_hash";
    private static final String BOOTSTRAP_HASH = "bootstrap_token_hash";
    private static final String BOOTSTRAP_FILE_NAME = "bootstrap-token";

    private final SettingService settings;
    private final PasswordEncoder passwordEncoder;
    private final Path bootstrapTokenFile;
    private volatile String currentBootstrapToken;

    public BootstrapService(
            SettingService settings, PasswordEncoder passwordEncoder, GatewayProperties properties) {
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapTokenFile =
                Path.of(properties.getDataDir()).toAbsolutePath().normalize().resolve(BOOTSTRAP_FILE_NAME);
    }

    @PostConstruct
    void prepareBootstrapToken() {
        if (isInitialized()) {
            deleteBootstrapTokenFile();
            return;
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        currentBootstrapToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        settings.put(BOOTSTRAP_HASH, sha256(currentBootstrapToken));
        writeBootstrapTokenFile(currentBootstrapToken);
    }

    public boolean isInitialized() {
        return settings.get(ADMIN_HASH).isPresent();
    }

    public synchronized void setup(String bootstrapToken, String password) {
        if (isInitialized()) {
            throw new com.tangguo.gateway.api.GatewayException(
                    org.springframework.http.HttpStatus.CONFLICT, "ALREADY_INITIALIZED", "管理员已经初始化");
        }
        String expected = settings.get(BOOTSTRAP_HASH).orElse("");
        if (bootstrapToken == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        sha256(bootstrapToken).getBytes(StandardCharsets.US_ASCII))) {
            throw new com.tangguo.gateway.api.GatewayException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_BOOTSTRAP_TOKEN", "首次初始化令牌无效");
        }
        settings.put(ADMIN_HASH, passwordEncoder.encode(password));
        settings.delete(BOOTSTRAP_HASH);
        currentBootstrapToken = null;
        deleteBootstrapTokenFile();
    }

    public boolean verifyPassword(String password) {
        return settings.get(ADMIN_HASH).map(hash -> passwordEncoder.matches(password, hash)).orElse(false);
    }

    public synchronized void changePassword(String currentPassword, String newPassword) {
        if (!isInitialized()) {
            throw new com.tangguo.gateway.api.GatewayException(
                    org.springframework.http.HttpStatus.CONFLICT, "NOT_INITIALIZED", "管理员尚未初始化");
        }
        if (!verifyPassword(currentPassword)) {
            throw new com.tangguo.gateway.api.GatewayException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_CURRENT_PASSWORD", "当前密码错误");
        }
        if (passwordEncoder.matches(newPassword, settings.get(ADMIN_HASH).orElse(""))) {
            throw new com.tangguo.gateway.api.GatewayException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "PASSWORD_REUSE", "新密码不能与当前密码相同");
        }
        settings.put(ADMIN_HASH, passwordEncoder.encode(newPassword));
    }

    String currentBootstrapTokenForTests() {
        return currentBootstrapToken;
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private void writeBootstrapTokenFile(String token) {
        Path parent = bootstrapTokenFile.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".bootstrap-token-", ".tmp");
            Files.setPosixFilePermissions(
                    temporary,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Files.writeString(
                    temporary,
                    token + System.lineSeparator(),
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        bootstrapTokenFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, bootstrapTokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(
                    bootstrapTokenFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException exception) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 原始异常决定启动失败；临时文件沿用 0600 且不含数据库凭据。
                }
            }
            throw new IllegalStateException("无法安全写入一次性初始化令牌文件", exception);
        }
    }

    private void deleteBootstrapTokenFile() {
        try {
            Files.deleteIfExists(bootstrapTokenFile);
        } catch (IOException exception) {
            throw new IllegalStateException("无法删除已失效的一次性初始化令牌文件", exception);
        }
    }
}
