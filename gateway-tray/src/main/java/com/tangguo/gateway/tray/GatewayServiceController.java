package com.tangguo.gateway.tray;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Invokes the existing PowerShell service wrapper from the tray process. */
final class GatewayServiceController {
    record CommandResult(int exitCode, String output) {
        boolean succeeded() {
            return exitCode == 0;
        }
    }

    private final Path serviceScript;

    GatewayServiceController(Path serviceScript) {
        this.serviceScript = serviceScript.toAbsolutePath().normalize();
    }

    CommandResult execute(String action) {
        if (!List.of("start", "stop", "restart", "status").contains(action)) {
            return new CommandResult(2, "不支持的网关操作");
        }
        if (!Files.isRegularFile(serviceScript)) {
            return new CommandResult(2, "找不到 windows-service.ps1");
        }

        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "powershell.exe",
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    serviceScript.toString(),
                    action)
                    .directory(serviceScript.getParent().toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
            process = processBuilder.start();
            boolean completed = process.waitFor(90, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new CommandResult(124, "网关操作超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
            return new CommandResult(process.exitValue(), output);
        } catch (IOException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(1, "无法执行 PowerShell 服务脚本");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(130, "网关操作被中断");
        }
    }
}
