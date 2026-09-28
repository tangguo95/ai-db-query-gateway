package com.tangguo.gateway.server;

import com.tangguo.gateway.api.GatewayException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 查询模式使用有限语法解析后重新引用参数，绝不直接执行用户提供的 Shell 文本。 */
@Component
public class ReadOnlyCommandPolicy {
    private static final Map<String, String> FLAGS = Map.ofEntries(
            Map.entry("ls", "-a -l -h -al -la -lh -lah -alh -d -i -n -t -r -S -1 --all --long --human-readable"),
            Map.entry("cat", "-n -b -s -v -E -T"),
            Map.entry("head", "-q -v"), Map.entry("tail", "-q -v"),
            Map.entry("grep", "-i -n -v -c -l -L -w -x -F -E -o -s -h -H --ignore-case --line-number --fixed-strings --extended-regexp"),
            Map.entry("wc", "-l -w -c -m -L"),
            Map.entry("df", "-h -H -T -i -k -m -P -a"), Map.entry("du", "-h -s -sh -k -m -a -c"),
            Map.entry("free", "-h -b -k -m -g -t -w"),
            Map.entry("ps", "-e -f -ef -aux -A -a -u -x -ax -eo"),
            Map.entry("ss", "-t -u -l -n -p -a -s -r -4 -6 -tuln -tulpn -lntp -lntup -antp -tunlp -an"),
            Map.entry("uptime", "-p -s"), Map.entry("uname", "-a -s -r -m -n -v -p -i -o"),
            Map.entry("id", "-u -g -G -n -r"), Map.entry("whoami", ""),
            Map.entry("hostname", "-f -s -i -I"), Map.entry("date", "-u -R -I --iso-8601"),
            Map.entry("pwd", "-L -P"), Map.entry("stat", "-L -f"),
            Map.entry("journalctl", "--no-pager -r -q -b -k --utc --no-hostname --plain"),
            Map.entry("systemctl", "--no-pager --plain --all --full --failed"));
    private static final Map<String, Set<String>> VALUES = Map.of(
            "head", Set.of("-n", "-c"), "tail", Set.of("-n", "-c"),
            "grep", Set.of("-m", "-A", "-B", "-C", "-e"),
            "du", Set.of("--max-depth"), "ps", Set.of("-o", "-eo", "-p", "--sort"),
            "journalctl", Set.of("-n", "--lines", "-u", "--unit", "--since", "--until", "-p"));
    private static final Set<String> NO_OPERANDS = Set.of("free", "ss", "uptime", "uname", "whoami", "hostname", "date", "pwd");
    private static final Set<String> SYSTEMCTL = Set.of("status", "show", "is-active", "is-enabled", "list-units", "list-unit-files", "list-failed");

    public String prepare(String command, boolean fullAccess) {
        if (command == null || command.isBlank() || command.length() > 16384 || command.indexOf('\0') >= 0) deny();
        if (fullAccess) return command;
        List<List<String>> stages = parse(command);
        List<String> prepared = new ArrayList<>();
        for (List<String> args : stages) {
            String name = args.getFirst();
            if (!FLAGS.containsKey(name)) deny();
            Set<String> flags = new HashSet<>(Arrays.asList(FLAGS.get(name).split(" ")));
            Set<String> values = VALUES.getOrDefault(name, Set.of());
            boolean operands = false;
            List<String> positional = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                if (!operands && "--".equals(arg)) { operands = true; continue; }
                if (!operands && arg.startsWith("-")) {
                    if (values.contains(arg)) {
                        if (++i >= args.size()) deny();
                        String value = args.get(i);
                        if (value.startsWith("-") || value.isBlank()) deny();
                        if (Set.of("head", "tail", "du").contains(name)
                                || (name.equals("grep") && !arg.equals("-e"))
                                || (name.equals("journalctl") && Set.of("-n", "--lines").contains(arg))) {
                            if (!value.matches("[0-9]{1,6}")) deny();
                        }
                    } else if (!flags.contains(arg)) deny();
                } else positional.add(arg);
            }
            if (NO_OPERANDS.contains(name) && !positional.isEmpty()) deny();
            if (name.equals("systemctl") && (positional.isEmpty() || !SYSTEMCTL.contains(positional.getFirst()))) deny();
            if (name.equals("ps") && positional.stream().anyMatch(a -> !Set.of("aux", "ax", "ef").contains(a))) deny();
            prepared.add("command " + name + args.stream().skip(1).map(a -> " " + quote(a)).reduce("", String::concat));
        }
        // 不同发行版的工具分布于 bin/sbin；清空环境，只在系统目录中解析固定命令名。
        String preflight = stages.stream().map(args -> "command -v " + args.getFirst()
                + " >/dev/null 2>&1 || { printf '%s\\n' '查询命令不存在：" + args.getFirst() + "' >&2; exit 127; }; ")
                .reduce("", String::concat);
        return "/usr/bin/env -i LC_ALL=C PATH=/usr/bin:/bin:/usr/sbin:/sbin SYSTEMD_PAGER=cat PAGER=cat "
                + "/bin/sh -c " + quote(preflight + String.join(" | ", prepared));
    }

    private List<List<String>> parse(String command) {
        List<List<String>> stages = new ArrayList<>();
        List<String> args = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char c : command.toCharArray()) {
            // 不支持展开、重定向、后台任务或复合语句；引号内也保守拒绝这些字符。
            if (";&<>`$\\\n\r\0".indexOf(c) >= 0 || (Character.isISOControl(c) && c != '\t')) deny();
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c; started = true;
            } else if (c == '|' || Character.isWhitespace(c)) {
                if (started) { args.add(token.toString()); token.setLength(0); started = false; }
                if (c == '|') {
                    if (args.isEmpty() || stages.size() >= 7) deny();
                    stages.add(args); args = new ArrayList<>();
                }
            } else { token.append(c); started = true; }
        }
        if (quote != 0) deny();
        if (started) args.add(token.toString());
        if (args.isEmpty()) deny();
        stages.add(args);
        return stages;
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static void deny() {
        throw new GatewayException(HttpStatus.FORBIDDEN, "SERVER_READ_ONLY_POLICY",
                "查询模式仅支持常见查询命令、允许的参数及管道；不支持写操作、脚本、重定向或命令展开。可调整命令，或由管理员开启完整权限。");
    }
}
