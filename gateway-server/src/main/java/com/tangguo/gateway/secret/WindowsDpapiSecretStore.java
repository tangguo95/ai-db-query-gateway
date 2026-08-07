package com.tangguo.gateway.secret;

import com.sun.jna.platform.win32.Crypt32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinCrypt;
import com.tangguo.gateway.api.GatewayException;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * Windows equivalent of the macOS Keychain store.
 *
 * <p>Values are protected with Windows DPAPI for the current user and stored as ciphertext in a
 * per-account file. The account itself is hashed so neither credential references nor token
 * account names become file names.
 */
public final class WindowsDpapiSecretStore implements SecretStore {
    private static final int MAX_SECRET_BYTES = 1024 * 1024;
    private static final int MAX_PROTECTED_FILE_BYTES = 2 * 1024 * 1024;
    private static final int DPAPI_FLAGS = WinCrypt.CRYPTPROTECT_UI_FORBIDDEN;
    private static final byte[] OPTIONAL_ENTROPY =
            "AI DB Query Gateway Windows Secret Store v1".getBytes(StandardCharsets.UTF_8);

    private final Path secretsDirectory;

    public WindowsDpapiSecretStore(Path secretsDirectory) {
        if (!isSupportedPlatform()) {
            throw new IllegalStateException("Windows DPAPI secret store is only available on Windows");
        }
        this.secretsDirectory = secretsDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.secretsDirectory);
        } catch (IOException exception) {
            throw unavailable("Windows DPAPI 存储目录不可用", exception);
        }
    }

    public static boolean isSupportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Override
    public synchronized void put(String account, String value) {
        validateAccount(account);
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_SECRET_BYTES) {
            Arrays.fill(plaintext, (byte) 0);
            throw unavailable("Windows DPAPI 秘密超过安全上限", null);
        }
        byte[] protectedValue = null;
        try {
            protectedValue = protect(plaintext);
            writeAtomically(fileFor(account), protectedValue);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (protectedValue != null) {
                Arrays.fill(protectedValue, (byte) 0);
            }
        }
    }

    @Override
    public synchronized Optional<String> get(String account) {
        validateAccount(account);
        Path file = fileFor(account);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("Windows DPAPI 秘密文件无效", null);
        }

        byte[] protectedValue = null;
        byte[] plaintext = null;
        try {
            long size = Files.size(file);
            if (size <= 0 || size > MAX_PROTECTED_FILE_BYTES) {
                throw unavailable("Windows DPAPI 秘密文件大小异常", null);
            }
            protectedValue = Files.readAllBytes(file);
            plaintext = unprotect(protectedValue);
            String value = decodeUtf8(plaintext);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException exception) {
            throw unavailable("Windows DPAPI 秘密读取失败", exception);
        } finally {
            if (protectedValue != null) {
                Arrays.fill(protectedValue, (byte) 0);
            }
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    @Override
    public synchronized void delete(String account) {
        validateAccount(account);
        try {
            Files.deleteIfExists(fileFor(account));
        } catch (IOException exception) {
            throw unavailable("Windows DPAPI 秘密删除失败", exception);
        }
    }

    private byte[] protect(byte[] plaintext) {
        WinCrypt.DATA_BLOB input = new WinCrypt.DATA_BLOB(plaintext);
        WinCrypt.DATA_BLOB entropy = new WinCrypt.DATA_BLOB(OPTIONAL_ENTROPY);
        WinCrypt.DATA_BLOB output = new WinCrypt.DATA_BLOB();
        try {
            boolean success = Crypt32.INSTANCE.CryptProtectData(
                    input,
                    "AI DB Query Gateway",
                    entropy,
                    null,
                    null,
                    DPAPI_FLAGS,
                    output);
            if (!success || output.pbData == null || output.cbData <= 0) {
                throw unavailable("Windows DPAPI 加密失败", null);
            }
            return output.getData();
        } finally {
            clearBlob(input);
            clearBlob(entropy);
            clearAndFreeLocalMemory(output);
        }
    }

    private byte[] unprotect(byte[] protectedValue) {
        WinCrypt.DATA_BLOB input = new WinCrypt.DATA_BLOB(protectedValue);
        WinCrypt.DATA_BLOB entropy = new WinCrypt.DATA_BLOB(OPTIONAL_ENTROPY);
        WinCrypt.DATA_BLOB output = new WinCrypt.DATA_BLOB();
        com.sun.jna.ptr.PointerByReference description = new com.sun.jna.ptr.PointerByReference();
        try {
            boolean success = Crypt32.INSTANCE.CryptUnprotectData(
                    input,
                    description,
                    entropy,
                    null,
                    null,
                    DPAPI_FLAGS,
                    output);
            if (!success || output.pbData == null || output.cbData < 0 || output.cbData > MAX_SECRET_BYTES) {
                throw unavailable("Windows DPAPI 解密失败", null);
            }
            return output.getData();
        } finally {
            clearBlob(input);
            clearBlob(entropy);
            clearAndFreeLocalMemory(output);
            if (description.getValue() != null) {
                Kernel32.INSTANCE.LocalFree(description.getValue());
            }
        }
    }

    private void writeAtomically(Path target, byte[] protectedValue) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(secretsDirectory, ".secret-", ".tmp");
            Files.write(
                    temporary,
                    protectedValue,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException exception) {
            throw unavailable("Windows DPAPI 秘密写入失败", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original failure is the useful error and the temporary file contains ciphertext.
                }
            }
        }
    }

    private Path fileFor(String account) {
        return secretsDirectory.resolve(sha256(account) + ".dpapi");
    }

    private static void validateAccount(String account) {
        if (account == null || account.isBlank() || account.length() > 512) {
            throw new IllegalArgumentException("Secret account is invalid");
        }
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw unavailable("Windows DPAPI 秘密编码无效", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private static void clearBlob(WinCrypt.DATA_BLOB blob) {
        if (blob.pbData != null && blob.cbData > 0) {
            blob.pbData.clear(blob.cbData);
        }
    }

    private static void clearAndFreeLocalMemory(WinCrypt.DATA_BLOB blob) {
        if (blob.pbData != null) {
            if (blob.cbData > 0) {
                blob.pbData.clear(blob.cbData);
            }
            Kernel32.INSTANCE.LocalFree(blob.pbData);
            blob.pbData = null;
            blob.cbData = 0;
        }
    }

    private static GatewayException unavailable(String message, Throwable cause) {
        return cause == null
                ? new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_STORE_UNAVAILABLE", message)
                : new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "SECRET_STORE_UNAVAILABLE", message, cause);
    }
}
