#!/bin/sh
set -eu

# 手动管理本地网关的 launchd 用户服务。
# plist 不放入 ~/Library/LaunchAgents，而是由 bootstrap/bootout 显式加载，
# 因此不会在登录或重启后自动启动；服务加载后由 KeepAlive 负责异常拉起。

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LABEL=${GATEWAY_LAUNCHD_LABEL:-com.tangguo.ai-db-query-gateway}
DOMAIN="gui/$(id -u)"
SERVICE="${DOMAIN}/${LABEL}"
DATA_DIR=${GATEWAY_DATA_DIR:-"${HOME}/Library/Application Support/AI DB Query Gateway"}
PLIST_DIR="${DATA_DIR}/launchd"
PLIST_PATH="${PLIST_DIR}/${LABEL}.plist"
LOG_DIR="${DATA_DIR}/logs"
SERVER_JAR="${PROJECT_DIR}/gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar"
HELPER_PATH="${GATEWAY_KEYCHAIN_HELPER:-${PROJECT_DIR}/native/macos-keychain/build/keychain-helper}"
JAVA_BIN=${GATEWAY_JAVA_BIN:-}
BIND_ADDRESS=${GATEWAY_BIND_ADDRESS:-127.0.0.1}
PORT=${GATEWAY_PORT:-8765}
REMOTE_ENABLED=${GATEWAY_REMOTE_ENABLED:-false}
APPROVAL_REQUIRED=${GATEWAY_QUERY_APPROVAL_REQUIRED:-true}

if [ "$(uname -s)" != "Darwin" ]; then
    echo "launchd 服务仅支持 macOS。" >&2
    exit 1
fi

xml_escape() {
    printf '%s' "$1" |
        sed \
            -e 's/&/\&amp;/g' \
            -e 's/</\&lt;/g' \
            -e 's/>/\&gt;/g' \
            -e 's/"/\&quot;/g' \
            -e "s/'/\&apos;/g"
}

resolve_java() {
    if [ -n "${JAVA_BIN}" ] && [ -x "${JAVA_BIN}" ]; then
        return 0
    fi

    for candidate in \
        "${HOME}/.sdkman/candidates/java/current/bin/java" \
        "/usr/bin/java" \
        "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java" \
        "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/bin/java"; do
        if [ -x "${candidate}" ] && "${candidate}" -version 2>&1 | /usr/bin/grep -q 'version "21'; then
            JAVA_BIN="${candidate}"
            return 0
        fi
    done

    JAVA_BIN=$(command -v java 2>/dev/null || true)
    [ -n "${JAVA_BIN}" ] && [ -x "${JAVA_BIN}" ] &&
        "${JAVA_BIN}" -version 2>&1 | /usr/bin/grep -q 'version "21'
}

require_runtime() {
    if [ ! -f "${SERVER_JAR}" ]; then
        echo "服务 JAR 不存在，请先执行 ./scripts/build.sh。" >&2
        exit 1
    fi
    if [ ! -x "${HELPER_PATH}" ] || [ -L "${HELPER_PATH}" ]; then
        echo "Keychain helper 不可用，请先执行 ./scripts/build.sh。" >&2
        exit 1
    fi
    if ! resolve_java; then
        echo "找不到可执行的 Java 21，请检查 GATEWAY_JAVA_BIN 或 PATH。" >&2
        exit 1
    fi
}

