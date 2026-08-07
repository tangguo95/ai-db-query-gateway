package com.tangguo.gateway.secret;

import com.tangguo.gateway.config.GatewayDataDirectory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Small stdin/stdout bridge used by the Windows PowerShell MCP scripts. */
public final class SecretStoreCli {
    private static final String COMMAND_PREFIX = "--gateway.secret-store-cli=";
    private static final String ACCOUNT_PREFIX = "--gateway.secret-store-account=";
    private static final String DATA_DIRECTORY_PREFIX = "--gateway.data-dir=";

    private SecretStoreCli() {}

    public static boolean isCommand(String[] args) {
        return option(args, COMMAND_PREFIX) != null;
    }

    public static int run(String[] args) {
        String action = option(args, COMMAND_PREFIX);
        String account = option(args, ACCOUNT_PREFIX);
        if (action == null || account == null || account.isBlank()) {
            System.err.println("Secret store CLI arguments are incomplete.");
            return 2;
        }
        if (!WindowsDpapiSecretStore.isSupportedPlatform()) {
            System.err.println("The Windows DPAPI secret store is only available on Windows.");
            return 3;
        }

        try {
            Path dataDirectory = GatewayDataDirectory.resolve(option(args, DATA_DIRECTORY_PREFIX));
            WindowsDpapiSecretStore store = new WindowsDpapiSecretStore(dataDirectory.resolve("secrets"));
            switch (action) {
                case "put" -> {
                    String value = readSecret();
                    if (value == null) {
                        System.err.println("Secret input is missing.");
                        return 2;
                    }
                    store.put(account, value);
                }
                case "get" -> {
                    var value = store.get(account);
                    if (value.isEmpty()) {
                        return 4;
                    }
                    System.out.print(value.get());
                }
                case "delete" -> store.delete(account);
                default -> {
                    System.err.println("Unsupported secret store action.");
                    return 2;
                }
            }
            return 0;
        } catch (IOException | RuntimeException exception) {
            // Never print exception details: a provider or native error may contain sensitive context.
            System.err.println("Secret store operation failed.");
            return 1;
        }
    }

    private static String readSecret() throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }

    private static String option(String[] args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
