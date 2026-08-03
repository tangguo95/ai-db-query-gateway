#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "${script_dir}/../.." && pwd)
output_dir=${1:-"${script_dir}/build"}
app_bundle="${output_dir}/AI DB Query Gateway.app"
contents_dir="${app_bundle}/Contents"
icon_source="${script_dir}/resources/AppIcon.png"
iconset_dir="${output_dir}/AppIcon.iconset"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "菜单栏管理工具仅支持 macOS。" >&2
    exit 1
fi

if [ ! -f "${icon_source}" ]; then
    echo "菜单栏 App 图标素材不存在：${icon_source}" >&2
    exit 1
fi

mkdir -p "${output_dir}"
rm -rf "${app_bundle}"
mkdir -p "${contents_dir}/MacOS" "${contents_dir}/Resources"
rm -rf "${iconset_dir}"
mkdir -p "${iconset_dir}"

sips -z 16 16 "${icon_source}" --out "${iconset_dir}/icon_16x16.png" >/dev/null
sips -z 32 32 "${icon_source}" --out "${iconset_dir}/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "${icon_source}" --out "${iconset_dir}/icon_32x32.png" >/dev/null
sips -z 64 64 "${icon_source}" --out "${iconset_dir}/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "${icon_source}" --out "${iconset_dir}/icon_128x128.png" >/dev/null
sips -z 256 256 "${icon_source}" --out "${iconset_dir}/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "${icon_source}" --out "${iconset_dir}/icon_256x256.png" >/dev/null
sips -z 512 512 "${icon_source}" --out "${iconset_dir}/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "${icon_source}" --out "${iconset_dir}/icon_512x512.png" >/dev/null
sips -z 1024 1024 "${icon_source}" --out "${iconset_dir}/icon_512x512@2x.png" >/dev/null
/usr/bin/iconutil --convert icns "${iconset_dir}" --output "${contents_dir}/Resources/AppIcon.icns"

xcrun swiftc \
    -O \
    -parse-as-library \
    -framework AppKit \
    -framework Foundation \
    -framework CoreServices \
    "${script_dir}/GatewayMenuBar.swift" \
    -o "${contents_dir}/MacOS/gateway-menubar"

chmod 755 "${contents_dir}/MacOS/gateway-menubar"

/usr/bin/plutil -create xml1 "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleDisplayName -string "查询网关" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleExecutable -string "gateway-menubar" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleIdentifier -string "com.tangguo.ai-db-query-gateway.menubar" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleIconFile -string "AppIcon" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleIconName -string "AppIcon" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleName -string "查询网关" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundlePackageType -string "APPL" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleShortVersionString -string "0.1.0" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert CFBundleVersion -string "1" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert GatewayProjectDirectory -string "${project_dir}" "${contents_dir}/Info.plist"
/usr/bin/plutil -insert LSUIElement -bool true "${contents_dir}/Info.plist"
/usr/bin/plutil -insert NSHighResolutionCapable -bool true "${contents_dir}/Info.plist"

printf '%s\n' "${app_bundle}"
