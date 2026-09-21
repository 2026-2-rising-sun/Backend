# Commerce ERD · P1 기능 명세 매핑 & 설계 근거

> 대상 파일: [`src/main/resources/db/migration/V1__commerce_baseline.sql`](../src/main/resources/db/migration/V1__commerce_baseline.sql)
> 기능 명세: `docs/` 하위 P1 상세 기능명세 (Shopping · Live · Commerce)
> 실행 계획: [`.omc/plans/plan-p1-commerce-sprint2.md`](../../../../.omc/plans/plan-p1-commerce-sprint2.md)
> 요구사항: [`.omc/specs/deep-interview-p1-backend-sprint2-estimate.md`](../../../../.omc/specs/deep-interview-p1-backend-sprint2-estimate.md)
> 작성: 2026-09-20 (Sprint 2 Phase 0)

---

## 30초 요약 (TL;DR)

Commerce 도메인은 **판매(sales) · 재고(stock) · 주문(orders) · 결제(payment_attempt)** 4개 테이블로 구성한다. P1 명세의 판매 1~4 · 주문 1~5 · 결제 1~4 총 13개 기능 카드를 이 4 테이블이 모두 커버한다.

핵심 결정 세 줄로 요약:

1. **재고와 판매정보를 분리** — 관리자가 가격만 바꿀 때 재고 잠금이 걸리지 않게. 재고는 조건부 UPDATE 로만, 판매정보는 낙관적 락으로.
2. **상품명·단가는 주문 생성 시점에 스냅샷** — 나중에 Shopping/Commerce 값이 바뀌어도 기존 주문 영수증은 그대로.
3. **결제 시도는 별도 테이블 + `scheduled_resolve_at`** — 재기동으로 in-process 스케줄러가 유실돼도 DB 스캔으로 지연 결제 복구.

---

## 1. 도메인 지도

```
                                     ┌────────────────┐
                                     │  Shopping      │
                                     │  (다른 서비스) │
                                     │  productId ↓   │
                                     └─────┬──────────┘
                                           │  (REST · ShoppingClient)
                                           │  · 상품 존재 확인
                                           │  · 상품명 스냅샷
                                           │
   ┌──────────────────────┐   1 : 1   ┌────▼───────────┐
   │  sales_stock         │◄──────────│  sales_info    │
   │  · available         │  FK       │  · product_id  │
   │  · reserved          │           │  · price       │
   │  (조건부 UPDATE 로만)│           │  · status      │
   └──────────────────────┘           │  · version     │
                                      └────┬───────────┘
                                           │  1 : N
                                           │  FK
                                           ▼
                                      ┌────────────────────────┐
                                      │  orders                │
                                      │  · order_number        │
                                      │  · quantity            │
                                      │  · unit_price          │
                                      │  · product_name        │
                                      │      _snapshot         │
                                      │  · status              │
                                      │  · idempotency_key     │
                                      │  · expires_at          │
                                      │  · lookup_password     │
                                      │      _hash             │
                                      └────┬───────────────────┘
                                           │  1 : N
                                           │  FK
                                           ▼
                                      ┌────────────────────────┐
                                      │  payment_attempt       │
                                      │  · scenario            │
                                      │  · status              │
                                      │  · scheduled_resolve_at│
                                      └────────────────────────┘
```

- 화살표는 FK. `product_id` 만 예외적으로 FK 가 아니라 논리 참조 (module-boundary).
- 실제 서비스간 통신은 `commerce-service → Shopping (동기 REST)` 방향만.

---

## 2. P1 기능 카드 ↔ 스키마 매핑 인덱스

