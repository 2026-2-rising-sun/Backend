# 2026-11-18 #340 구매 SKU 장바구니 정리

[실행 계획](plans/active/340-cart-sku-cleanup/README.md) · [Cart 설계](../design-docs/services/commercecore/cart/overview.md)

## 시작과 변경

별도 워크스페이스 `cart-sku-cleanup-340`, 브랜치 `fix/#340-cart-sku-cleanup`, clean HEAD `32cc00d53646b906a41de3295a59754d3cfc45a3`에서 시작했다. fetch 후 origin/dev와 좌우 차이 0/0을 확인했다. 원본 dirty 체크아웃은 수정하지 않았다.

Cart 정리를 회원+구매 SKU로 통합하고 구 결제 묶음·과거 단건 및 새 relay에 연결했다. 현재 Cart 행을 ID순으로 잠근 뒤 SKU를 처리하며 기존 결과 terminal/승인 사실과 상태 전이의 최초 성공 멱등성을 유지한다. Cart 출처 ID/version은 생성 검증·선택 환불 호환을 위해 유지한다. 직접 구매·실패·취소·만료에는 정리하지 않는다. SQL/알림 오류는 같은 트랜잭션으로 롤백한다.

API 경로·요청·응답 스키마와 이벤트 계약은 바꾸지 않았다. Core API YAML의 성공 정리 업무 설명은 새 정책으로 수정하고 생성 자료를 갱신한다. Payment 소유 코드·계약·DB, #345/#346/#360은 수정하지 않는다.

## 실행 검증

