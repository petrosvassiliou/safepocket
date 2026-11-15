#!/usr/bin/env bash
# ==============================================================================
# SafePocket — 1-Command Zero-Knowledge Firebase Database & Auth Provisioner
# ==============================================================================
# Usage:
#   ./scripts/setup-firebase.sh <your-firebase-project-id> [region=eur3]
#
# What this script sets up:
#   1. Creates or attaches your Firebase project (<your-firebase-project-id>).
#   2. Provisions Cloud Firestore in Native mode (default EU multi-region `eur3`).
#   3. Deploys SafePocket's strict zero-knowledge `firestore.rules`,
#      `firestore.indexes.json`, and `storage.rules` (blocking plaintext fields).
#   4. Registers a Web App (`SafePocket PWA`) and writes `pwaApp/.env.local`
#      with `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_PROJECT_ID`,
#      `VITE_FIREBASE_STORAGE_BUCKET`, and `VITE_FIREBASE_APP_ID`.
#
# Note: SafePocket's local storage remains the out-of-the-box default even
#       after linking Firebase (`cloudSyncEnabled = false` until toggled on).
# ==============================================================================
set -euo pipefail

PROJECT_ID="${1:-}"
REGION="${2:-eur3}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "$PROJECT_ID" ]]; then
  echo "Usage: ./scripts/setup-firebase.sh <firebase-project-id> [firestore-region=eur3]"
  exit 1
fi

if ! command -v npx >/dev/null 2>&1; then
  echo "Error: Node.js / npx is required to run firebase-tools."
  exit 1
fi

echo "==> [1/5] Authenticating & verifying Firebase project: ${PROJECT_ID}"
npx --yes firebase-tools projects:list >/dev/null 2>&1 || npx --yes firebase-tools login
npx --yes firebase-tools projects:create "$PROJECT_ID" --display-name "SafePocket Vault" 2>/dev/null || \
  echo "    Project ${PROJECT_ID} already exists — attaching..."

cat > "${REPO_ROOT}/.firebaserc" <<EOF
{
  "projects": {
    "default": "${PROJECT_ID}"
  }
}
EOF

echo "==> [2/5] Provisioning Cloud Firestore database (${REGION})..."
npx --yes firebase-tools firestore:databases:create "(default)" --location "${REGION}" --project "${PROJECT_ID}" 2>/dev/null || \
  echo "    Firestore (default) database already provisioned."

echo "==> [3/5] Deploying Zero-Knowledge Firestore & Storage Security Rules..."
(
  cd "${REPO_ROOT}"
  npx --yes firebase-tools deploy --only firestore:rules,firestore:indexes,storage --project "${PROJECT_ID}" || \
    npx --yes firebase-tools deploy --only firestore:rules,firestore:indexes --project "${PROJECT_ID}"
)

echo "==> [4/5] Registering SafePocket Web App & fetching SDK config..."
npx --yes firebase-tools apps:create WEB "SafePocket ChromeOS PWA" --project "${PROJECT_ID}" 2>/dev/null || true
SDK_JSON="$(npx --yes firebase-tools apps:sdkconfig WEB --project "${PROJECT_ID}" --json)"

API_KEY="$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.result?.sdkConfig?.apiKey || '');" "$SDK_JSON")"
STORAGE_BUCKET="$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.result?.sdkConfig?.storageBucket || '${PROJECT_ID}.firebasestorage.app');" "$SDK_JSON")"
APP_ID="$(node -e "const d=JSON.parse(process.argv[1]); console.log(d.result?.sdkConfig?.appId || '');" "$SDK_JSON")"

cat > "${REPO_ROOT}/pwaApp/.env.local" <<EOF
VITE_FIREBASE_API_KEY=${API_KEY}
VITE_FIREBASE_PROJECT_ID=${PROJECT_ID}
VITE_FIREBASE_STORAGE_BUCKET=${STORAGE_BUCKET}
VITE_FIREBASE_APP_ID=${APP_ID}
EOF

echo "==> [5/5] Complete!"
echo "    1. Enable Email/Password Sign-In once at: https://console.firebase.google.com/project/${PROJECT_ID}/authentication/providers"
echo "    2. Written config to pwaApp/.env.local (Local Storage remains default until Cloud Sync is enabled in Settings)."
