#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ "$(uname -s)" = "Darwin" ]; then
    "${PROJECT_DIR}/native/macos-keychain/build.sh"
fi

cd "${PROJECT_DIR}"
./mvnw clean verify

if [ "$(uname -s)" = "Darwin" ]; then
    "${PROJECT_DIR}/native/statusbar/build.sh"
fi

if [ "$(uname -s)" = "Darwin" ]; then
    echo "Build completed: gateway-server, gateway-mcp, gateway-tray, and the macOS menu bar app are ready."
else
    echo "Build completed: gateway-server, gateway-mcp, and gateway-tray are ready."
fi
