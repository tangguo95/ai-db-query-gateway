#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SERVER_JAR="${PROJECT_DIR}/gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar"
HELPER_CONFIGURED=${GATEWAY_KEYCHAIN_HELPER:-"${PROJECT_DIR}/native/macos-keychain/build/keychain-helper"}

if [ ! -f "${SERVER_JAR}" ]; then
    echo "Server artifact is missing. Run scripts/build.sh first." >&2
    exit 1
fi

if [ "$(uname -s)" != "Darwin" ]; then
    echo "The production secret store requires macOS Keychain." >&2
    exit 1
fi

if [ ! -f "${HELPER_CONFIGURED}" ] || [ ! -x "${HELPER_CONFIGURED}" ] || [ -L "${HELPER_CONFIGURED}" ]; then
    echo "Keychain helper is missing, not executable, or is a symbolic link. Run scripts/build.sh first." >&2
    exit 1
fi

HELPER_DIR=$(CDPATH= cd -- "$(dirname -- "${HELPER_CONFIGURED}")" && pwd)
HELPER_PATH="${HELPER_DIR}/$(basename -- "${HELPER_CONFIGURED}")"

exec java \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=Asia/Shanghai \
    -jar "${SERVER_JAR}" \
    "--gateway.secrets.helper-path=${HELPER_PATH}" \
    "$@"
