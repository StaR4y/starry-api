#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_BASE="${API_BASE:-http://127.0.0.1:8080}"
IMPORT_FILE="${1:-$ROOT_DIR/examples/accounts.import.json}"

if [[ -z "${ADMIN_TOKEN:-}" ]]; then
  echo "ADMIN_TOKEN is required. Login first and export the returned data.accessToken:" >&2
  echo "curl -X POST ${API_BASE}/auth/login -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"<password>\"}'" >&2
  exit 1
fi

if [[ ! -f "$IMPORT_FILE" ]]; then
  echo "Import file not found: $IMPORT_FILE" >&2
  exit 1
fi

curl -sS -X POST "${API_BASE}/admin/accounts/import" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  --data-binary "@${IMPORT_FILE}"

echo
