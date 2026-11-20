package com.tangguo.gateway.tray;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.W32APIOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过 Win32 {@code TrackPopupMenu} 显示系统原生托盘菜单。
 *
 * <p>菜单项不设置自定义字体，由 Windows 使用系统菜单字体渲染，因此中文显示正常，
 * 外观与本机其它应用一致，点击菜单外部或按 Esc 由系统自动收起。
 */
final class NativeTrayMenu {

    enum Command {
        NONE,
        START,
        STOP,
        RESTART,
        OPEN_CONSOLE,
        OPEN_LOGS,
        REFRESH,
        EXIT
    }

    sealed interface Entry permits Header, Action, Separator {}

    record Header(String label) implements Entry {}

    record Action(String label, Command command, boolean enabled) implements Entry {}

    record Separator() implements Entry {}

    private interface MenuApi extends Library {
        MenuApi INSTANCE = Native.load("user32", MenuApi.class, W32APIOptions.DEFAULT_OPTIONS);

        WinDef.HMENU CreatePopupMenu();

        boolean AppendMenuW(WinDef.HMENU menu, int flags, int itemId, WString label);

        boolean DestroyMenu(WinDef.HMENU menu);

        int TrackPopupMenu(
                WinDef.HMENU menu, int flags, int x, int y, int reserved, WinDef.HWND owner, WinDef.RECT rect);
    }

    private static boolean sameWindow(WinDef.HWND first, WinDef.HWND second) {
        if (first == null || second == null) {
            return first == second;
        }
        return com.sun.jna.Pointer.nativeValue(first.getPointer())
                == com.sun.jna.Pointer.nativeValue(second.getPointer());
    }

    private static final int MF_STRING = 0x00000000;
    private static final int MF_SEPARATOR = 0x00000800;
    private static final int MF_GRAYED = 0x00000001;
    private static final int TPM_RIGHTBUTTON = 0x0002;
    private static final int TPM_NONOTIFY = 0x0080;
    private static final int TPM_RETURNCMD = 0x0100;
    private static final int WM_NULL = 0x0000;
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final int WS_EX_TRANSPARENT = 0x00000020;
    private static final int WS_EX_TOPMOST = 0x00000008;
    private static final int LWA_ALPHA = 0x00000002;
    private static final int SW_SHOWNOACTIVATE = 4;

    private NativeTrayMenu() {}

    /** 阻塞显示菜单，返回用户选择的命令；调用方应放在后台线程执行。 */
    static Command show(List<Entry> entries) {
        WinDef.HMENU menu = MenuApi.INSTANCE.CreatePopupMenu();
        if (menu == null) {
            return Command.NONE;
        }
        WinDef.HWND created = null;
        WinDef.HWND owner = null;
        try {
            Map<Integer, Command> commands = new HashMap<>();
            int id = 1;
            for (Entry entry : entries) {
                if (entry instanceof Separator) {
                    MenuApi.INSTANCE.AppendMenuW(menu, MF_SEPARATOR, 0, null);
                } else if (entry instanceof Header header) {
                    MenuApi.INSTANCE.AppendMenuW(menu, MF_STRING | MF_GRAYED, id++, new WString(header.label()));
                } else if (entry instanceof Action action) {
                    int flags = MF_STRING | (action.enabled() ? 0 : MF_GRAYED);
                    MenuApi.INSTANCE.AppendMenuW(menu, flags, id, new WString(action.label()));
                    commands.put(id, action.command());
                    id++;
                }
            }
            WinDef.POINT point = new WinDef.POINT();
            User32.INSTANCE.GetCursorPos(point);
            WinDef.HWND previousWindow = User32.INSTANCE.GetForegroundWindow();
            // 使用 1x1 全透明、可见的宿主窗口：可见窗口才能稳定保持前台，
            // 否则系统在几秒后重新激活资源管理器时会把菜单一起关掉。
            created = User32.INSTANCE.CreateWindowEx(
                    WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TRANSPARENT,
                    "STATIC",
                    "",
                    WS_POPUP | WS_VISIBLE,
                    0,
                    0,
                    1,
                    1,
                    null,
                    null,
                    null,
                    null);
            owner = created;
            if (owner != null) {
                User32.INSTANCE.SetLayeredWindowAttributes(owner, 0, (byte) 0, LWA_ALPHA);
                User32.INSTANCE.ShowWindow(owner, SW_SHOWNOACTIVATE);
                User32.INSTANCE.SetForegroundWindow(owner);
            } else {
                // 创建失败时退回当前前台窗口，仍保证点击外部能正常关闭菜单。
                owner = User32.INSTANCE.GetForegroundWindow();
            }
            int selected = MenuApi.INSTANCE.TrackPopupMenu(
                    menu,
                    TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY,
                    point.x,
                    point.y,
                    0,
                    owner,
                    null);
            if (owner != null) {
                User32.INSTANCE.PostMessage(owner, WM_NULL, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
                // 菜单关闭后把前台还给原窗口；若用户已切到别处则保持不动。
                if (created != null && sameWindow(User32.INSTANCE.GetForegroundWindow(), created)) {
                    User32.INSTANCE.SetForegroundWindow(previousWindow);
                }
            }
            return commands.getOrDefault(selected, Command.NONE);
        } finally {
            if (created != null) {
                User32.INSTANCE.DestroyWindow(created);
            }
            MenuApi.INSTANCE.DestroyMenu(menu);
        }
    }
}