| 카드 | 명세 요지 | 주 사용 테이블 | 핵심 필드·규칙 |
|---|---|---|---|
| 판매 1 | 판매정보 최초 설정 | `sales_info` + `sales_stock` | `product_id UNIQUE` + Shopping 존재 확인 + 초기 재고 세팅 |
| 판매 2 | 가격 변경 | `sales_info` | `price` + `@Version` 낙관적 락. 기존 주문은 `unit_price` 스냅샷으로 보호 |
| 판매 3 | 재고 확인·수정 | `sales_stock` | 조건부 UPDATE. `reserved` 는 관리자 수정 대상 아님 |
| 판매 4 | 판매 시작·품절·비공개 | `sales_info.status` | CHECK 제약 + 조건부 UPDATE `WHERE status = :expected` |
| 주문 1 | 주문서 확인·입력 | `sales_info` + `sales_stock` (read) | 진입만으로는 재고 차감 안 함. 판매 상태·재고·가격 조회만 |
| 주문 2 | 주문 생성·재고 차감 | `orders` + `sales_stock` (write) | `idempotency_key UNIQUE`, 재고 조건부 UPDATE, 상품명·단가 스냅샷 |
| 주문 3 | 주문 결과·상세 조회 | `orders` + `payment_attempt` | `order_number` + `lookup_password_hash` 검증 |
| 주문 4 | 결제 전 취소 | `orders` + `sales_stock` | 조건부 UPDATE `WHERE status='PENDING_PAYMENT'` + 재고 복구 |
| 주문 5 | 미결제 만료·재고 회수 | `orders.expires_at` + `sales_stock` | 스케줄러 조건부 UPDATE + 재고 복구. `status`·`expires_at` 복합 인덱스 |
| 결제 1 | 결제 시작 | `payment_attempt` (INSERT) | 저장된 `total_amount` 만 사용. 화면 금액 신뢰 금지 |
| 결제 2 | 성공·실패 결과 처리 | `payment_attempt.status` + `sales_stock` | 조건부 UPDATE `WHERE status='PROCESSING' AND resolved_at IS NULL` |
| 결제 3 | 지연·응답 유실 상태 확인 | `payment_attempt.scheduled_resolve_at` | Reconciler DB 스캔. 인덱스 `(status, scheduled_resolve_at)` |
| 결제 4 | Mock 시나리오 반복 검증 | `payment_attempt.scenario` | `INSTANT_*`/`DELAYED_*` 4종 CHECK 제약. 결정적 재현 |

---

## 3. 테이블별 상세 근거

### 3.1 `sales_info` — 판매 조건

**책임:** Shopping 상품 하나에 대해 "얼마에 파는지, 지금 팔 수 있는 상태인지" 를 담당.

**컬럼 근거:**

- `product_id BIGINT UNIQUE NOT NULL`
  - **왜 FK 가 아닌가:** `product_id` 는 Shopping 서비스의 소유. Backend 의 `module-boundary-conventions` 가 서비스 간 컴파일 의존을 금지하므로 DB 레벨 FK 를 걸어도 `shopping-service` 스키마 파일을 `commerce-service` 에서 참조할 방법이 없다. Shopping DB 와 Commerce DB 는 Sprint 3 이후 논리적으로도 분리될 수 있으므로 (설계 문서 참고), FK 대신 **논리 참조 + `ShoppingClient` REST 호출 검증** 으로 간다.
  - 판매 1 완료 기준의 "상품이 없거나 존재 여부를 확인하지 못하면 설정을 완료하지 않는다" → `SalesRegistrationService` 가 `ShoppingClient.exists(productId)` 호출로 강제.
  - `UNIQUE` 는 판매 1 완료 기준의 "한 상품의 판매정보는 하나만 관리한다" 를 DB 계층에서 강제.

- `price BIGINT NOT NULL CHECK (price > 0)`
  - P1 명세 "기본 입력 제약" 의 "가격은 양의 정수 원 단위" 그대로. Java `Long` 매핑.
  - CHECK 제약을 DB 에서 걸어야 하는 이유: 판매 2 (가격 변경) 나 판매 1 (설정) 서비스 계층에서 검증을 놓쳐도 DB 가 마지막 방어선.

- `status VARCHAR(32) NOT NULL CHECK (status IN ('READY','ON_SALE','SOLD_OUT','PRIVATE'))`
  - 판매 4 완료 기준 표의 4 상태와 1:1.
  - **왜 Postgres ENUM 이 아닌가:** H2 는 Postgres `CREATE TYPE ENUM` 미지원. 통합 테스트를 H2 로 돌리기로 스펙에서 결정했으므로 VARCHAR + CHECK 로 통일.
  - **왜 CHECK 를 걸고 서비스 계층에서도 검증하는가:** DB CHECK 는 라인 위반 방어, 서비스 계층 검증은 상태 전이 규칙 (예: `SOLD_OUT → ON_SALE` 은 재고 조건 확인 후 허용) 을 담당. 두 레이어가 서로 다른 보장.

