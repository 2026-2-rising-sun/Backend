#!/usr/bin/env bash
# 결제창 테스트 페이지를 http://localhost:8095 에 띄운다(8090은 Infra kafka-ui).
# services/payment-service/.env의 PAYMENT_TOSS_CLIENT_KEY를 읽어 gitignore된 config.json을 만든다. 키는 출력하지 않는다.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
env_file="$here/../../.env"
key=$(sed -n 's/^PAYMENT_TOSS_CLIENT_KEY=//p' "$env_file" 2>/dev/null | tail -n 1)
if [[ $key != test_ck_* ]]; then
  echo "$env_file 에 PAYMENT_TOSS_CLIENT_KEY=test_ck_... (API 개별 연동 키의 테스트 클라이언트 키)가 필요합니다." >&2
  exit 1
fi
(umask 077 && printf '{"clientKey": "%s"}\n' "$key" >"$here/config.json")
echo "http://localhost:8095/checkout.html?orderId=<pgOrderId> 를 연다. 끄면 Ctrl+C"
exec python3 -m http.server 8095 --bind 127.0.0.1 --directory "$here"
