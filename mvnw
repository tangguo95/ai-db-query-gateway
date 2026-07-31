#!/bin/sh
set -eu

MAVEN_VERSION="3.9.11"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_CACHE_DIR="${MAVEN_WRAPPER_CACHE:-${SCRIPT_DIR}/.mvn-dist}"
MAVEN_HOME_DIR="${MAVEN_CACHE_DIR}/apache-maven-${MAVEN_VERSION}"
ARCHIVE_PATH="${MAVEN_CACHE_DIR}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
CHECKSUM_PATH="${ARCHIVE_PATH}.sha512"
DOWNLOAD_BASE="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}"

if [ ! -x "${MAVEN_HOME_DIR}/bin/mvn" ]; then
    mkdir -p "${MAVEN_CACHE_DIR}"
    curl --fail --location --silent --show-error \
        "${DOWNLOAD_BASE}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        --output "${ARCHIVE_PATH}"
    curl --fail --location --silent --show-error \
        "${DOWNLOAD_BASE}/apache-maven-${MAVEN_VERSION}-bin.tar.gz.sha512" \
        --output "${CHECKSUM_PATH}"

    EXPECTED_SHA=$(tr -d '[:space:]' < "${CHECKSUM_PATH}")
    ACTUAL_SHA=$(shasum -a 512 "${ARCHIVE_PATH}" | awk '{print $1}')
    if [ "${EXPECTED_SHA}" != "${ACTUAL_SHA}" ]; then
        echo "Maven distribution checksum verification failed." >&2
        exit 1
    fi

    tar -xzf "${ARCHIVE_PATH}" -C "${MAVEN_CACHE_DIR}"
fi

exec "${MAVEN_HOME_DIR}/bin/mvn" "$@"