- `version BIGINT NOT NULL DEFAULT 0`
  - JPA `@Version` 매핑. 가격 변경 (판매 2) 처럼 동시 수정 시 마지막 갱신이 승리하는 대신 **재검증 후 재시도** 를 강제.
  - **왜 status 변경에도 낙관적 락이 붙나:** JPA 는 모든 업데이트에 `version` 을 반영하므로 status 조건부 UPDATE 를 `@Modifying` 네이티브로 별도 작성해 낙관적 락과 병존한다.

- `created_at`, `updated_at`
  - `common-persistence` `BaseEntity` 매핑용. 감사·로그·디버깅.

**빠진 것 (의도적):**

- `broadcast_id` 나 `seller_id` 는 없다 — P1 Live 는 상품을 방송에 노출만 하지 소유하지 않는다. 회원·판매자 도메인은 P2.
- `discounted_price` 나 방송 특가 — Non-Goal 로 스펙 명시.
- 삭제 (`deleted_at`) — 판매 4 완료 기준의 "삭제 대신 비공개" 그대로. `status = 'PRIVATE'` 로 소프트 딜리트를 흉내낸다.

---

### 3.2 `sales_stock` — 판매 재고

**책임:** 지금 팔 수 있는 재고와 이미 주문에 배정된 재고를 나눠서 관리. 초과 판매 방지와 취소·만료 시 복원.

**왜 `sales_info` 에 재고 컬럼을 추가하지 않고 별도 테이블로 분리:**

- **잠금 범위 분리.** 관리자가 가격만 변경 (판매 2) 하는 동안 재고 변경이 락 대기하면 안 된다. `sales_info` 는 낙관적 락, `sales_stock` 은 조건부 UPDATE 로 서로 다른 동시성 전략.
- **행 크기 최소화.** 재고는 초당 수십 번 UPDATE 될 수 있는 hot row. price·status 처럼 자주 안 바뀌는 필드와 같은 페이지에 두면 dead tuple 이 지나치게 늘어난다 (Postgres MVCC).
- **P1 완료 기준의 "이미 주문에 배정된 수량은 수정 대상에 포함하지 않는다"** (판매 3) 를 자연스럽게 표현. `available` 만 관리자 수정 대상, `reserved` 는 주문·결제 흐름 전용.

**컬럼 근거:**

- `sales_info_id BIGINT PK · FK`
  - `sales_info` 1:1 이므로 자체 `id` 없이 부모의 PK 를 그대로 사용. 1:1 관계의 표준 관용구.

- `available INT NOT NULL DEFAULT 0 CHECK (available >= 0)`
  - "지금 살 수 있는 수량". 판매 3 관리자 수정 대상.
  - CHECK 는 조건부 UPDATE 실수 (음수 될 때) 를 2 차 방어.

- `reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0)`
  - "결제 대기 중" 수량. 주문 2 에서 `available -= qty, reserved += qty`. 결제 성공 시 `reserved -= qty`, 실패·취소·만료 시 `reserved -= qty, available += qty`.

**동시성 시나리오 대응:**

P1 통합 완료 기준의 "재고 5개에 수량 1개 주문 10건 동시 요청" 시 성공은 최대 5건. 이걸 보장하는 근거가 `WHERE available >= :qty` 조건부 UPDATE:

```sql
UPDATE sales_stock
   SET available = available - :qty,
       reserved  = reserved  + :qty,
       updated_at = now()
 WHERE sales_info_id = :id
   AND available >= :qty;
```

- Postgres 는 행 단위 `X-lock` 을 자동으로 잡는다. `available >= :qty` 가 FALSE 인 순간 UPDATE 대상 행이 0 이 되고, 서비스 계층은 `updated rows == 0` 을 재고 부족 (409) 으로 판단한다.
- 자바 `if (available >= qty)` 는 절대 사용하지 않는다 — check-then-act 갭에서 초과 판매 발생.

**만약 재고를 하나 컬럼 (`stock`) 으로만 관리하면:**

