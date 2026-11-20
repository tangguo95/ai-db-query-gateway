package com.tangguo.gateway.tray;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Windows notification-area app for the local gateway. */
public final class GatewayTrayApplication {
    private static final int DEFAULT_PORT = 8765;
    private static final long STATUS_REFRESH_SECONDS = 5;

    private final Path dataDirectory;
    private final Path logDirectory;
    private final int port;
    private final GatewayStatusClient statusClient;
    private final GatewayServiceController serviceController;
    private final InstanceLock instanceLock;
    private final ScheduledExecutorService statusExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> namedThread(runnable, "gateway-tray-status"));
    private final ExecutorService operationExecutor = Executors.newSingleThreadExecutor(
            runnable -> namedThread(runnable, "gateway-tray-operation"));

    private TrayIcon trayIcon;
    private volatile GatewayStatusClient.Snapshot currentSnapshot =
            GatewayStatusClient.Snapshot.unknown("检查中");
    private volatile boolean operationInFlight;
    private volatile boolean menuVisible;
    private long lastPopupTimestamp;

    private GatewayTrayApplication(
            Path dataDirectory, Path serviceScript, int port, InstanceLock instanceLock) {
        this.dataDirectory = dataDirectory;
        this.port = port;
        this.logDirectory = dataDirectory.resolve("windows-service");
        this.statusClient = new GatewayStatusClient(URI.create("http://127.0.0.1:" + port + "/actuator/health"));
        this.serviceController = new GatewayServiceController(serviceScript);
        this.instanceLock = instanceLock;
    }

    public static void main(String[] args) {
        if (!isWindows()) {
            showError("Windows 托盘应用只能在 Windows 上运行。");
            return;
        }
        if (!SystemTray.isSupported()) {
            showError("当前 Windows 会话不支持系统托盘，请确认不是无头会话。");
            return;
        }
        applySystemLookAndFeel();
        EventQueue.invokeLater(GatewayTrayApplication::launch);
    }

    private static void applySystemLookAndFeel() {
        try {
            // 使用 Windows 系统外观，让菜单的字体、配色和边框与本机应用保持一致。
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Font uiFont = new Font(resolveMenuFontFamily(), Font.PLAIN, 12);
            for (String key : List.of("Menu.font", "MenuItem.font", "PopupMenu.font", "Label.font")) {
                UIManager.put(key, uiFont);
            }
        } catch (Exception ignored) {
            // 外观设置失败时退回默认外观，不影响托盘功能。
        }
    }

    private static String resolveMenuFontFamily() {
        Set<String> families = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : List.of("Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Segoe UI")) {
            if (families.contains(family)) {
                return family;
            }
        }
        return Font.DIALOG;
    }

    private static void launch() {
        try {
            Path dataDirectory = resolveDataDirectory();
            Files.createDirectories(dataDirectory);
            InstanceLock instanceLock = InstanceLock.tryAcquire(dataDirectory.resolve("gateway-tray.lock"));
            if (instanceLock == null) {
                showError("AI DB Query Gateway 托盘应用已经在运行。");
                return;
            }

            Path serviceScript = resolveServiceScript();
            if (serviceScript == null) {
                instanceLock.close();
                showError("找不到 scripts\\windows-service.ps1。请从项目目录运行，或设置 GATEWAY_PROJECT_DIR。");
                return;
            }

            GatewayTrayApplication application = new GatewayTrayApplication(
                    dataDirectory, serviceScript, resolvePort(), instanceLock);
            application.install();
        } catch (Exception exception) {
            showError("托盘应用启动失败：" + safeMessage(exception));
        }
    }

    private void install() throws Exception {
        Files.createDirectories(logDirectory);

        trayIcon = new TrayIcon(createTrayImage(), "AI DB Query Gateway");
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                showMenuForTrayEvent(event);
            }
        });
        SystemTray.getSystemTray().add(trayIcon);
        applyStatus(GatewayStatusClient.Snapshot.unknown("检查中"));
        statusExecutor.scheduleWithFixedDelay(
                this::refreshStatus,
                0,
                STATUS_REFRESH_SECONDS,
                TimeUnit.SECONDS);
    }

    private void showMenuForTrayEvent(MouseEvent event) {
        appendLog("event id=" + event.getID() + " button=" + event.getButton() + " popup="
                + event.isPopupTrigger() + " when=" + event.getWhen());
        if (!event.isPopupTrigger() && !SwingUtilities.isRightMouseButton(event)) {
            appendLog("ignored: not a popup trigger");
            return;
        }
        if (menuVisible) {
            appendLog("ignored: menu already open");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPopupTimestamp < 150) {
            appendLog("ignored: throttled");
            return;
        }
        lastPopupTimestamp = now;
        List<NativeTrayMenu.Entry> entries = buildMenuEntries();
        menuVisible = true;
        trayIcon.setToolTip(null);
        Thread menuThread = new Thread(() -> {
            try {
                appendLog("menu opening");
                NativeTrayMenu.Command command = NativeTrayMenu.show(entries);
                appendLog("menu closed command=" + command);
                if (command != NativeTrayMenu.Command.NONE) {
                    EventQueue.invokeLater(() -> handleCommand(command));
                }
            } catch (Throwable error) {
                reportMenuFailure(error);
            } finally {
                menuVisible = false;
                EventQueue.invokeLater(() -> trayIcon.setToolTip(tooltipText()));
            }
        }, "gateway-tray-menu");
        menuThread.setDaemon(true);
        try {
            menuThread.start();
        } catch (Throwable error) {
            menuVisible = false;
            EventQueue.invokeLater(() -> trayIcon.setToolTip(tooltipText()));
            reportMenuFailure(error);
        }
    }

    private String tooltipText() {
        return "AI DB Query Gateway · " + statusTitle(currentSnapshot.state());
    }

    private void appendLog(String message) {
        try {
            Files.createDirectories(logDirectory);
            Path logFile = logDirectory.resolve("tray.log");
            if (Files.exists(logFile) && Files.size(logFile) > 262144) {
                Files.delete(logFile);
            }
            Files.writeString(
                    logFile,
                    java.time.Instant.now() + " " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 诊断日志写入失败不应影响托盘功能。
        }
    }

    private void reportMenuFailure(Throwable error) {
        String message = safeMessage(error);
        appendLog("menu failure: " + error);
        EventQueue.invokeLater(() -> trayIcon.displayMessage(
                "AI DB Query Gateway", "无法打开托盘菜单：" + message, TrayIcon.MessageType.ERROR));
    }

    private List<NativeTrayMenu.Entry> buildMenuEntries() {
        GatewayStatusClient.Snapshot snapshot = currentSnapshot;
        boolean running = snapshot.state() == GatewayStatusClient.State.RUNNING
                || snapshot.state() == GatewayStatusClient.State.UNHEALTHY;
        boolean stopped = snapshot.state() == GatewayStatusClient.State.STOPPED;
        boolean known = snapshot.state() != GatewayStatusClient.State.UNKNOWN;
        boolean busy = operationInFlight;
        return List.of(
                new NativeTrayMenu.Header("网关状态：" + statusTitle(snapshot.state())),
                new NativeTrayMenu.Separator(),
                new NativeTrayMenu.Action("启动网关", NativeTrayMenu.Command.START, !busy && stopped),
                new NativeTrayMenu.Action("停止网关", NativeTrayMenu.Command.STOP, !busy && running),
                new NativeTrayMenu.Action("重启网关", NativeTrayMenu.Command.RESTART, !busy && known),
                new NativeTrayMenu.Separator(),
                new NativeTrayMenu.Action("打开 Web 控制台", NativeTrayMenu.Command.OPEN_CONSOLE, true),
                new NativeTrayMenu.Action("打开日志目录", NativeTrayMenu.Command.OPEN_LOGS, true),
                new NativeTrayMenu.Action("立即刷新状态", NativeTrayMenu.Command.REFRESH, true),
                new NativeTrayMenu.Separator(),
                new NativeTrayMenu.Action("退出托盘应用", NativeTrayMenu.Command.EXIT, true));
    }

    private void handleCommand(NativeTrayMenu.Command command) {
        appendLog("command=" + command);
        switch (command) {
            case START -> runServiceAction("start");
            case STOP -> runServiceAction("stop");
            case RESTART -> runServiceAction("restart");
            case OPEN_CONSOLE -> openConsole();
            case OPEN_LOGS -> openLogs();
            case REFRESH -> refreshStatusAsync();
            case EXIT -> shutdown();
            case NONE -> { }
        }
    }

    private void refreshStatusAsync() {
        statusExecutor.execute(this::refreshStatus);
    }

    private void refreshStatus() {
        GatewayStatusClient.Snapshot snapshot = statusClient.check();
        EventQueue.invokeLater(() -> applyStatus(snapshot));
    }

    private void applyStatus(GatewayStatusClient.Snapshot snapshot) {
        currentSnapshot = snapshot;
        if (!menuVisible) {
            trayIcon.setToolTip(tooltipText());
        }
    }

    private void runServiceAction(String action) {
        if (operationInFlight) {
            return;
        }
        operationInFlight = true;
        applyStatus(GatewayStatusClient.Snapshot.unknown("正在操作"));
        operationExecutor.submit(() -> {
            GatewayServiceController.CommandResult result = serviceController.execute(action);
            EventQueue.invokeLater(() -> {
                operationInFlight = false;
                if (!result.succeeded()) {
                    trayIcon.displayMessage(
                            "AI DB Query Gateway",
                            truncate(result.output().isBlank() ? "网关操作失败" : result.output()),
                            TrayIcon.MessageType.ERROR);
                }
                refreshStatusAsync();
            });
        });
    }

    private void openConsole() {
        openUri(URI.create("http://127.0.0.1:" + port));
    }

    private void openLogs() {
        try {
            Files.createDirectories(logDirectory);
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("当前系统不支持打开目录");
            }
            Desktop.getDesktop().open(logDirectory.toFile());
        } catch (Exception exception) {
            trayIcon.displayMessage(
                    "AI DB Query Gateway", "无法打开日志目录：" + safeMessage(exception), TrayIcon.MessageType.ERROR);
        }
    }

    private void openUri(URI uri) {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("当前系统不支持打开浏览器");
            }
            Desktop.getDesktop().browse(uri);
        } catch (Exception exception) {
            trayIcon.displayMessage(
                    "AI DB Query Gateway", "无法打开 Web 控制台：" + safeMessage(exception), TrayIcon.MessageType.ERROR);
        }
    }

    private void shutdown() {
        statusExecutor.shutdownNow();
        operationExecutor.shutdownNow();
        SystemTray.getSystemTray().remove(trayIcon);
        instanceLock.close();
        System.exit(0);
    }

    static Path resolveDataDirectory() {
        String explicit = firstNonBlank(System.getProperty("gateway.data-dir"), System.getenv("GATEWAY_DATA_DIR"));
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local")
                : Path.of(localAppData);
        return base.resolve("AI DB Query Gateway").toAbsolutePath().normalize();
    }

    static Path resolveServiceScript() {
        List<Path> candidates = new ArrayList<>();
        String projectDirectory = firstNonBlank(System.getProperty("gateway.project-dir"), System.getenv("GATEWAY_PROJECT_DIR"));
        if (projectDirectory != null) {
            Path project = Path.of(projectDirectory).toAbsolutePath().normalize();
            candidates.add(project.resolve("scripts").resolve("windows-service.ps1"));
        }

        try {
            Path codeLocation = Path.of(GatewayTrayApplication.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path start = Files.isRegularFile(codeLocation) ? codeLocation.getParent() : codeLocation;
            candidates.add(start.resolve("windows-service.ps1"));
            Path current = start;
            for (int i = 0; i < 5 && current != null; i++) {
                candidates.add(current.resolve("scripts").resolve("windows-service.ps1"));
                current = current.getParent();
            }
        } catch (URISyntaxException exception) {
            // Fall through to the working-directory candidates.
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        candidates.add(workingDirectory.resolve("scripts").resolve("windows-service.ps1"));
        candidates.add(workingDirectory.resolve("windows-service.ps1"));
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> Files.isRegularFile(path))
                .findFirst()
                .orElse(null);
    }

    static int resolvePort() {
        String configured = firstNonBlank(System.getenv("GATEWAY_PORT"), System.getProperty("gateway.port"));
        if (configured != null) {
            try {
                int value = Integer.parseInt(configured);
                if (value > 0 && value <= 65535) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Use the safe default below.
            }
        }
        return DEFAULT_PORT;
    }

    private static Image createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(24, 95, 170));
            graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            graphics.drawString("G", 8, 21);
            graphics.setColor(new Color(86, 220, 145));
            graphics.fillOval(21, 20, 7, 7);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static String statusTitle(GatewayStatusClient.State state) {
        return switch (state) {
            case RUNNING -> "运行中";
            case UNHEALTHY -> "运行异常";
            case STOPPED -> "已停止";
            case UNKNOWN -> "检查中";
        };
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String truncate(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 237) + "…";
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : truncate(message);
    }

    private static Thread namedThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void showError(String message) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
            return;
        }
        EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(
                null, message, "AI DB Query Gateway", JOptionPane.ERROR_MESSAGE));
    }

    private static final class InstanceLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private InstanceLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private static InstanceLock tryAcquire(Path path) throws IOException {
            FileChannel channel = FileChannel.open(
                    path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new InstanceLock(channel, lock);
            } catch (OverlappingFileLockException exception) {
                channel.close();
                return null;
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // The process is exiting; the OS will release the lock.
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The process is exiting; the OS will close the channel.
            }
        }
    }
}
