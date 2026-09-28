package com.tangguo.gateway.server;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.server.ServerDtos.Connection;
import com.tangguo.gateway.server.ServerDtos.Endpoint;
import com.tangguo.gateway.server.ServerDtos.ExecuteResult;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SshExecutor {
    private static final int MAX_OUTPUT = 256 * 1024;

    public ExecuteResult execute(Connection connection, String command, int timeoutSeconds, boolean testing) {
        long start = System.nanoTime();
        // 包含两跳握手在内的总预算小于 MCP HTTP 超时，避免客户端超时后误重试。
        long deadline = start + 38_000_000_000L;
        Session jump = null;
        Session target = null;
        ChannelExec channel = null;
        OutputBudget output = new OutputBudget();
        boolean submitted = false;
        try {
            int port = connection.target().port();
            String host = connection.target().host();
            if (connection.jump() != null) {
                jump = connect(connection.jump(), connection.jump().host(), connection.jump().port(), deadline);
                port = jump.setPortForwardingL("127.0.0.1", 0, host, port);
                host = "127.0.0.1";
            }
            target = connect(connection.target(), host, port, deadline);
            channel = (ChannelExec) target.openChannel("exec");
            channel.setPty(false);
            channel.setAgentForwarding(false);
            channel.setInputStream(null);
            channel.setCommand(command);
            channel.setOutputStream(output.stream(false));
            channel.setErrStream(output.stream(true));
            submitted = true;
            channel.connect(remaining(deadline));
            long commandDeadline = Math.min(deadline, System.nanoTime() + timeoutSeconds * 1_000_000_000L);
            while (!channel.isClosed() && !output.truncated && System.nanoTime() < commandDeadline) {
                Thread.sleep(20);
            }
            String status;
            String message;
            Integer exitCode = null;
            if (output.truncated) {
                status = "OUTPUT_LIMIT";
                message = "输出超过 256 KiB，已断开连接；远程进程是否停止未知，不会自动重试。";
            } else if (!channel.isClosed()) {
                status = "TIMED_OUT";
                message = "等待命令超时，已断开连接；远程进程可能仍在运行，不会自动重试。";
            } else if (channel.getExitStatus() < 0) {
                status = "UNKNOWN";
                message = "连接结束但没有退出码，远程执行结果未知，不会自动重试。";
            } else {
                exitCode = channel.getExitStatus();
                status = exitCode == 0 ? "EXECUTED" : "FAILED";
                message = "命令已结束。";
            }
            return new ExecuteResult(null, status, exitCode, testing ? output.text(false) : redact(output.text(false), connection),
                    redact(output.text(true), connection), output.truncated, elapsed(start), message);
        } catch (GatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (submitted) {
                return new ExecuteResult(null, "UNKNOWN", null, redact(output.text(false), connection),
                        redact(output.text(true), connection), output.truncated, elapsed(start),
                        "命令提交后连接中断，执行结果未知，不会自动重试。请先核对远程状态。");
            }
            // 不向 API、日志或 MCP 透传 SSH 异常，其中可能带连接信息。
            throw new GatewayException(HttpStatus.BAD_GATEWAY, "SSH_CONNECTION_FAILED",
                    "SSH 连接失败，请检查网络、账号、密码或私钥，以及跳板机转发权限。");
        } finally {
            if (channel != null) channel.disconnect();
            if (target != null) target.disconnect();
            if (jump != null) jump.disconnect();
        }
    }

    private Session connect(Endpoint endpoint, String host, int port, long deadline)
            throws Exception {
        JSch client = new JSch();
        if ("PRIVATE_KEY".equals(endpoint.authType())) {
            client.addIdentity("gateway", endpoint.privateKey().getBytes(StandardCharsets.UTF_8), null,
                    endpoint.passphrase() == null ? null : endpoint.passphrase().getBytes(StandardCharsets.UTF_8));
        }
        Session session = client.getSession(endpoint.username(), host, port);
        // 按服务器管理的连接方式直接认证，不要求管理员配置或确认主机指纹。
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "PRIVATE_KEY".equals(endpoint.authType()) ? "publickey" : "password");
        if ("PASSWORD".equals(endpoint.authType())) session.setPassword(endpoint.password());
        session.setTimeout(remaining(deadline));
        session.setServerAliveInterval(5000);
        session.setServerAliveCountMax(1);
        try {
            session.connect(remaining(deadline));
            return session;
        } catch (Exception exception) {
            session.disconnect();
            throw exception;
        }
    }

    private static int remaining(long deadline) {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis <= 0) throw new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "SSH_CONNECT_TIMEOUT", "SSH 连接超时");
        return (int) Math.min(8000, millis);
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static String redact(String text, Connection connection) {
        for (Endpoint endpoint : new Endpoint[]{connection.target(), connection.jump()}) {
            if (endpoint == null) continue;
            for (String secret : new String[]{endpoint.password(), endpoint.privateKey(), endpoint.passphrase()}) {
                if (secret != null && !secret.isEmpty()) text = text.replace(secret, "[凭据已隐藏]");
            }
        }
        return text;
    }

    private static final class OutputBudget {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private OutputStream stream(boolean error) {
            return new OutputStream() {
                @Override public void write(int value) { write(new byte[]{(byte) value}, 0, 1); }
                @Override public void write(byte[] bytes, int offset, int length) {
                    synchronized (OutputBudget.this) {
                        int accepted = Math.min(length, MAX_OUTPUT - stdout.size() - stderr.size());
                        (error ? stderr : stdout).write(bytes, offset, accepted);
                        if (accepted < length) truncated = true;
                    }
                }
            };
        }
        private synchronized String text(boolean error) { return (error ? stderr : stdout).toString(StandardCharsets.UTF_8); }
    }
}
