package com.tangguo.gateway.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsDpapiSecretStoreTest {
    @TempDir
    Path directory;

    @Test
    void protectsReadsAndDeletesSecretsWithCurrentWindowsUserDpapi() throws Exception {
        Assumptions.assumeTrue(WindowsDpapiSecretStore.isSupportedPlatform());
        WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(directory.resolve("secrets"));
        String account = "datasource:windows-test";
        String secret = "database-password-不会出现在明文文件中";

        store.put(account, secret);

        Path protectedFile;
        try (Stream<Path> files = Files.list(directory.resolve("secrets"))) {
            protectedFile = files.findFirst().orElseThrow();
        }
        byte[] ciphertext = Files.readAllBytes(protectedFile);
        assertThat(ciphertext).isNotEmpty();
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(store.get(account)).contains(secret);

        store.delete(account);
        assertThat(store.get(account)).isEmpty();
        try (Stream<Path> files = Files.list(directory.resolve("secrets"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void rejectsTamperedProtectedFiles() throws Exception {
        Assumptions.assumeTrue(WindowsDpapiSecretStore.isSupportedPlatform());
        WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(directory.resolve("secrets"));
        String account = "tampered-account";
        store.put(account, "secret-value");
        Path protectedFile;
        try (Stream<Path> files = Files.list(directory.resolve("secrets"))) {
            protectedFile = files.findFirst().orElseThrow();
        }
        Files.writeString(protectedFile, "not-dpapi-ciphertext");

        assertThatThrownBy(() -> store.get(account))
                .isInstanceOfSatisfying(
                        com.tangguo.gateway.api.GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SECRET_STORE_UNAVAILABLE"));
    }
}
