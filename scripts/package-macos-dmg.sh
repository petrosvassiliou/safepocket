#!/usr/bin/env bash
set -euo pipefail

# Packages SafePocket native macOS universal binary (arm64 + x86_64) into SafePocket.app + .dmg
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_NAME="${SAFEPOCKET_RELEASE_VERSION:-0.5.0-beta.1}"
VERSION_NAME="${VERSION_NAME#v}"
VERSION_CODE="${SAFEPOCKET_VERSION_CODE:-16}"
OUT_DMG="${1:-${REPO_ROOT}/SafePocket-${VERSION_NAME}-${VERSION_CODE}-macOS-universal.dmg}"

echo "Building SafePocketMac v${VERSION_NAME} (Build ${VERSION_CODE}) universal macOS binary..."
swift build \
  --package-path "${REPO_ROOT}/macosApp" \
  -c release \
  --arch arm64 \
  --arch x86_64

BIN_PATH="${REPO_ROOT}/macosApp/.build/apple/Products/Release/SafePocketMac"
if [[ ! -f "${BIN_PATH}" ]]; then
  BIN_PATH="$(swift build --package-path "${REPO_ROOT}/macosApp" -c release --show-bin-path)/SafePocketMac"
fi

STAGE_DIR="$(mktemp -d)"
APP_BUNDLE="${STAGE_DIR}/SafePocket.app"
mkdir -p "${APP_BUNDLE}/Contents/MacOS" "${APP_BUNDLE}/Contents/Resources/i18n"

cp "${BIN_PATH}" "${APP_BUNDLE}/Contents/MacOS/SafePocketMac"
chmod +x "${APP_BUNDLE}/Contents/MacOS/SafePocketMac"
cp "${REPO_ROOT}"/i18n/*.arb.json "${APP_BUNDLE}/Contents/Resources/i18n/"

ICON_PNG="${REPO_ROOT}/macosApp/Resources/AppIcon-1024.png"
if [[ -f "${ICON_PNG}" ]] && command -v sips >/dev/null 2>&1 && command -v iconutil >/dev/null 2>&1; then
  ICONSET_DIR="${STAGE_DIR}/AppIcon.iconset"
  mkdir -p "${ICONSET_DIR}"
  sips -z 16 16     "${ICON_PNG}" --out "${ICONSET_DIR}/icon_16x16.png" >/dev/null
  sips -z 32 32     "${ICON_PNG}" --out "${ICONSET_DIR}/icon_16x16@2x.png" >/dev/null
  sips -z 32 32     "${ICON_PNG}" --out "${ICONSET_DIR}/icon_32x32.png" >/dev/null
  sips -z 64 64     "${ICON_PNG}" --out "${ICONSET_DIR}/icon_32x32@2x.png" >/dev/null
  sips -z 128 128   "${ICON_PNG}" --out "${ICONSET_DIR}/icon_128x128.png" >/dev/null
  sips -z 256 256   "${ICON_PNG}" --out "${ICONSET_DIR}/icon_128x128@2x.png" >/dev/null
  sips -z 256 256   "${ICON_PNG}" --out "${ICONSET_DIR}/icon_256x256.png" >/dev/null
  sips -z 512 512   "${ICON_PNG}" --out "${ICONSET_DIR}/icon_256x256@2x.png" >/dev/null
  sips -z 512 512   "${ICON_PNG}" --out "${ICONSET_DIR}/icon_512x512.png" >/dev/null
  cp "${ICON_PNG}" "${ICONSET_DIR}/icon_512x512@2x.png"
  iconutil -c icns "${ICONSET_DIR}" -o "${APP_BUNDLE}/Contents/Resources/AppIcon.icns"
  rm -rf "${ICONSET_DIR}"
fi

cat > "${APP_BUNDLE}/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDisplayName</key>
  <string>SafePocket</string>
  <key>CFBundleExecutable</key>
  <string>SafePocketMac</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundleIdentifier</key>
  <string>com.safepocket.mac</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>SafePocket</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${VERSION_NAME}</string>
  <key>CFBundleVersion</key>
  <string>${VERSION_CODE}</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <false/>
  <key>NSPrincipalClass</key>
  <string>NSApplication</string>
  <key>NSHighResolutionCapable</key>
  <true/>
  <key>NSCameraUsageDescription</key>
  <string>SafePocket uses the camera strictly offline to scan membership barcodes, IDs, and Emergency Recovery QR codes.</string>
</dict>
</plist>
EOF

if command -v codesign >/dev/null 2>&1; then
  SIGN_IDENTITY="${MACOS_CODESIGN_IDENTITY:--}"
  codesign --force --deep --sign "${SIGN_IDENTITY}" "${APP_BUNDLE}"
fi

ln -s /Applications "${STAGE_DIR}/Applications"
rm -f "${OUT_DMG}"
hdiutil create \
  -volname "SafePocket v${VERSION_NAME}" \
  -srcfolder "${STAGE_DIR}" \
  -ov \
  -format UDZO \
  "${OUT_DMG}"

rm -rf "${STAGE_DIR}"
echo "✓ Packaged universal macOS DMG: ${OUT_DMG}"
