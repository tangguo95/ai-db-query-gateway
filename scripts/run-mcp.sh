#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MCP_JAR="${PROJECT_DIR}/gateway-mcp/target/gateway-mcp.jar"
KEYCHAIN_HELPER="${PROJECT_DIR}/native/macos-keychain/build/keychain-helper"
TOKEN_ACCOUNT="mcp:codex:default"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "MCP token loading requires macOS Keychain." >&2
    exit 1
fi

if [ ! -f "${MCP_JAR}" ]; then
    echo "MCP artifact is missing. Run scripts/build.sh first." >&2
    exit 1
fi

if [ ! -x "${KEYCHAIN_HELPER}" ] || [ -L "${KEYCHAIN_HELPER}" ]; then
    echo "Keychain helper is unavailable. Run scripts/build.sh first." >&2
    exit 1
fi

TOKEN_RESPONSE=$(
    printf '%s\n' \
        "{\"action\":\"get\",\"account\":\"${TOKEN_ACCOUNT}\"}" |
        "${KEYCHAIN_HELPER}"
)

TOKEN_OK=$(printf '%s' "${TOKEN_RESPONSE}" | /usr/bin/plutil -extract ok raw -o - - 2>/dev/null || true)
if [ "${TOKEN_OK}" != "true" ]; then
    echo "The scoped MCP token is missing from macOS Keychain." >&2
    exit 1
fi

AI_DB_GATEWAY_TOKEN=$(
    printf '%s' "${TOKEN_RESPONSE}" |
        /usr/bin/plutil -extract value raw -o - -
)
unset TOKEN_RESPONSE TOKEN_OK

if [ -z "${AI_DB_GATEWAY_TOKEN}" ]; then
    echo "The scoped MCP token is empty." >&2
    exit 1
fi

export AI_DB_GATEWAY_TOKEN
export AI_DB_GATEWAY_URL="${AI_DB_GATEWAY_URL:-http://127.0.0.1:8765}"

exec java \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai \
    -jar "${MCP_JAR}"
