import AppKit
import Darwin
import Foundation

private let gatewayLabel = "com.tangguo.ai-db-query-gateway"
private let gatewayURL = "http://127.0.0.1:8765/actuator/health"

private struct CommandResult {
    let status: Int32
    let output: String
}

private enum CommandRunner {
    static func run(
        _ executable: String,
        arguments: [String],
        completion: @escaping (CommandResult) -> Void
    ) {
        DispatchQueue.global(qos: .utility).async {
            let process = Process()
            let pipe = Pipe()
            process.executableURL = URL(fileURLWithPath: executable)
            process.arguments = arguments
            process.standardOutput = pipe
            process.standardError = pipe

            do {
                try process.run()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                let output = String(data: data, encoding: .utf8) ?? ""
                completion(CommandResult(status: process.terminationStatus, output: output))
            } catch {
                completion(CommandResult(status: -1, output: error.localizedDescription))
            }
        }
    }
}

private enum GatewayStatus {
    case checking
    case running
    case starting
    case stopped
    case unknown

    var title: String {
        switch self {
        case .checking: return "检查中…"
        case .running: return "运行中"
        case .starting: return "启动中"
        case .stopped: return "已停止"
        case .unknown: return "状态未知"
        }
    }

    var symbol: String {
        switch self {
        case .checking: return "○"
        case .running: return "●"
        case .starting: return "◐"
        case .stopped: return "○"
        case .unknown: return "?"
        }
    }

    var color: NSColor {
        switch self {
        case .running: return .systemGreen
        case .starting, .checking: return .systemOrange
        case .stopped: return .secondaryLabelColor
        case .unknown: return .systemRed
        }
    }
}

@main
final class GatewayMenuBarApplication: NSObject, NSApplicationDelegate {
    private let launchdDomain = "gui/\(getuid())"
    private let projectDirectory: String
    private let launchdScript: String
    private let logFile: String

    private var statusItem: NSStatusItem!
    private var statusLine: NSMenuItem!
    private var startItem: NSMenuItem!
    private var stopItem: NSMenuItem!
    private var restartItem: NSMenuItem!
    private var status = GatewayStatus.checking
    private var operationInFlight = false
    private var refreshInFlight = false
    private var refreshTimer: Timer?

    override init() {
        let bundleDirectory = Bundle.main.object(forInfoDictionaryKey: "GatewayProjectDirectory") as? String
        let configuredDirectory = ProcessInfo.processInfo.environment["AI_DB_GATEWAY_PROJECT_DIR"]
        projectDirectory = configuredDirectory ?? bundleDirectory ?? FileManager.default.currentDirectoryPath
        launchdScript = "\(projectDirectory)/scripts/launchd.sh"

        let dataDirectory = ProcessInfo.processInfo.environment["GATEWAY_DATA_DIR"]
            ?? "\(NSHomeDirectory())/Library/Application Support/AI DB Query Gateway"
        logFile = "\(dataDirectory)/logs/gateway.log"
        super.init()
    }

    static func main() {
        let application = NSApplication.shared
        let delegate = GatewayMenuBarApplication()
        application.delegate = delegate
        application.setActivationPolicy(.accessory)
        application.run()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        configureStatusItem()
        refreshStatus()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.refreshStatus()
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        refreshTimer?.invalidate()
    }