- 판매 3 "이미 주문에 배정된 수량은 수정 대상에 포함하지 않는다" 를 표현할 방법이 없다. `stock` 이 30 인데 그중 20 이 결제 대기 중이면 관리자가 볼 때 "왜 30 인데 팔면 안 되지?" 라는 모순이 생긴다.
- 취소·만료 복구 시 어느 것이 원래 판매 재고이고 어느 것이 배정 취소분인지 구분할 수 없어 감사·통계가 불가능.

---

### 3.3 `orders` — 주문

**책임:** 비회원 주문 하나를 표현. 주문 시점의 조건 (상품명·단가·수량·금액) 을 스냅샷으로 보존하고 결제 진행 상태를 추적.

**컬럼 근거 (핵심만):**

- `order_number VARCHAR(64) UNIQUE NOT NULL`
  - P1 완료 기준 "주문번호를 통한 주문 결과 조회" — 외부 노출 식별자. DB PK (`id`) 는 외부에 노출하지 않아 스캔·enum 공격 방지.
  - 포맷 예: `OD-20260920-000123`. 값 생성은 서비스 계층 (`OrderNumberGenerator`) 담당.

- `sales_info_id BIGINT NOT NULL FK`
  - 어느 판매정보에 대한 주문인지. FK 로 `sales_info` 삭제 방지 (Non-Goal 에도 물리적 삭제 없음 명시).

- `quantity`, `unit_price`, `total_amount`
  - 세 값을 모두 저장 — `total_amount = unit_price × quantity` 를 서비스 계층이 계산해 저장하되, DB 에는 셋 다 쓰고 CHECK 제약으로 양수 강제. 나중에 discount 등 파생 값이 추가되면 total 이 별도 계산식이 되므로 컬럼으로 두는 것이 확장에 유리.

- `product_name_snapshot VARCHAR(255) NOT NULL`
  - **주문 2 완료 기준** "주문 당시 상품명·단가·수량·총액과 주문번호를 보존" 그대로.
  - 상품 3 (상품명 수정) 이 실행되어도 이 스냅샷은 유지 → 상품 3 완료 기준의 "이미 생성된 주문의 상품명과 금액은 그대로다".
  - 상품명이 길어질 수 있으니 VARCHAR(255) (P1 스코프는 이걸로 충분).

- `status VARCHAR(32) NOT NULL CHECK IN (...)`
  - 6개 상태: `PENDING_PAYMENT`, `PAYMENT_CONFIRMING`, `PAID`, `FAILED`, `CANCELLED`, `EXPIRED`. Plan Section 3 Phase 3 명세와 일치.
  - **왜 6개인가:** 명세의 "주문 상태와 허용 동작" 표의 5개 + 만료 (`EXPIRED`) 는 주문 5 보완 제안의 상태. `EXPIRED` 는 스케줄러 조건부 UPDATE 대상 값.

- `buyer_name`, `buyer_phone`
  - 비회원 주문 필수 입력. P1 명세 주문 1 그대로.

- `lookup_password_hash VARCHAR(255) NOT NULL`
  - **주문 확인 방법 제안 기준:** 주문번호 + 조회 비밀번호. bcrypt hash 로 저장 (평문 저장 절대 안 됨).
  - VARCHAR(255) 는 bcrypt 가 60자를 쓰는 것에 여유. 미래에 다른 hash 로 마이그레이션할 여지.
  - 주문 3 "주문번호와 함께 주문 조회·결제·취소에 사용" 을 위해 컨트롤러가 `X-Order-Password` 헤더로 원문을 받고, 서비스 계층이 `BCryptPasswordEncoder.matches()` 로 비교.

- `idempotency_key VARCHAR(64) UNIQUE (nullable)`
  - **주문 2 완료 기준** "같은 주문 동작의 재전송은 기존 주문 결과로 연결한다" 를 DB 레벨에서 강제.
  - 클라이언트가 헤더 `X-Idempotency-Key` 로 UUID 등을 보내면 서비스 계층이 이 컬럼에 저장. 두 번째 요청은 UNIQUE 위반으로 실패하고, 예외 처리기가 기존 주문을 조회해 200 으로 응답.
  - **왜 nullable:** 멱등키 없이 오는 요청도 허용 (초기 클라이언트 구현 여지). Postgres UNIQUE 는 NULL 을 중복 허용하므로 안전.

