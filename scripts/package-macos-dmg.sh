#!/usr/bin/env bash
set -euo pipefail

# Packages SafePocket native macOS universal binary (arm64 + x86_64) into SafePocket.app + .dmg
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_NAME="${SAFEPOCKET_RELEASE_VERSION:-0.2}"
VERSION_NAME="${VERSION_NAME#v}"
VERSION_CODE="${SAFEPOCKET_VERSION_CODE:-4}"
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
mkdir -p "${APP_BUNDLE}/Contents/MacOS" "${APP_BUNDLE}/Contents/Resources"

cp "${BIN_PATH}" "${APP_BUNDLE}/Contents/MacOS/SafePocketMac"
chmod +x "${APP_BUNDLE}/Contents/MacOS/SafePocketMac"

cat > "${APP_BUNDLE}/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDisplayName</key>
  <string>SafePocket</string>
  <key>CFBundleExecutable</key>
  <string>SafePocketMac</string>
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
