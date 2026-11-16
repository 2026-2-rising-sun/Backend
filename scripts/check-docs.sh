#!/usr/bin/env bash
# 문서 전용 검사는 Python 표준 라이브러리만 사용한다.
set -euo pipefail
backend_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_root"
exec "${PYTHON:-python3}" scripts/check_docs.py "$@"