- `expires_at TIMESTAMPTZ (nullable)`
  - **주문 5 보완 제안** — 결제를 시작하지 않은 결제 전 주문의 유효시간. 스케줄러가 이 컬럼 + `status = 'PENDING_PAYMENT'` 조건으로 만료 대상 스캔.
  - nullable: 만료 규칙 자체가 아직 팀 합의 사항. 미채택 시 항상 NULL 로 두면 스케줄러가 스캔 후 대상 없음으로 무해.

- `cancelled_at TIMESTAMPTZ (nullable)`
  - **주문 4** 결제 전 취소된 시점. status 만으로도 취소 여부는 알 수 있지만 취소 시각 자체를 감사·통계에 남긴다.

- `version BIGINT NOT NULL DEFAULT 0`
  - `orders` 는 여러 서비스가 동시 수정할 여지 (결제 시작 + 취소 요청 동시). 낙관적 락 병용.

**빠진 것 (의도적):**

- `member_id` — P1 은 비회원. P2 Sprint 3 에서 추가.
- 배송 관련 (`shipping_address` 등) — Non-Goal.
- `payment_method` — Mock 결제라 결제 수단 개념 없음.
- `broadcast_id` — 방송 경유 주문이더라도 주문 자체는 Live 에 의존하지 않는다는 명세 원칙. 방송·주문의 상관관계는 Live 서비스가 자체 관리.

---

### 3.4 `payment_attempt` — 결제 시도

**책임:** 한 주문에 대해 여러 번 시도될 수 있는 결제의 각 회차를 기록. Mock 결제의 시나리오·상태·예약 시각을 저장.

**왜 `orders` 안에 결제 상태를 통합하지 않고 별도 테이블로:**

- 한 주문에 대한 재시도 (실패 → 새 시도) 를 표현하기 위해. 결제 2 완료 기준의 "실패·취소된 주문을 되살리지 않고 새 주문으로 구매" 는 Order 를 새로 만든다는 뜻이지만, **결제 시도** 자체는 여러 번 남길 수 있다 (예: 지연 결과 확인 요청도 시도 이력에 남을 수 있음).
- 결제 3 "결과 지연·응답 유실 후 상태 확인" 을 위한 감사 로그 성격.
- Sprint 3+ 에서 실제 PG 도입 시 자연스러운 확장 지점.

**컬럼 근거:**

- `order_id BIGINT NOT NULL FK`
  - 어느 주문의 시도인지.

- `scenario VARCHAR(32) NOT NULL CHECK IN (...)`
  - **결제 4** Mock 시나리오 4종. 개발용 컨트롤러 `PaymentScenarioController` 가 주문별로 지정 → 결제 시작 시 이 컬럼에 고정 → 재확인·재기동 후에도 시나리오 유지 (결제 4 완료 기준의 "결제를 시작한 뒤에는 임의로 결과를 변경하지 않는다").

- `status VARCHAR(32) NOT NULL CHECK IN (...)`
  - `PENDING` (요청 접수 직후, 매우 짧게 존재)
  - `PROCESSING` (in-process 스케줄러 예약 중, 또는 확정 대기)
  - `SUCCESS`, `FAILED` (확정)
  - `TIMEOUT` (Reconciler 가 최대 대기 초과로 확정)

- `requested_at TIMESTAMPTZ NOT NULL`
  - 결제 1 "저장된 주문 금액으로 결제를 시작한다" 시점. Reconciler 가 `now() - requested_at > max_wait` 로 타임아웃 판단 가능.

- `resolved_at TIMESTAMPTZ (nullable)`
  - **결제 2 완료 기준** "성공과 실패를 각각 재현하고 주문 조회에서 같은 결과를 확인한다" — 확정 시점을 남겨야 재확인 idempotent 비교 (status + resolvedAt 튜플) 가 가능.

