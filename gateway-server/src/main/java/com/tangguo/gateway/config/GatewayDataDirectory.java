package com.tangguo.gateway.config;

import java.nio.file.Path;

/** Resolves the platform-native runtime directory without persisting secrets in the repository. */
public final class GatewayDataDirectory {
    private static final String APPLICATION_DIRECTORY_NAME = "AI DB Query Gateway";

    private GatewayDataDirectory() {}

    public static Path resolve() {
        return resolve(null);
    }

    public static Path resolve(String explicitlyConfigured) {
        String configured = firstNonBlank(
                explicitlyConfigured,
                System.getProperty("gateway.data-dir"),
                System.getenv("GATEWAY_DATA_DIR"));
        if (configured != null) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (osName.contains("win")) {
            String localAppData = firstNonBlank(System.getenv("LOCALAPPDATA"));
            Path base = localAppData == null
                    ? home.resolve("AppData").resolve("Local")
                    : Path.of(localAppData);
            return base.resolve(APPLICATION_DIRECTORY_NAME).toAbsolutePath().normalize();
        }
        if (osName.contains("mac")) {
            return home.resolve("Library")
                    .resolve("Application Support")
                    .resolve(APPLICATION_DIRECTORY_NAME)
                    .toAbsolutePath()
                    .normalize();
        }

        String xdgDataHome = firstNonBlank(System.getenv("XDG_DATA_HOME"));
        Path base = xdgDataHome == null ? home.resolve(".local").resolve("share") : Path.of(xdgDataHome);
        return base.resolve(APPLICATION_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