write_plist() {
    mkdir -p "${PLIST_DIR}" "${LOG_DIR}"
    chmod 700 "${PLIST_DIR}" "${LOG_DIR}"

    project_xml=$(xml_escape "${PROJECT_DIR}")
    java_xml=$(xml_escape "${JAVA_BIN}")
    jar_xml=$(xml_escape "${SERVER_JAR}")
    helper_xml=$(xml_escape "${HELPER_PATH}")
    home_xml=$(xml_escape "${HOME}")
    data_dir_xml=$(xml_escape "${DATA_DIR}")
    log_dir_xml=$(xml_escape "${LOG_DIR}")
    bind_address_xml=$(xml_escape "${BIND_ADDRESS}")
    port_xml=$(xml_escape "${PORT}")
    remote_enabled_xml=$(xml_escape "${REMOTE_ENABLED}")
    approval_required_xml=$(xml_escape "${APPROVAL_REQUIRED}")

    umask 077
    /usr/bin/printf '%s\n' \
        '<?xml version="1.0" encoding="UTF-8"?>' \
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
        '<plist version="1.0">' \
        '<dict>' \
        '  <key>Label</key>' \
        "  <string>${LABEL}</string>" \
        '  <key>ProgramArguments</key>' \
        '  <array>' \
        "    <string>${java_xml}</string>" \
        '    <string>-Dfile.encoding=UTF-8</string>' \
        '    <string>-Duser.timezone=Asia/Shanghai</string>' \
        '    <string>-jar</string>' \
        "    <string>${jar_xml}</string>" \
        "    <string>--gateway.secrets.helper-path=${helper_xml}</string>" \
        '  </array>' \
        '  <key>WorkingDirectory</key>' \
        "  <string>${project_xml}</string>" \
        '  <key>EnvironmentVariables</key>' \
        '  <dict>' \
        '    <key>PATH</key>' \
        '    <string>/usr/bin:/bin:/usr/sbin:/sbin</string>' \
        '    <key>HOME</key>' \
        "    <string>${home_xml}</string>" \
        '    <key>GATEWAY_DATA_DIR</key>' \
        "    <string>${data_dir_xml}</string>" \
        '    <key>GATEWAY_BIND_ADDRESS</key>' \
        "    <string>${bind_address_xml}</string>" \
        '    <key>GATEWAY_PORT</key>' \
        "    <string>${port_xml}</string>" \
        '    <key>GATEWAY_REMOTE_ENABLED</key>' \
        "    <string>${remote_enabled_xml}</string>" \
        '    <key>GATEWAY_QUERY_APPROVAL_REQUIRED</key>' \
        "    <string>${approval_required_xml}</string>" \
        '  </dict>' \
        '  <key>RunAtLoad</key>' \
        '  <false/>' \
        '  <key>KeepAlive</key>' \
        '  <true/>' \
        '  <key>ProcessType</key>' \
        '  <string>Interactive</string>' \
        '  <key>ThrottleInterval</key>' \
        '  <integer>10</integer>' \
        '  <key>StandardOutPath</key>' \
        "  <string>${log_dir_xml}/gateway.log</string>" \
        '  <key>StandardErrorPath</key>' \
        "  <string>${log_dir_xml}/gateway.error.log</string>" \
        '</dict>' \
        '</plist>' >"${PLIST_PATH}"
    chmod 600 "${PLIST_PATH}"
}

is_loaded() {
    launchctl print "${SERVICE}" >/dev/null 2>&1
}

wait_until_unloaded() {
    attempt=1
    while [ "${attempt}" -le 20 ]; do
        if ! is_loaded; then
            return 0
        fi
        sleep 0.25
        attempt=$((attempt + 1))
    done
    return 1
}

wait_for_health() {
    attempt=1
    while [ "${attempt}" -le 15 ]; do
        if curl -fsS --max-time 1 "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

start_service() {
    require_runtime
    write_plist

    if ! is_loaded; then
        if /usr/sbin/lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | /usr/bin/grep -q LISTEN; then
            echo "端口 ${PORT} 已被其它进程占用，请先停止该进程。" >&2
            exit 1
        fi
        if ! launchctl bootstrap "${DOMAIN}" "${PLIST_PATH}"; then
            if ! is_loaded; then
                echo "launchd 服务加载失败，请查看 ${LOG_DIR}/gateway.error.log。" >&2
                exit 1
            fi
        fi
    fi

    launchctl kickstart -k "${SERVICE}"
    if ! is_loaded; then
        echo "launchd 服务加载失败，请查看 ${LOG_DIR}/gateway.error.log。" >&2
        exit 1
    fi
    if ! wait_for_health; then
        echo "launchd 服务已加载，但健康检查未通过，请查看 ${LOG_DIR}/gateway.error.log。" >&2
        exit 1
    fi
    echo "网关已由 launchd 启动：${SERVICE}"
    echo "日志：${LOG_DIR}/gateway.log"
}

stop_service() {
    if is_loaded; then
        launchctl bootout "${SERVICE}"
        if ! wait_until_unloaded; then
            echo "launchd 服务停止请求已发送，但服务仍处于加载状态。" >&2
            exit 1
        fi
        echo "网关 launchd 服务已停止。"
    else
        echo "网关 launchd 服务当前未加载。"
    fi
}

status_service() {
    if is_loaded; then
        echo "launchd 状态：已加载（${SERVICE}）"
        launchctl print "${SERVICE}" | sed -n '1,22p'
    else
        echo "launchd 状态：未加载"
    fi

    if curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/actuator/health"; then
        printf '\n网关健康检查：正常\n'
    else
        printf '\n网关健康检查：不可用\n'
    fi
}

case "${1:-status}" in
    start)
        start_service
        ;;
    stop)
        stop_service
        ;;
    restart)
        stop_service
        start_service
        ;;
    status)
        status_service
        ;;
    *)
        echo "用法：$0 {start|stop|restart|status}" >&2
        exit 2
        ;;
esac