- `scheduled_resolve_at TIMESTAMPTZ (nullable)`
  - **결제 3 · Architect 지적 반영.** `DELAYED_*` 시나리오에서 확정 예약 시각. In-process `ScheduledExecutorService` 로 예약해 두고 이 컬럼에도 저장 → 앱이 재기동되면 스케줄러 유실 → `PaymentDelayReconciler` (`@Scheduled`) 가 `status = 'PROCESSING' AND scheduled_resolve_at < now()` 조건으로 DB 스캔 → 시나리오 결과로 확정.
  - **왜 nullable:** `INSTANT_*` 시나리오는 예약 없음.

- `version BIGINT NOT NULL DEFAULT 0`
  - 낙관적 락. 다만 실제 상태 전이는 조건부 UPDATE `WHERE status = 'PROCESSING' AND resolved_at IS NULL` 이 primary 방어이고 `@Version` 은 JPA 무의식 UPDATE 방어 보조.

---

## 4. 인덱스 근거

| 인덱스 | 쓰이는 쿼리 | 카드 |
|---|---|---|
| `uk_sales_info_product_id` | 판매정보 최초 등록 시 상품 중복 검증 | 판매 1 |
| `uk_orders_order_number` | 사용자가 `GET /v1/orders/{orderNumber}` 조회 | 주문 3, 결제 1 |
| `uk_orders_idempotency_key` | 재전송 중복 주문 방지 | 주문 2 |
| `ix_orders_status_expires_at` | `WHERE status='PENDING_PAYMENT' AND expires_at < now()` 만료 스캔 | 주문 5 |
| `ix_orders_sales_info_status` | 판매 상태 변경 시 pending 주문 카운트 (`WHERE sales_info_id=? AND status='PENDING_PAYMENT'`) | 판매 4, 판매 3 |
| `ix_payment_attempt_order_id` | 주문 결과 조회 시 결제 이력 함께 로드 | 주문 3 |
| `ix_payment_attempt_status_scheduled` | `WHERE status='PROCESSING' AND scheduled_resolve_at < now()` Reconciler 스캔 | 결제 3 |

**왜 부분 인덱스 (`WHERE status='PROCESSING'`) 를 안 쓰나:**

- Postgres 는 지원하지만 H2 미지원. 통합 테스트를 H2 로 하기로 결정한 이상 두 엔진 모두 동작하는 스키마가 필요.
- 대신 status 를 인덱스 첫 컬럼으로 두어 카디널리티 낮은 필터를 앞에서 선별. 실무 성능은 Sprint 3+ 에서 재측정.

---

## 5. 상태 CHECK 값 근거

### `sales_info.status` 4 값

| 값 | P1 명세 표 매핑 | 신규 주문 허용 |
|---|---|---|
| `READY` | 판매 준비 | 불가 |
| `ON_SALE` | 판매 중 | 가능 (수량 검증 후) |
| `SOLD_OUT` | 품절 | 불가 |
| `PRIVATE` | 비공개 | 불가 |

전이 규칙 (판매 4 완료 기준 표 그대로):

- `READY → ON_SALE`: 필수 상품정보 + 판매 가능 재고 확인 후
- `ON_SALE → SOLD_OUT`: 판매 가능 재고가 0 일 때 자동, 또는 관리자 명시적 중단
- `SOLD_OUT → ON_SALE`: 재고 복구·추가
- 어느 상태 → `PRIVATE`: 관리자 직접 지정 (소프트 딜리트)

### `orders.status` 6 값

- `PENDING_PAYMENT` — 주문 생성·재고 확보 완료, 결제 시작 전
- `PAYMENT_CONFIRMING` — 결제 시작, 결과 미확정
- `PAID` — 결제 성공 확정
- `FAILED` — 결제 실패 확정, 재고 복구됨
- `CANCELLED` — 결제 전 사용자 취소, 재고 복구됨
- `EXPIRED` — 미결제 만료, 재고 복구됨 (주문 5 채택 시)

**허용 전이 (명세 "주문 상태와 허용 동작" 표):**

```
PENDING_PAYMENT ──┬─→ PAYMENT_CONFIRMING → { PAID | FAILED }
                  ├─→ CANCELLED  (주문 4)
                  └─→ EXPIRED    (주문 5, 채택 시)
```

전이 자체는 서비스 계층에서 각 상태별 조건부 UPDATE `WHERE status = :expected` 로 강제.

### `payment_attempt.status` 5 값

