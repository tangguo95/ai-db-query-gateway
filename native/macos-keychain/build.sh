#!/bin/sh
set -eu

umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:-"$script_dir/build"}

mkdir -p -- "$output_dir"
xcrun swiftc \
  -O \
  -framework Security \
  "$script_dir/KeychainHelper.swift" \
  -o "$output_dir/keychain-helper"
chmod 700 "$output_dir/keychain-helper"

printf '%s\n' "$output_dir/keychain-helper"
