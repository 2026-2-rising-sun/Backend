#!/usr/bin/env bash
# 기존 API 검사 환경의 고정 PyYAML 의존성을 사용한다.
set -euo pipefail
backend_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
backend_python="${PYTHON:-$backend_root/.venv-api/bin/python}"
if [[ ! -x "$backend_python" ]]; then
    backend_python="${PYTHON:-python3}"
fi
cd "$backend_root"
exec "$backend_python" scripts/render_api_docs.py "$@"
