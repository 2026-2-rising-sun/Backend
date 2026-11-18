# 2026-11-18 #371 SKU 일반 버전 제거

[실행 계획](plans/completed/371-sku-version/README.md) · [SKU 설계](../design-docs/services/commercecore/sku/overview.md)

## 시작과 변경

브랜치 `refactor-commercecore-sku-version`, clean HEAD `0e0eae6dd92989fb70db532b045b349061d9eb31`에서 시작했다. fetch 후 origin/dev와 같은 커밋임을 확인했다. 사용자가 구현·푸시·PR·병합과 구간별 리뷰·검증을 요청했다.

SKU 일반 version의 엔티티·조회 DTO·SQL·최신 fixture 참조와 판매 응답 필드를 제거했다. V107에서 sku.version만 삭제하며 V102 및 과거 단계 마이그레이션 테스트는 보존했다. Product·Cart·기존 Sales·Catalog 사본의 버전과 이벤트 snapshotVersion은 유지한다. 계약 YAML과 API/설계 MD·HTML을 갱신했다.

## 소비자와 리뷰

- 구현 리뷰: 가격·수량·상태 SQL에서 일반 버전 증가만 제거했다. 조건식·updated_at·트랜잭션·행 잠금·Product→SKU 순서·이벤트 추적 코드는 유지했다.
- 계약 리뷰: 판매 등록·가격·상태 응답에서 version만 제거한다. 로컬 Frontend `e2748d7`의 sellerProducts.ts는 변경 응답을 사용하지 않고 재조회한다. Catalog·Live 조회 DTO에도 일반 버전이 없다. 다른 배포본·저장소 밖 소비자는 확인하지 않았다.
- 마이그레이션 리뷰: V102가 만든 행의 version을 제외한 모든 값을 JSONB로 대조한다. 기존 Sales 버전·이벤트 버전·identity 신규 발급을 검증한다. 구 애플리케이션과 컬럼 삭제의 동시 운영은 지원하지 않으며 실제 배포는 수행하지 않는다.

## 검증 진행

Java 21과 이 작업 전용 Docker PostgreSQL 16, Kafka 4.0.0을 사용한다. 공용 DB나 기존 사용자 컨테이너는 변경하지 않는다. 테스트별 H2·Mock·PostgreSQL·Kafka 증거는 구분한다.

첫 단위 186건·대상 통합 30건은 통과했다. 새 PostgreSQL 경합 테스트의 Outbox 전체 건수 기대가 Product 이벤트까지 포함해 1건 실패했다. SKU 판매 토픽과 키로 범위를 한정한 뒤 대상 PostgreSQL 25건·마이그레이션 7건은 실패·스킵 없이 통과했다.

기본 하네스 검사는 단위 186건·전체 통합 313건 중 외부 Kafka 2건 스킵 때문에 incomplete였다. 선택된 PostgreSQL은 별도 명령으로 실행하고, 격리 Kafka를 준비해 전체 통합을 다시 실행한다. 아래 최종 결과는 해당 미실행·스킵을 실제 격리 환경 실행으로 보완한 결과다.

Orca 브라우저에서 생성 SKU 개요/API HTML을 열었다. 390×844 화면에서 가로 넘침과 깨진 내부 앵커가 없고 변경 설명·목차·API 탐색이 표시되는 것을 확인했다. 캡처는 임시 폴더에만 두었다.

## 최종 회귀와 결과

| 환경·검사 | 테스트 수 | 실패·오류·스킵 |
|---|---:|---|
| CommerceCore 단위 | 186 | 0·0·0 |
| CommerceCore 전체 통합, 외부 Kafka 연결 | 313 | 0·0·0 |
| CommerceCore 실제 PostgreSQL 업무 | 206 | 0·0·0 |
| CommerceCore 실제 PostgreSQL 마이그레이션 | 27 | 0·0·0 |
| Catalog 통합, EmbeddedKafka 포함 | 100 | 0·0·0 |
| Catalog 실제 PostgreSQL 업무 | 11 | 0·0·0 |
| Catalog 실제 PostgreSQL 마이그레이션 | 3 | 0·0·0 |

실행 명령은 `./gradlew :services:commercecore-service:test`, `:services:commercecore-service:integrationTest`, 두 서비스의 `postgresTest`·`migrationTest`, `:services:catalog-service:integrationTest`다. 외부 Kafka를 연결한 CommerceCore 전체 통합은 `--rerun-tasks --max-workers=2`로 다시 실행했다. XML별 합계를 확인한다. 실제 PostgreSQL에서 과거 이행·최신 스키마 validate·초과 판매 방지·보충/해제 경합·가격 변경/예약 경합·환불 복구·Outbox 실패 롤백을 확인했다. Catalog 중복·역순/동시 최초 이벤트 검증도 포함한다.

이벤트 계약 8개·예시 23개·현재 CommerceCore 직렬화 표본 23개가 통과했다. 실제 외부 Kafka 검증은 판매 API→Outbox→브로커 수신과 별도 직렬화기 수신 경로이며, Catalog 전체 경로는 별도의 EmbeddedKafka/DB 검사다. 운영 브로커·실제 PG·프로덕션 배포를 검증한 것은 아니다.

최종 코드·계약 자체 리뷰에서 일반 버전 제거 외의 업무 조건 변경이 없고, 기존 마이그레이션과 이벤트 계약 원문이 보존되는 것을 확인했다. 생성 API의 다른 모듈 문서 변경은 계약 입력 지문 갱신이다. 저장소 밖 소비자·구 배포본 영향과 구 인스턴스 종료를 포함한 실제 배포 절차는 후속 확인 사항이다. PR 게시 후 최신 HEAD·CI·리뷰 스레드를 재확인하고 같은 HEAD를 조건으로 병합한다.

최종 `scripts/verify.py --base origin/dev --external`은 격리 PostgreSQL·Payment fixture DB·Kafka를 지정해 전체 passed였다. 문서/API 생성 최신성·API 구조·이벤트 계약/표본·CommerceCore build·마이그레이션·PostgreSQL 업무 검사가 모두 통과했다. 기본 실행의 incomplete 결과는 이 최종 실행으로 보완했다. 기록 갱신 후 문서 검사와 생성 최신성을 다시 확인했다.