Java 21은 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`을 사용했다. 작업 전용 `cart340-postgres`(PostgreSQL 16.15, localhost:52986)에 `cart340_core`와 `cart340_payment`를 분리했고 각 테스트가 별도 스키마를 생성했다. 외부 Kafka는 작업 전용 `cart340-kafka`(apache/kafka:4.0.0, localhost:53401)다. 기존 실행 중 인프라는 수정하지 않았다.

| 실제 명령·환경 | 결과 |
|---|---|
| `git fetch origin dev`, `git rev-list --left-right --count HEAD...origin/dev` | 기준 SHA 동일, 0/0 |
| `.venv-api/bin/python scripts/verify.py --dry-run --base origin/dev` | Core build·postgresTest, 문서·API·이벤트·표본 검사 선택 |
| Core `integrationTest --tests '*CartSelectionServiceTest' --tests '*PaymentGroupIntegrationTest' --tests '*CartOrderIntegrationTest'` | H2 43건, 실패·오류·스킵 0 |
| Core `postgresTest --tests '*CartSelectionPostgresTest' --tests '*PaymentRelayPostgresTest'` | 실제 PostgreSQL 52건, 실패·오류·스킵 0 |
| `JAVA_HOME=… COMMERCECORE_TEST_KAFKA_SERVERS=127.0.0.1:53401 ./gradlew :services:commercecore-service:spotlessApply :services:commercecore-service:build --no-daemon --max-workers=2` | 실제 Core 단위 186 + 일반 통합 312건, 실패·오류·스킵 0. 조건부 외부 Kafka 2건 실행 |
| `./gradlew :services:commercecore-service:postgresTest --no-daemon --max-workers=2` + 아래 전용 DB 환경 | 실제 PostgreSQL 205건, 실패·오류·스킵 0 |
| `.venv-api/bin/python .github/scripts/api/check_events.py --samples` | 이벤트 8개·예시 23개·현재 Core 직렬화 표본 23개 통과 |
| `scripts/check-docs.sh`, API·설계 생성기의 `--check`, API 구조·이벤트 기본 검사 | 통과. 화면 시각 검사와 구분 |
| `.venv-api/bin/python scripts/verify.py --base origin/dev --external` 최종 실행 | 전체 passed, 검사 중 diff 변경 없음. Core build는 직전 실제 단위/통합 결과를 UP-TO-DATE로 재사용, PostgreSQL 205건은 다시 실제 실행하여 실패·오류·스킵 0 |
| `git diff --check`, 성공 삭제 호출·소유 경계 대조 | 통과. Payment 소유 코드·이벤트·마이그레이션 변경 없음 |

PostgreSQL 환경은 `COMMERCECORE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:52986/cart340_core`, `PAYMENT_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:52986/cart340_payment`, 각 USER/PASSWORD는 작업 전용 postgres 계정으로 지정했다. 최종 Core 실행은 합계 703건이며 앞선 부분 검사 건수를 더하지 않는다. 태그 제외된 migrationTest·redisTest는 이번에 실행하지 않았고 DDL·Redis 변경도 없다.

실패 이력도 보존한다. 첫 부분 실행은 Cart 포트 변경에 맞추지 않은 단위 테스트 mock 타입 2곳의 컴파일 오류로 중단했고 수정했다. 첫 전체 실행의 추가 단건 테스트는 신규 확정 결과로 허용되지 않는 TIMEOUT을 잘못 입력해 1건 실패했으며 SUCCESS/FAILED 입력으로 수정했다. 같은 실행의 PostgreSQL 205건 중 legacy 판매자 스냅샷 8건은 HTML용 임시 Python 서버와 Payment fixture의 포트 53400이 겹쳐 문서 서버가 결제 POST에 501을 반환했다. 문서 서버 로그와 fixture 시작 포트를 대조하고 임시 서버를 종료했다. 변경 전 `32cc00d5`를 `/tmp/cart340-baseline-32cc00d5`에 archive해 비교한 해당 클래스 8건·전체 PostgreSQL 196건은 통과했다. 환경 충돌 제거 후 변경본 전체 PostgreSQL 205건도 통과했다. Payment 정책·코드를 수정해 우회하지 않았다.

Cart 포트의 실제 PostgreSQL 잠금 대기·수량 변경 후 삭제·롤백과 새 relay의 변경/재추가·다른 옵션/회원 보존·중복 후 재담기·실패/취소/만료/늦은 승인 보존·성공 알림 실패 원자성 및 재시도를 확인했다. 과거 묶음 없는 단건 분기는 mock 단위 검사이며, legacy 묶음/단건 Cart는 H2와 PostgreSQL 기존 통합 검사 근거를 함께 본다. 새 relay의 remote는 계약 대역이다. 독립 Payment HTTP fixture는 실제 제공자가 아니다.

생성 HTML의 링크·앵커·쌍·최신성은 검사했으나 CUA가 `The browser access host is unavailable`을 반환해 대표 화면·좁은 화면 시각 확인은 수행하지 못했다. 정적 HTML 통과를 렌더링 증거로 표시하지 않는다.

## 남은 범위와 저장

이슈 본문의 FigJam Cart 영역 반영 조건을 확인했으나 외부 쓰기는 수행하지 않았다. 초기 요청에서는 로컬 결과만 작성했고, 후속 요청으로 검증 후 커밋·푸시·PR·dev 병합까지 승인받았다. 이슈 편집·종료와 배포는 수행하지 않는다. 작업 전용 PostgreSQL·Kafka 컨테이너와 임시 문서 서버는 검증 후 정리했다. 실제 PG·SDK·운영 검증은 범위 밖이며 PostgreSQL과 Payment 대역 검사를 실제 제공자 증거로 확대하지 않는다.

## 후속 게시와 병합 검증

후속 요청 시 `git fetch origin dev` 후에도 dev는 `32cc00d5`이며 시작점과 차이 0/0이었다. 기존 전체 검사 passed와 Core 단위 186·통합 312·PostgreSQL 205건의 실패·오류·스킵 0을 XML에서 다시 확인했다. 이후 변경은 권한 기록 MD/HTML뿐이므로 문서·생성 최신성·diff 검사를 다시 수행하고, 커밋 후 PR의 정확한 head와 CI·미해결 리뷰 상태를 확인해 dev에 병합한다. FigJam 잔여가 있어 `Refs #340`으로 연결하며 자동 종료하지 않는다. 실제 PR·CI·병합 결과는 GitHub PR에서 확인한다.
