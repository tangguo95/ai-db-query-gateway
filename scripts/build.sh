#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ "$(uname -s)" = "Darwin" ]; then
    "${PROJECT_DIR}/native/macos-keychain/build.sh"
fi

cd "${PROJECT_DIR}"
./mvnw clean verify

echo "Build completed: gateway-server and gateway-mcp are ready under their target directories."
