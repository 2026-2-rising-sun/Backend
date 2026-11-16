#!/usr/bin/env bash
# 변경 영향에 따라 문서·도구·포맷·테스트·계약 검사를 선택한다.
set -euo pipefail
backend_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
backend_python="${PYTHON:-$backend_root/.venv-api/bin/python}"
if [[ ! -x "$backend_python" ]]; then
    backend_python="${PYTHON:-python3}"
fi
cd "$backend_root"
exec "$backend_python" scripts/verify.py "$@"