- `PENDING`: 시도 레코드 생성 직후. 실제로는 `PROCESSING` 로 바로 넘어가지만 로그·감사 지점.
- `PROCESSING`: 확정 대기
- `SUCCESS`, `FAILED`, `TIMEOUT`: 확정

### `payment_attempt.scenario` 4 값

- `INSTANT_SUCCESS` — 즉시 성공 응답
- `INSTANT_FAIL` — 즉시 명시 실패 응답
- `DELAYED_SUCCESS` — 200ms 후 성공 (스케줄러 예약)
- `DELAYED_FAIL` — 200ms 후 실패

**왜 결정적 4 종만:** 결제 4 완료 기준 "매번 무작위 결과를 내어 테스트가 달라지는 방식은 사용하지 않는다".

---

## 6. 트랜잭션 경계와 동시성 시나리오

### 6.1 재고 5개 · 10건 동시 주문 (주문 2 통합 테스트)

**단계:**

```
1. (트랜잭션 밖) Shopping fetch — 상품명·현재 노출 정보
2. (트랜잭션 밖) idempotency_key UNIQUE 확인
3. BEGIN
4.   UPDATE sales_stock SET available -= :qty, reserved += :qty
        WHERE sales_info_id = :id AND available >= :qty
     (row 0 → 재고 부족 409, 트랜잭션 롤백)
5.   INSERT INTO orders (product_name_snapshot 포함, status='PENDING_PAYMENT')
6. COMMIT
```

10건이 동시에 4~5 단계에 진입해도 Postgres 는 행 단위 X-lock 을 순차 획득. `available >= :qty` 가 FALSE 인 요청부터 UPDATE 대상 0 이 되어 재고 부족 응답. **최대 성공 = 재고 크기.**

### 6.2 취소 · 결제 시작 동시 요청 (주문 4)

동일 주문에 대한 두 요청이 동시 도달:

- 결제 시작: `UPDATE orders SET status='PAYMENT_CONFIRMING' WHERE id=? AND status='PENDING_PAYMENT'`
- 취소:     `UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING_PAYMENT'`

둘 다 `WHERE status='PENDING_PAYMENT'` 조건 → **한쪽만 성공, 다른 쪽은 row 0 → 409.**

취소가 이긴 경우 재고 복구 (`available += qty, reserved -= qty`) 도 조건부 UPDATE (`WHERE reserved >= :qty`) 로 idempotent 보장.

### 6.3 결제 지연 재기동 (결제 3, `PaymentRebootTest`)

시나리오: `DELAYED_SUCCESS` 로 결제 시작 → 200ms 예약 → JVM 죽음 → 재기동.

**전개:**

1. 재기동 시 in-process `ScheduledExecutorService` 는 초기화 상태 (예약 유실).
2. `payment_attempt` 는 `status='PROCESSING', scheduled_resolve_at = t+200ms, resolved_at = NULL` 상태로 DB 에 존재.
3. Spring 부팅 후 `PaymentDelayReconciler @Scheduled(fixedDelay=10s)` 가 10초 안에 스캔:
   ```sql
   SELECT ... FROM payment_attempt
    WHERE status = 'PROCESSING'
      AND resolved_at IS NULL
      AND scheduled_resolve_at < now();
   ```
4. 해당 시도의 `scenario` 를 보고 결과 결정 (`SUCCESS`).
5. 조건부 UPDATE `WHERE status='PROCESSING' AND resolved_at IS NULL` 로 전이. `affected rows > 0` 일 때만 downstream (재고 소진 확정 = `reserved -= qty`) 트리거.

**In-process 스케줄러가 살아 있는데 Reconciler 가 먼저 이겼다면:** downstream 은 여전히 한 번만. Reconciler 성공 후 in-process 스케줄러가 도착하면 `WHERE status='PROCESSING'` 이 이미 FALSE → row 0 → 스킵.

### 6.4 만료 스캐너 재실행 (주문 5)

동일 주문 만료 시각 도달 시 스케줄러가 동시 다중 실행되면 (미래 다중 인스턴스):

```sql
UPDATE orders
   SET status = 'EXPIRED', cancelled_at = now()
 WHERE id = :id
   AND status = 'PENDING_PAYMENT'
   AND expires_at < now();
```

