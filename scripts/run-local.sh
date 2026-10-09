#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
if [[ -z "${TG_API_ID:-}" || -z "${TG_API_HASH:-}" ]]; then
  echo "Fill TG_API_ID and TG_API_HASH in $ROOT/.env, then run this script again." >&2
  exit 1
fi
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
exec ./gradlew --console=plain :backend:run
