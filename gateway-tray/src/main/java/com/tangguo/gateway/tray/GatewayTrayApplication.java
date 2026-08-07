package com.tangguo.gateway.tray;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
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
import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

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
    private JPopupMenu popupMenu;
    private JWindow popupAnchor;
    private JMenuItem statusItem;
    private JMenuItem startItem;
    private JMenuItem stopItem;
    private JMenuItem restartItem;
    private boolean operationInFlight;
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
        EventQueue.invokeLater(GatewayTrayApplication::launch);
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
        Font menuFont = resolveMenuFont();
        popupMenu = new JPopupMenu();
        popupMenu.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        statusItem = menuItem("网关状态：检查中…", menuFont);
        statusItem.setEnabled(false);
        popupMenu.add(statusItem);
        popupMenu.addSeparator();

        startItem = menuItem("启动网关", menuFont);
        startItem.addActionListener(event -> runServiceAction("start"));
        popupMenu.add(startItem);

        stopItem = menuItem("停止网关", menuFont);
        stopItem.addActionListener(event -> runServiceAction("stop"));
        popupMenu.add(stopItem);

        restartItem = menuItem("重启网关", menuFont);
        restartItem.addActionListener(event -> runServiceAction("restart"));
        popupMenu.add(restartItem);

        popupMenu.addSeparator();
        JMenuItem openConsoleItem = menuItem("打开 Web 控制台", menuFont);
        openConsoleItem.addActionListener(event -> openConsole());
        popupMenu.add(openConsoleItem);

        JMenuItem openLogsItem = menuItem("打开日志目录", menuFont);
        openLogsItem.addActionListener(event -> openLogs());
        popupMenu.add(openLogsItem);

        JMenuItem refreshItem = menuItem("立即刷新状态", menuFont);
        refreshItem.addActionListener(event -> refreshStatusAsync());
        popupMenu.add(refreshItem);

        popupMenu.addSeparator();
        JMenuItem exitItem = menuItem("退出托盘应用", menuFont);
        exitItem.addActionListener(event -> shutdown());
        popupMenu.add(exitItem);

        popupAnchor = new JWindow();
        popupAnchor.setFocusableWindowState(false);
        popupAnchor.setAlwaysOnTop(true);
        popupAnchor.setSize(1, 1);
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {}

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                popupAnchor.setVisible(false);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                popupAnchor.setVisible(false);
            }
        });

        trayIcon = new TrayIcon(createTrayImage(), "AI DB Query Gateway");
        trayIcon.setImageAutoSize(true);
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                showPopupForTrayEvent(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                showPopupForTrayEvent(event);
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

    private void showPopupForTrayEvent(MouseEvent event) {
        if (!event.isPopupTrigger() && !SwingUtilities.isRightMouseButton(event)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPopupTimestamp < 150) {
            return;
        }
        lastPopupTimestamp = now;
        EventQueue.invokeLater(this::showPopup);
    }

    private void showPopup() {
        if (popupAnchor == null || popupMenu == null) {
            return;
        }
        var pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) {
            return;
        }
        Point pointer = pointerInfo.getLocation();
        popupAnchor.setLocation(pointer);
        popupAnchor.setVisible(true);
        popupMenu.show(popupAnchor, 0, 0);
    }

    private void refreshStatusAsync() {
        statusExecutor.execute(this::refreshStatus);
    }

    private void refreshStatus() {
        GatewayStatusClient.Snapshot snapshot = statusClient.check();
        EventQueue.invokeLater(() -> applyStatus(snapshot));
    }

    private void applyStatus(GatewayStatusClient.Snapshot snapshot) {
        String title = statusTitle(snapshot.state());
        statusItem.setLabel("网关状态：" + title);
        trayIcon.setToolTip("AI DB Query Gateway · " + title);

        boolean running = snapshot.state() == GatewayStatusClient.State.RUNNING
                || snapshot.state() == GatewayStatusClient.State.UNHEALTHY;
        boolean stopped = snapshot.state() == GatewayStatusClient.State.STOPPED;
        boolean busy = operationInFlight;
        startItem.setEnabled(!busy && stopped);
        stopItem.setEnabled(!busy && running);
        restartItem.setEnabled(!busy && snapshot.state() != GatewayStatusClient.State.UNKNOWN);
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
        if (popupMenu != null) {
            popupMenu.setVisible(false);
        }
        if (popupAnchor != null) {
            popupAnchor.dispose();
        }
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

    private static JMenuItem menuItem(String label, Font font) {
        JMenuItem item = new JMenuItem(label);
        item.setFont(font);
        return item;
    }

    private static Font resolveMenuFont() {
        Set<String> availableFamilies = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : List.of("Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Segoe UI")) {
            if (availableFamilies.contains(family)) {
                return new Font(family, Font.PLAIN, 12);
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
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

    private static String safeMessage(Exception exception) {
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
