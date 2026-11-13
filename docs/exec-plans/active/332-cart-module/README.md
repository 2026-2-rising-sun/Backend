# #332 Cart 모듈 구현 계획

상태: 진행 중 · 코드 작성·선행 통합·검증 수행 중

자료: [설계](../../../design-docs/commercecore/cart/설계.md) · [ADR](../../../adr/0332-cart-sku-boundary.md) · [ERD MD](../../../design-docs/commercecore/cart/ERD.md) · [ERD HTML](../../../design-docs/commercecore/cart/ERD.html) · [워크로그](../../../worklog/2026-11-13.md) · [이슈 #332](https://github.com/2026-2-rising-sun/Backend/issues/332) · [정리 정책 #340](https://github.com/2026-2-rising-sun/Backend/issues/340)

## 권한과 현재 기준

2026-11-13 사용자가 코드 구현과 MD/HTML 보완을 요청했고 코디네이터가 이 동일 세션의 Sol Medium 적용을 확인해 구현 Task task_496cea38c3e7를 배정했다. 이전 설계 Task의 failed 판정과 당시 기록은 과거 사실로 보존하되 현재 구현을 중단시키는 지시로 재사용하지 않는다. 모듈별 커밋·푸시·PR은 허용됐으며 dev 병합·배포는 범위 밖이다.

기반 cc91c09b에서 검증된 첫 포트 b3091eae를 커밋했다. 패키지 이동/SKU 연결 후속 코드는 e84a03c7 체크포인트로 보존하고 SKU 정확 SHA 0b36ba4722aea963b092e87a1f6af4145e920af3를 merge했다. 코디네이터의 후속 의존 조정 승인으로 Product 64598663을 fc5e6be5로 직접 merge했다. 후속 SKU SHA도 merge해 PR base 차이를 정리한다. 미커밋 파일 복사·cherry-pick·rebase/force push는 하지 않는다.

공통 지시의 파일 소유권 예외로 공유 소비자 11개 파일의 import/fully-qualified type 변경만 Cart 브랜치에서 적용·검증·커밋하도록 코디네이터가 승인했다. [변경 조각](order-import-compat.patch)의 업무 로직·상태·환불 DTO 변경은 없으며 Order 소유권을 유지한다.

## 목표와 범위

Cart를 cart 패키지로 분리하고 SKU별 항목·수량·소유권을 관리한다. Product별 묶음과 현재 SKU 정보를 조회하며 담기·조회는 예약하지 않는다. 선택 항목을 엄격히 검증·정렬 잠금하는 포트와 결제 성공 뒤 기존 버전으로 조건부 삭제하는 포트를 Order에 제공한다.

기존 Cart/Order/환불 ID·version 출처를 보존한다. #340의 새 편집 제한·새 연결 테이블·별도 Cart 상태는 도입하지 않는다. 신규 판매 준비 정책은 Product sale_enabled=false·SKU READY이고 기존 상품 상태는 유지한다.

사용자 후속 환불 U1은 4,000원×3/쿠폰2,000/실결제10,000의 순차 환불 4,000→4,000→2,000과 전량 후 쿠폰복구로 확정됐고 비례배분 제안은 폐기됐다. U2의 쿠폰 한 장/선택 항목 하나·누적 전량 시 원래 유효기간 복원, U3의 결과 대기 중 즉시 취소 제한·10분 만료도 확정됐다. 금액·환불·쿠폰·취소 상태는 Order 소유이며 Cart는 별도 계산/상태를 추가하지 않는다.

## 단계와 체크포인트

- [x] 브랜치·dirty 보존, dev ff-only 기준 확인. git pull의 rebase 설정 충돌은 pull.rebase=false와 ff-only로 변경 없이 처리.
- [x] 선택/정리 포트 독립 기반 작성·검증·커밋 b3091eae: H2 JPA 7개 실패/오류/스킵 0.
- [x] 후속 SKU 참조와 미매핑 보존을 보강한 단계의 H2 8개 실패/오류/스킵 0. 이후 최종 패키지/DTO 변화는 재검증 대상.
- [x] Cart 패키지/CRUD/상품별 그룹/최종 선택 DTO/nullable V103 코드와 테스트 작성. 공유 import-only 패치 적용.
- [x] ERD.md와 독립 ERD.html 작성. 자체 HTML 검사와 390px 페이지 폭/도표 내부 스크롤 확인.
- [x] SKU 0b36ba47과 Product 64598663을 통합하고 실제 포트/단순조회 DTO와 코드 대조.
- [x] Cart 단위13/H2통합20/실제PG잠금12/Flyway이행3 실패·오류·스킵0. 전체 Core 단위165 통과.
- [ ] 기존 단일 SKU 주문·결제/환불 호출자와 데이터/권한 회귀 확인. 기존 Sales 경로 전환의 선행 한계 분리.
- [ ] Core YAML 최종 편집자 Order가 [Cart API 조각](cart-api-fragment.yaml)을 통합하고 생성 API/소비자 의미를 검증.
- [x] 코드·실행 결과와 MD/HTML·현행 Core 설명을 맞추고 1280/390px 일반 viewport 화면·링크 확인.
- [ ] Cart PR 한 개를 SKU 브랜치 base로 전달. 계약/연계 미완료면 완성 PR로 보고하지 않음.

## 포트와 소유 파일

| 제공자 | 실제 타입·계약 |
|---|---|
| Cart | CartSelectionPort.readOwned/lockOwned(String,List<CartSelection>) → List<SelectedCartItem>. 선택 결과는 itemId/skuId/productId/quantity/version |
| Cart | lockForCompletion(String,List<Long>) → void. 이미 삭제·변경된 행 허용 |
| Cart | deletePurchased(String,List<CartSelection>) → int. 원본 ID·회원·version만 삭제 |
| SKU | SkuService.findBySkuIds(Collection<Long>), findByProductId(Long) → List<SkuView>. 단순 조회는 예약/거래 잠금 없음 |
| Product | ProductLookup.findProducts, ProductSnapshot.saleEnabled 단순 조회. 거래 검증 잠금 포트는 Cart 조회에서 호출하지 않음 |
| Order | 공유 생성/결과/환불 서비스·재진입·Core API YAML 최종 통합. Cart는 import-only 예외 외 업무 로직 수정 안 함 |

Cart의 strict 잠금·완료 잠금·성공 삭제는 Propagation.MANDATORY다. readOwned는 읽기 전용이고 Cart CRUD 수량 변경/삭제는 본인 행 잠금을 유지한다. 선택 개수는 기존 API와 같은 1~100, 중복 ID/잘못된 버전은 거절한다. Order는 Cart → 전체 Product 정렬 공유 잠금 → SKU 정렬 잠금/예약으로 연결하며 회원/주문/쿠폰 보호도 자기 흐름에서 조정한다.

V103은 sales_info.product_id→sales_info.id=sku.id로만 연결하고 미매핑 행을 product_id+NULL sku_id로 보존한다. 회원+SKU 유일성과 미매핑 회원+상품 부분 유일 인덱스를 두며 기존 ID·version·감사 시각을 바꾸지 않는다. 독립 Cart enum/상태관리도는 필요 없다.

## 검증 명령과 환경

```bash
scripts/check.sh --dry-run
scripts/check-docs.sh
scripts/render-api-docs.sh --check
python3 scripts/render_design_docs.py --check
./gradlew :services:commercecore-service:spotlessCheck
./gradlew :services:commercecore-service:test --tests '*Cart*Test'
./gradlew :services:commercecore-service:integrationTest --tests '*CartSelectionServiceTest' --tests '*CartApiIntegrationTest' --tests '*CartOrderIntegrationTest'
./gradlew :services:commercecore-service:postgresTest --tests '*CartSelectionPostgresTest'
./gradlew :services:commercecore-service:migrationTest --tests '*CartSkuMigrationPostgresTest'
```

명령에 설치된 JDK 21의 JAVA_HOME/PATH를 지정한다. API 검사 의존성은 워크트리 .venv-api에 requirements.txt의 고정 버전으로 설치했다. 전역 설정이나 하네스 소스는 변경하지 않았다.

PostgreSQL은 Cart 전용 임시 컨테이너/DB를 준비했고 테스트는 자기 랜덤 스키마만 생성·정리한다. 실제 연결로 행 잠금 대기·거래 롤백, V100→V102→V103 데이터 보존/미매핑/다중 SKU/부분 인덱스를 검사한다. H2 결과를 PostgreSQL 증거로 대체하지 않는다. 외부 Kafka·PG·AWS·CI 미실행도 명시한다.

독립 ERD HTML은 기존 프로젝트 문서 색상·시스템 폰트를 사용한 로컬 문서다. 도표 가로 스크롤은 내부 컨테이너에만 둔다. full-screenshot의 긴 화면 출력과 일반 screenshot·DOM 폭 검증을 구분하며 실제 본 화면만 검증 기록으로 남긴다.

## 전달과 남은 일

의존 PR은 Product→dev, SKU→Product, Cart→SKU, Order→Cart 순서이며 네 개를 유지한다. 검증된 정확 SHA를 merge해 ancestry를 보존하고 워크로그 충돌에서는 각 모듈 기록을 모두 남긴다. FigJam API·ERD·상태 표현은 코디네이터 단독 편집, Cart는 구현/API 근거만 전달한다.

현재 상품별 GET 응답은 기존 평면 배열과 달라지고 POST는 SKU ID와 단일 SKU productId 호환을 제공한다. 계약 원본/생성 HTML은 아직 Order 통합 대기이며 이 상태는 PR ready가 아니다. 검증된 범위·후속 SHA·실행 결과는 워크로그와 감독 Task 보고에 갱신한다. 필수 연계·검증이 남아 있어 계획은 active에 둔다.

## 최종 검증 체크포인트

53af15c7을 원격 Cart 브랜치에 푸시하고 Order에 전달했다. 전체 Core 통합298개 중30개 실패·외부 Kafka2개 스킵이다. PaymentGroupIntegrationTest27, CartCouponPreviewApiTest1, SalesInfoEventIntegrationTest1, MemberCommerceCoreApiTest1에서 Sales-only fixture의 SKU 누락/H2 테이블 누락이 드러났다. 운영 신규 Sales 쓰기에서도 SKU 동기화가 빠지면 Cart의 기존 productId 호환이409가 되므로 단순 fixture 수정만으로 전환 완료라고 하지 않는다. 코디네이터가 소비자 fixture 수정·재검증을 Order에 배정했다.

Spotless·문서123개·생성 API HTML6개/설계 HTML28개 최신성은 통과했다. 이전 API 구조 실패는 Product 후속447b0056 통합으로 해소됐다. Cart GET 응답 스키마 변경은 여전히 Order의 최종 원본 통합 대기다. root build·외부 Kafka/PG/AWS·CI는 미검증으로 유지한다.

### Product 승인 API 후속 통합

코디네이터 승인으로 Product `447b0056902dd0bac4262c113f8c93a8653ce251`을 `22289dbf`로 merge했다. 충돌은 워크로그 한 곳으로 양쪽 기록을 모두 보존했다. Cart 원본 API 경로는 이전과 의미가 동일함을 YAML 구조 비교로 확인했고 신규 SKU 조회 조각은 중복 적용하지 않았다. Cart 단위13/H2통합20·Spotless 재검증 통과, API 경로 구조 검사·문서124개·생성 API HTML6/설계HTML28 최신성 통과다. 이 병합 후 실제 PG/전체Core 회귀는 재실행하지 않았으며 이전30실패·Sales 원본전환 미완료가 해소됐다고 보지 않는다.
