# CommerceCore Product 구현 계획 — #333

상태: **Product 독립 구현·계약 반영 완료 · 후속 SKU/Cart/Order 통합 대기.** 2026-11-13 사용자 코드 구현 지시와 코디네이터 Task `task_c4e31898898a`로 기존 착수 대기를 해제했다. 실제 결과는 [워크로그](../../../worklog/2026-11-13.md)를 따른다.

- 작업 경로: `/Users/junje/orca/workspaces/Backend/commercecore-product`, 브랜치 `refactor/#333-product-module`.
- 시작 기준: `cc91c09b753a12a352bda65481210e264d3e88b0`. 기존 설계 변경을 보존하고 fetch·ff-only 동기화를 확인했다. 로컬 pull.rebase 설정 때문에 첫 pull이 dirty로 거절되어 해당 명령에서만 rebase=false로 재실행했다. reset/stash 없음.
- 동일 세션 gpt-6.1-sol / medium으로 진행하며 다른 워크트리·FigJam은 수정하지 않는다. FigJam 편집은 코디네이터가 맡는다.
- [ADR](../../../adr/0333-product-sku-boundary.md) · [실제 포트/공유 경계](model.md) · [Product ERD·상태](../../../design-docs/commercecore/product/README.md) · [API 조각](api-changes.md).

## 1. 확정 정책

Product는 공통 이미지·이름·설명을 묶고 SKU는 옵션 조합별 구매 단위다. 상품 선저장과 SKU 생성을 분리하며 옵션 없는 상품도 실제 판매 때 기본 SKU를 쓴다. SKU 가격·초기재고는 필수이고 재고 0을 허용한다.

Product 전체 판매허용과 SKU 개별 상태는 독립이다. 전체 중지·재개는 SKU 상태/수량을 덮어쓰지 않는다. 신규 Product false·SKU READY로 준비하고 판매자가 시작한다. 기존 상품 상태는 보존한다. Product에 품절을 중복 저장하지 않는다.

Product는 소프트 삭제하고 이미지 FK도 사용하지 않는다. 앱 참조 검증·NOT NULL/UNIQUE/CHECK는 유지한다. 기존 주문 표시/단가는 snapshot으로 독립이며 완료·해제·환불 복구에 신규 거래의 판매허용 검사를 재사용하지 않는다. 실제 재고 오차 보정 업무는 제외한다.

옵션 JSON/Catalog 상세 관리와 조합 변경·중복 판정은 [FigJam 후속 이슈](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=448-1280)로 보류한다. 새로운 옵션 저장소/계약을 만들지 않는다. 자세한 답변과 대안은 ADR을 원본으로 유지한다.

## 2. 자체 구현

| 영역 | 현재 구현 | 확인할 동작 |
|---|---|---|
| Product 상태/삭제 | sale_enabled, deleted_at, 기존 version | 본인·버전 검증, 동일 상태 no-op, 삭제 재요청 멱등, 이미지/하위 행 보존 |
| Core V101 | 기존 true 보존 후 신규 기본 false, 삭제 CHECK, 이미지 FK 제거 | V100→V101 이행, 유일성/NOT NULL 보존, 앱에서 없는 이미지 거절 |
| 거래 포트 | ProductTradingService의 MANDATORY·정렬 공유 잠금·불변 snapshot | 신규 주문 미삭제/전체 허용, SKU 생성은 본인/미삭제만, 최신 상태 재조회 |
| 일반 조회 | ProductSnapshot에 saleEnabled, 삭제 제외 | 기존 단건/일괄 signature와 요청 순서·누락 제외 유지 |
| 이벤트 | Product 변경과 같은 TX의 전체 snapshot | saleEnabled/deletedAt, 중복 no-op, Outbox 실패 시 원본 롤백 |
| Catalog V2/소비 | 제어/삭제 사본, version·삭제 사실 보존 | 구형 이벤트 true/null 호환, 역순/중복·삭제 후 구형 snapshot 보호 |
| Catalog 공개 조회 | 기존 공개 조건 + 삭제 제외, 전체 중지 시 구매 불가 | SKU 상태/재고와 구분한 SALE_SUSPENDED, 실제 예약/재고 갱신 없음 |
| API | sale-state PATCH, version 쿼리 DELETE, 응답 제어 필드 | 실제 HTTP·보안 검증, Order 승인 범위 원본/HTML 반영 완료 |
| MD/HTML | 모듈 ERD/상태 MD 원본과 생성 HTML | 현재/목표·근거·원본 링크, 1280px/390px 화면·필터 검증 |

## 3. 다른 모듈과 통합

Product/Cart/SKU ID는 BIGINT/Long이다. SKU ID는 기존 Sales ID를 보존한다. Product는 SKU 엔티티·가격·재고 테이블/SQL을 중복 구현하지 않는다.

