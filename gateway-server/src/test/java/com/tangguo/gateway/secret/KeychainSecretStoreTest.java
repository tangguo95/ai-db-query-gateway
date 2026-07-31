package com.tangguo.gateway.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tangguo.gateway.api.GatewayException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class KeychainSecretStoreTest {
    @TempDir
    Path directory;

    @Test
    void acceptsOneValidJsonResponseAndDetectsRuntimeReplacement() throws Exception {
        Path helper = helper(
                """
                #!/bin/sh
                read request
                printf '%s\\n' '{"ok":true,"value":"test-value"}'
                """);
        KeychainSecretStore store = new KeychainSecretStore(helper, new ObjectMapper());

        assertThat(store.get("test-account")).contains("test-value");

        Files.writeString(
                helper,
                """
                #!/bin/sh
                read request
                printf '%s\\n' '{"ok":true,"value":"changed"}'
                """);
        assertThatThrownBy(() -> store.get("test-account"))
                .isInstanceOfSatisfying(
                        GatewayException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SECRET_STORE_UNAVAILABLE"));
    }

    @Test
    void rejectsMalformedOrNonZeroHelperResponses() throws Exception {
        KeychainSecretStore malformed =
                new KeychainSecretStore(helper("#!/bin/sh\nread request\nprintf '%s\\n' '{}'\n"), new ObjectMapper());
        assertThatThrownBy(() -> malformed.get("test-account"))
                .isInstanceOf(GatewayException.class);

        KeychainSecretStore nonZero = new KeychainSecretStore(
                helper("#!/bin/sh\nread request\nprintf '%s\\n' '{\"ok\":true}'\nexit 3\n"),
                new ObjectMapper());
        assertThatThrownBy(() -> nonZero.put("test-account", "test-value"))
                .isInstanceOf(GatewayException.class);
    }

    private Path helper(String content) throws Exception {
        // macOS exposes /var as a symlink to /private/var. The production store
        // intentionally rejects every symlink component, so build the fixture
        // from the canonical temporary directory instead of weakening that check.
        Path realDirectory = directory.toRealPath();
        Path path = Files.createTempFile(realDirectory, "keychain-helper-", ".sh");
        Files.writeString(path, content);
        Files.setPosixFilePermissions(
                path,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        Files.setPosixFilePermissions(
                realDirectory,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        return path;
    }
}
