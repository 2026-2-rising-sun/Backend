# 2026-11-19 통합 상품 등록 (#372)

기준: feat-commercecore-sku-api, dev `389e3ae0`에서 구현. 기존 미커밋 변경 없음. 프론트 코드는 수정하지 않았다.

## 변경

통합 multipart 등록, 최초 판매 상태, 요청 기록과 파일 복구, Product initialSkuCount와 Catalog 다중 SKU 부분 수신 차단을 추가했다. 기존 API는 유지한다. 상세는 [실행 계획](plans/completed/372-product-registration/README.md)과 [Product 설계](../design-docs/services/commercecore/product/overview.md#이미지상품sku-통합-등록-372)를 따른다.

## 검증

Java 21과 격리 Docker PostgreSQL 16(55472), 외부 Kafka 4(59092)를 사용했다. Python 계약 도구는 저장소 밖 임시 venv에 설치했다. 운영 DB·브로커·이미지 저장소에 연결하지 않았다.

| 검사 | 실제 결과와 범위 |
|---|---|
| 전체 `./gradlew build --max-workers=1 --console=plain` | Gradle 종료 성공. Notification ConcurrentInsertPostgresTest 3건은 외부 PostgreSQL 미설정으로 스킵됐다. Notification 단위는 원래 NO-SOURCE이며 다른 테스트로 계산하지 않는다. 전체 외부 검증 완료를 뜻하지 않는다. |
| Core `test` / `integrationTest` | 187 / 288건, 실패·오류·스킵 0. COMMERCECORE_TEST_KAFKA_SERVERS를 격리 브로커로 지정해 기존 외부 Kafka 두 검사도 실행했다. |
| Catalog `test` / `integrationTest` | 12 / 84건, 실패·오류·스킵 0. 초기 SKU 수와 일부 SKU만 수신한 목록·상세 차단을 포함한다. |
| Core `postgresTest` 전체 | 142건, 실패·오류·스킵 0. 실행 뒤 XML은 build/verification/372/core-postgres-full에 별도 보존했다. |
| 최종 통합 등록 `postgresTest --tests '*ProductBundle*'` | 13건, 실패·오류·스킵 0. 실제 판매자 JWT·multipart·로컬 파일·PostgreSQL 등록/재응답 1건, 업무·파일 실패/복구/경합 11건, 실제 Outbox 릴레이→EmbeddedKafka 1건. |
| Core `migrationTest` | 27건, 실패·오류·스킵 0. 새 등록 테스트의 Flyway도 V108까지 실제 적용했다. |
| Catalog `postgresTest` / `migrationTest` | 12 / 3건, 실패·오류·스킵 0. V4와 기존 소비·조회 회귀. |
| API·이벤트·문서 | API 경로·메서드, 이벤트 YAML·직렬화 표본, 생성 MD/HTML 최신성·링크 검사를 실행했다. 통합 등록 initialSkuCount=2의 실제 생산 DTO 직렬화 표본도 생성한다. |
| 생성 HTML | Orca 브라우저에서 Product 설계 목차 링크와 390px 화면을 확인했다. 문서 전체 가로 넘침과 깨진 내부 앵커는 0이었다. |

PostgreSQL 명령은 COMMERCECORE_TEST_POSTGRES_{URL,USER,PASSWORD}, CATALOG_TEST_POSTGRES_{URL,USER,PASSWORD}로 격리 DB를 지정했다. `postgresTest`와 `migrationTest`를 일반 `integrationTest` 성공에 포함해 추정하지 않았다. 외부 검사별 실제 Gradle task 종료와 XML을 확인했다.

초기 실패는 테스트 설정의 잘못된 구성 선택, H2 수동 SKU 스키마의 새 열 누락, 실패 주입 후 Mockito 호출 기록 검증에 있었고 수정 후 재검증했다. 첫 공통 전체 빌드에서는 기존 CouponSyncKafkaTest가 시간 초과했으나 전체 빌드 재실행은 통과했다. 첫 check.sh 실행 중 문서·테스트가 바뀌어 해당 실행 결과는 실패이며 최종 증거로 사용하지 않았다. 이후 코드·문서별 검사를 다시 실행했다.

## 검증 경계와 저장 상태

새 등록의 릴레이 전달은 실제 PostgreSQL+EmbeddedKafka이며, Core→외부 브로커 기존 회귀와 구분한다. Catalog의 이벤트 순서·중복·부분 수신 검사는 소비 코드·실제 PostgreSQL 조회 경로에서 별도로 수행했다. 새 등록 API→외부 브로커→별도 Catalog 프로세스 전체 체인을 한 번에 실행한 증거는 아니다. S3·운영 배포·실제 사용자 프론트 등록은 실행하지 않았다.

공통 검사 선택기가 공통 계약 변경으로 제안한 Live/Payment PostgreSQL 검사는 이번 변경 서비스 밖이어서 실행하지 않았다. 전체 일반 빌드와 Core/Catalog 실제 DB 검증을 이 외부 검사들의 성공으로 확대하지 않는다. API·이벤트 계약 변경이 있으며 프론트는 미수정이다. 초기 구현 완료 시점에는 커밋·푸시·PR·병합·배포 없이 작업 트리에 저장했다. 이후 사용자 승인으로 진행하는 PR 리뷰·CI·병합 결과는 GitHub 이력과 함께 확인한다.

## PR #374 리뷰와 재검증

[PR #374](https://github.com/2026-2-rising-sun/Backend/pull/374)에서 DB 원자성·멱등 요청 경합·파일 정리 잠금·Catalog 소비 호환성을 자체 리뷰했다. 가격과 초기 재고의 소수·숫자 문자열 입력도 실제 HTTP 경로에서 거절되는지 추가 확인했다. 공통 JsonTypePolicy가 이미 이를 400으로 거절하므로 구현을 중복 추가하지 않고 네 가지 입력의 회귀 검증을 보완했다. ProductBundleHttpTest 3건은 실패·오류·스킵 0으로 통과했다. CI·리뷰 상태와 최종 병합 커밋은 PR 이력에서 확인한다.