- Core V101/Catalog V2: Product #333. SKU는 V102/Catalog V3, Cart는 V103, Order는 V104.
- 의존 PR base: Product→dev, SKU→Product 브랜치, Cart→SKU 브랜치, Order→Cart 브랜치. 각자 검증된 SHA를 일반 merge로 통합해 ancestry를 보존한다. 가능하면 ff-only, cherry-pick/rebase/force push는 하지 않는다.
- Core API YAML 최종 편집자는 Order #331이다. Order가 검토한 [Core 조각](api-changes.yaml)의 신규 3매핑·제어 필드와 [Catalog 조각](catalog-api-changes.yaml)의 최소 reason 확장을 #333 원본/HTML에 제한 반영했다. 후속 Order/SKU 통합에서도 해당 변경을 보존한다.
- Catalog Product 이벤트/사본·최소 제어 조회는 #333, sales-info·다중 SKU 판매 사본은 #334 소유다. 공유 Query/DTO 파일의 후속 변경은 Product 선행 커밋을 먼저 통합해 조정한다.
- SKU 검증 커밋의 실제 포트를 기준으로 #334에서 상품별 SKU 목록을 연결한다. 다중 SKU의 대표가·최저가·합산재고·외부 가격정렬을 임의 도입하지 않는다. 기존 단일 SKU 응답 호환은 통합 대상이다.
- [Product 조회 연결 조각·검증 기준](sku-query-connection.md)은 코디네이터 배정에 따라 #334가 적용한다. #333은 SKU/Order를 역merge하지 않는다.
- Order는 모든 Product 정렬 공유 잠금 다음 모든 SKU 정렬 잠금과 수량/상태를 같은 트랜잭션에 저장한다. 회원/Cart/쿠폰까지 포함한 전체 순서는 Order 통합에서 확인한다.

## 4. 수용 기준과 검증

| 대상 | 수용 기준 | 환경 |
|---|---|---|
| 등록/권한 | 신규 false, SKU 없이 상품 조회, 이미지 누락 거절, 타 판매자·삭제 상품 제어 거절 | 실제 보안 필터 HTTP·H2 |
| 제어/삭제 | 오래된 version 409, 동일 상태·삭제 재요청 이벤트 중복 없음, SKU 상태/수량·이미지 보존 | Product persistence/HTTP |
| 원자성 | Outbox 실패 시 기본정보·제어·삭제 모두 롤백 | H2 및 PostgreSQL |
| 거래 경합 | 공유 잠금끼리 공존, 중지/삭제가 잠금 뒤 완료, 먼저 완료된 중지/삭제·같은 컨텍스트의 오래된 일반 조회를 우회하지 못함 | 실제 PostgreSQL |
| 이행 | 기존 true·신규 false, 이미지 FK만 제거, 기존 제약 보존 | 실제 PostgreSQL migrationTest |
| 소비/공개 | 구형/중복/역순·삭제 사실 보존, 중지와 품절 구분, 삭제 공개 제외 | DTO 단위·H2·EmbeddedKafka, 실제 PostgreSQL 별도 |
| 연결 | 다중 SKU가 상품 페이지/건수를 중복하지 않음, 기존 주문 복구에 신규 판매 가드 미사용 | SKU/Cart/Order 선행 통합 후 |
| 문서 | MD/HTML 일치, 로컬 링크, 좁은 화면/필터 동작 | 정적 검사·Orca browser |

Java 21은 설치된 JDK를 명시해 사용한다. PostgreSQL에는 격리 DB의 COMMERCECORE_TEST_POSTGRES_URL/USER/PASSWORD와 CATALOG_TEST_POSTGRES_URL/USER/PASSWORD가 필요하다. 미설정·0건·스킵을 통과로 보고하지 않는다.

```bash
scripts/check.sh --base origin/dev --dry-run
scripts/check.sh --base origin/dev
./gradlew :services:commercecore-service:test --tests '*Product*Test'
./gradlew :services:commercecore-service:integrationTest --tests '*Product*Test'
./gradlew :services:commercecore-service:postgresTest --tests '*ProductPersistencePostgresTest'
./gradlew :services:commercecore-service:migrationTest --tests '*ProductSchemaPostgresTest'
./gradlew :services:catalog-service:test --tests '*ProductInfoEventMessageTest'
./gradlew :services:catalog-service:integrationTest --tests '*Product*Test'
./gradlew :services:catalog-service:postgresTest --tests '*ProductReplicaPostgresTest'
./gradlew :services:catalog-service:migrationTest --tests '*CatalogSchemaPostgresTest'
.venv-api/bin/python .github/scripts/api/check_structure.py
.venv-api/bin/python .github/scripts/api/check_events.py --samples
scripts/check-docs.sh
python3 scripts/render_design_docs.py --check
```

같은 검사를 의미 없이 반복하지 않고 변경·실패·미해결 우려에 맞춰 갱신한다. 외부 Kafka·PG/AWS·CI와 로컬 H2/EmbeddedKafka 결과를 구분한다.

## 5. 진행·납품

독립 구현과 회귀, Core V101/Catalog V2, MD/HTML을 선행 체크포인트로 묶는다. 기반 64598663과 API/경합 검증 보완 447b0056을 원격 전달했다. 전용 PostgreSQL 21건은 모두 통과했다. root Gradle build는 성공했지만 922건 중 5건 스킵으로 하네스는 incomplete이며 상세 결과는 워크로그를 따른다. 후속 SKU/Cart/Order 의존 통합은 각 소유 브랜치에서 수행한다.

최종 #333 PR은 하나다. 공유 계약·선행 포트·필요 검사가 미완료면 PR ready로 보고하지 않는다. 완료 조건이 남는 동안 active로 유지한다. FigJam API·ERD·상태 차이는 코디네이터에 전달하고 Product 담당은 직접 편집하지 않는다. 병합·배포는 허용 범위에 포함되지 않는다.