**첫 UPDATE 만 성공, 나머지 row 0.** downstream (재고 복구) 도 조건부라 idempotent.

Sprint 2 는 단일 인스턴스라 이 시나리오가 실전에 없지만, 스키마·쿼리는 미리 안전하게 구성.

---

## 7. 감사·인프라 컬럼

| 컬럼 | 어디에 있나 | 왜 |
|---|---|---|
| `created_at`, `updated_at` | 모든 테이블 | `common-persistence` `BaseEntity` 매핑. 감사·디버깅·통계. `Instant` (UTC) |
| `version` | `sales_info`, `orders`, `payment_attempt` | JPA `@Version` — 개발자가 실수로 서비스 계층에서 `find → mutate → save` 흐름을 쓸 때 동시 수정 감지 |

`sales_stock` 에 `version` 이 없는 이유: **조건부 UPDATE 만이 유일한 갱신 경로** 로 강제할 예정이므로 낙관적 락은 오히려 방해. 서비스 계층에서 `find → save` 를 못 쓰게 코드 리뷰로 강제.

---

## 8. 앞으로 (P2 이후) 확장 지점

이 스키마가 다음 스프린트에 어떻게 진화할지, 미리 여지를 두어 마이그레이션 비용을 낮춘다:

| 향후 요구 | 예상 스키마 변경 |
|---|---|
| 회원 로그인 (Sprint 3 P2) | `orders.member_id BIGINT NULL FK` 추가. NULL 은 비회원 |
| 장바구니 (P2) | 새 테이블 `carts`, `cart_items`. `orders.cart_id` 는 두지 않고 주문 시 스냅샷만 |
| 다상품 주문 (P2) | 새 테이블 `order_items`. 기존 `orders.sales_info_id`·`quantity`·`unit_price` 는 첫 아이템 요약으로 유지 (호환), 정식은 `order_items` |
| 실제 PG 결제 (P3) | `payment_attempt` 에 `external_transaction_id`, `provider` 컬럼 추가 |
| 방송 특가 / 쿠폰 (P3) | `orders.discount_amount`, `orders.coupon_snapshot_json` 추가 |
| 판매정보 이력 (audit) | 별도 `sales_info_history` 테이블 append-only |

**중요 원칙:** 기존 컬럼은 변형·삭제 지양. NULLable 추가로 확장.

---

## 9. 검증 방법 (Sprint 2 verification 매핑)

계획 §8 검증 단계를 스키마 관점에서 재확인:

- **로컬 부팅** (`bootRun`) → Flyway 가 이 파일을 실행. 실패 시 로그에 원인 노출
- **`SalesStockConcurrencyTest`** → `WHERE available >= :qty` 조건부 UPDATE 검증. 재고 5·10 스레드·3 반복
- **`OrderCancellationConcurrencyTest`** → `WHERE status='PENDING_PAYMENT'` 조건부 UPDATE 검증. 취소 + 결제 동시
- **`OrderCreationConcurrencyTest`** → 재고 5·10건 동시 POST → 성공 최대 5. idempotency_key 없이 재전송 → 다른 주문번호 (허용). idempotency_key 있으면 → 첫 요청만 성공, 두 번째는 기존 주문 반환
- **`PaymentRebootTest`** → `scheduled_resolve_at` 컬럼 활용 검증. 재기동 후 Reconciler 확정
- **`OrderFlowIntegrationTest`** (통합검증 1) → 4 테이블 모두 일관성 검증 (판매 등록 → 주문 → 결제 → 조회 e2e)

---

## 10. 왜 이 문서를 남기나

- 정수·준제·종민이 월요일 아침에 이 문서 하나 훑고 "왜 컬럼이 이렇게 나뉘어 있지?" 답을 찾을 수 있게.
- Sprint 3+ 에서 회원·장바구니·다상품·실제 PG 로 확장할 때 원래 결정의 근거를 되돌아볼 수 있게 (rationale 공유).
- `Backend/docs/decisions/` 아래 ADR 파일을 추가하지 않는 이번 스프린트 원칙을 지키면서도, 서비스 로컬 (`services/commerce-service/docs/`) 에는 이해 자료를 남긴다.

작성 후 팀 슬랙 백로그 스레드에 링크 공유 예정.
