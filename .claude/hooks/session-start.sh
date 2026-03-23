#!/bin/bash
set -euo pipefail

# Only run in remote (Claude Code on the web) environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

HAS_FAILURE=0

# --- Frontend (Node.js / npm) ---
if [ -f "$PROJECT_DIR/frontend/package.json" ]; then
  echo "Installing frontend dependencies..."
  cd "$PROJECT_DIR/frontend"
  npm install || HAS_FAILURE=1
fi

# --- Risk Service (Python / pip) ---
if [ -f "$PROJECT_DIR/risk-service/requirements.txt" ]; then
  echo "Installing risk-service dependencies..."
  cd "$PROJECT_DIR/risk-service"
  pip install -r requirements.txt || HAS_FAILURE=1
fi

# --- Notification Service (Go modules) ---
if [ -f "$PROJECT_DIR/notification-service/go.mod" ]; then
  echo "Installing notification-service dependencies..."
  cd "$PROJECT_DIR/notification-service"
  go mod download || HAS_FAILURE=1
fi

# --- Java Services (Maven) ---
for svc in payment-service account-service ledger-service; do
  if [ -f "$PROJECT_DIR/$svc/pom.xml" ]; then
    echo "Resolving $svc Maven dependencies..."
    cd "$PROJECT_DIR/$svc"
    mvn dependency:resolve -q || HAS_FAILURE=1
  fi
done

if [ "$HAS_FAILURE" -eq 1 ]; then
  echo "Some dependencies failed to install (possibly due to network). Non-fatal."
fi

echo "Dependency setup complete."