    private func configureStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            if let iconURL = Bundle.main.url(forResource: "AppIcon", withExtension: "icns"),
               let icon = NSImage(contentsOf: iconURL) {
                icon.size = NSSize(width: 18, height: 18)
                icon.isTemplate = false
                button.image = icon
            } else {
                button.image = NSImage(systemSymbolName: "server.rack", accessibilityDescription: "查询网关")
                button.image?.isTemplate = true
            }
            button.imagePosition = .imageLeading
            button.toolTip = "查询网关"
        }

        let menu = NSMenu()
        menu.autoenablesItems = false

        statusLine = NSMenuItem(title: "网关状态：检查中…", action: nil, keyEquivalent: "")
        statusLine.isEnabled = false
        menu.addItem(statusLine)
        menu.addItem(.separator())

        startItem = NSMenuItem(title: "启动服务", action: #selector(startService), keyEquivalent: "")
        stopItem = NSMenuItem(title: "停止服务", action: #selector(stopService), keyEquivalent: "")
        restartItem = NSMenuItem(title: "重启服务", action: #selector(restartService), keyEquivalent: "")
        startItem.target = self
        stopItem.target = self
        restartItem.target = self
        menu.addItem(startItem)
        menu.addItem(stopItem)
        menu.addItem(restartItem)
        menu.addItem(.separator())

        let openPageItem = NSMenuItem(title: "打开管理页面", action: #selector(openManagementPage), keyEquivalent: "")
        openPageItem.target = self
        menu.addItem(openPageItem)

        let openLogItem = NSMenuItem(title: "打开服务日志", action: #selector(openLog), keyEquivalent: "")
        openLogItem.target = self
        menu.addItem(openLogItem)
        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "退出菜单栏管理", action: #selector(quit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)
        statusItem.menu = menu
        updateMenu()
    }

    private var serviceIdentifier: String {
        "\(launchdDomain)/\(gatewayLabel)"
    }

    private func refreshStatus() {
        guard !refreshInFlight, !operationInFlight else { return }
        refreshInFlight = true
        CommandRunner.run("/bin/launchctl", arguments: ["print", serviceIdentifier]) { [weak self] result in
            guard let self else { return }
            if result.status != 0 {
                self.finishStatusRefresh(.stopped)
                return
            }
            CommandRunner.run("/usr/bin/curl", arguments: ["-fsS", "--max-time", "2", gatewayURL]) { [weak self] health in
                self?.finishStatusRefresh(health.status == 0 ? .running : .starting)
            }
        }
    }

    private func finishStatusRefresh(_ newStatus: GatewayStatus) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.refreshInFlight = false
            self.status = newStatus
            self.updateMenu()
        }
    }

    private func updateMenu() {
        guard statusLine != nil else { return }
        statusLine.title = "\(status.symbol)  网关状态：\(status.title)"
        statusLine.attributedTitle = NSAttributedString(
            string: statusLine.title,
            attributes: [
                .foregroundColor: status.color,
                .font: NSFont.systemFont(ofSize: 13, weight: .medium)
            ])

        let busy = operationInFlight || status == .checking
        startItem.isEnabled = !busy && status == .stopped
        stopItem.isEnabled = !busy && (status == .running || status == .starting)
        restartItem.isEnabled = !busy && status != .unknown

        if let button = statusItem.button {
            button.title = "网关"
            button.toolTip = "查询网关：\(status.title)"
        }
    }

    private func runServiceCommand(_ action: String, displayName: String) {
        guard !operationInFlight else { return }
        operationInFlight = true
        status = .starting
        updateMenu()

        CommandRunner.run("/bin/sh", arguments: [launchdScript, action]) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                self.operationInFlight = false
                if result.status != 0 {
                    self.status = .unknown
                    self.updateMenu()
                    self.showError(title: "\(displayName)失败", output: result.output)
                }
                self.refreshStatus()
            }
        }
    }

    @objc private func startService() {
        runServiceCommand("start", displayName: "启动服务")
    }

    @objc private func stopService() {
        runServiceCommand("stop", displayName: "停止服务")
    }

    @objc private func restartService() {
        runServiceCommand("restart", displayName: "重启服务")
    }

    @objc private func openManagementPage() {
        NSWorkspace.shared.open(URL(string: "http://127.0.0.1:8765")!)
    }

    @objc private func openLog() {
        let logURL = URL(fileURLWithPath: logFile)
        if !FileManager.default.fileExists(atPath: logFile) {
            NSWorkspace.shared.open(logURL.deletingLastPathComponent())
        } else {
            NSWorkspace.shared.open(logURL)
        }
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    private func showError(title: String, output: String) {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = title
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        alert.informativeText = trimmed.isEmpty
            ? "请查看服务日志后重试。"
            : String(trimmed.suffix(800))
        alert.addButton(withTitle: "确定")
        alert.runModal()
    }
}
