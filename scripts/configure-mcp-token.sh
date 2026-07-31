#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
KEYCHAIN_HELPER="${PROJECT_DIR}/native/macos-keychain/build/keychain-helper"
TOKEN_ACCOUNT="mcp:codex:default"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "MCP token storage requires macOS Keychain." >&2
    exit 1
fi

if [ ! -x "${KEYCHAIN_HELPER}" ] || [ -L "${KEYCHAIN_HELPER}" ]; then
    echo "Keychain helper is unavailable. Run scripts/build.sh first." >&2
    exit 1
fi

if [ ! -t 0 ]; then
    echo "Run this script directly in an interactive terminal." >&2
    exit 1
fi

restore_echo() {
    stty echo 2>/dev/null || true
}
trap restore_echo EXIT HUP INT TERM

printf '请输入网关作用域 Token（输入内容不会显示）：'
stty -echo
IFS= read -r scoped_token
stty echo
trap - EXIT HUP INT TERM
printf '\n'

case "${scoped_token}" in
    gwy_[A-Za-z0-9_-]*) ;;
    *)
        unset scoped_token
        echo "Token 格式不正确，未写入 Keychain。" >&2
        exit 1
        ;;
esac

case "${scoped_token}" in
    *[!A-Za-z0-9_-]*)
        unset scoped_token
        echo "Token 包含不支持的字符，未写入 Keychain。" >&2
        exit 1
        ;;
esac

response=$(
    printf '{"action":"put","account":"%s","value":"%s"}\n' \
        "${TOKEN_ACCOUNT}" "${scoped_token}" |
        "${KEYCHAIN_HELPER}"
)
unset scoped_token

ok=$(printf '%s' "${response}" | /usr/bin/plutil -extract ok raw -o - - 2>/dev/null || true)
unset response
if [ "${ok}" != "true" ]; then
    echo "Token 写入 macOS Keychain 失败。" >&2
    exit 1
fi

echo "MCP Token 已安全写入 macOS Keychain。"
