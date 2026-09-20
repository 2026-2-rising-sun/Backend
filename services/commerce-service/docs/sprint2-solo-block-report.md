# Sprint 2 SoloBlock 종합 리포트

> 대상: 이 리포트를 열어 볼 팀원 (정수 · 준제 · 종민) · 리뷰어 · 미래의 나
> 범위: 정 담당 Sprint 2 SoloBlock 10장 (판매 2·3·4 · 주문 3·4·5 · 결제 1·2·3·4 + Shopping stub) 완결 시점의 스냅샷
> 저장소: `2026-2-rising-sun/Backend` · 브랜치 `feat/#35` 및 스택
> 작성 시각: 2026-09-20 저녁

---

## 30초 요약 (TL;DR)

**뭐를 다 만들었나:** `commerce-service` 안에서 판매 도메인 3장 · 주문 도메인 3장 · 결제 Mock 도메인 4장 = 10장 + Shopping 어댑터 stub. 총 PR 13개 (선행 Flyway/스키마 · 패키지 뼈대 포함). 70+ 테스트 all green.

**어떻게 다른 서비스와 이어지나:** 이번 스프린트에서 `commerce-service` 는 오직 `shopping-service` 와만 상호작용한다. 그것도 실제 REST 는 아직 없고 `ShoppingClient` **인터페이스 + in-memory stub** 로만. 화요일 준제와 페어링해서 `HttpShoppingClient` 실 구현으로 스왑 예정.

**정수(월요일 오전)가 이어받아야 하는 것:** 판매 1 (판매정보 최초 설정) · 주문 1 (주문서 확인·입력) · 주문 2 (주문 생성 + 재고 차감). 세 카드 모두 `ShoppingClient` 를 통해 상품 정보를 참조하므로 stub 을 그대로 사용해 개발 가능.

**핵심 안전 장치 3가지:**
1. **조건부 UPDATE** — 재고·주문·결제 상태 전이를 자바 `if` 가 아니라 SQL WHERE 로 검증. 초과 판매 · 이중 취소 · 이중 결제 근본 차단.
2. **낙관적 락 `@Version`** — 관리자 편집 lost update 방어 (판매 정보 · 주문 상태).
3. **Reconciler DB-scan fallback** — Mock 결제 in-process 스케줄러가 재기동으로 유실돼도 DB 스캔으로 복구.

**아직 못 한 것 (완료 판정 후속):**
- Shopping 실제 REST 연동 (화 오전 준제 페어링)
- 회원·장바구니 (Sprint 3 P2)
- Postgres Testcontainers 이관 (Sprint 3+)
- 일부 CANCELLED · EXPIRED 시점 컬럼 명명 개선 (Sprint 3)

---

## 1. 큰 그림 · 어디가 뭘 하나

```
                          ┌───────────────────────────┐
                          │   Frontend / Admin UI     │
                          └────────────┬──────────────┘
                                       │  REST /api/commerce/…
                                       ▼
                              ┌───────────────────┐
                              │  Ingress          │
                              │  (Infra k8s)      │
                              │  prefix 제거       │
                              └────────┬──────────┘
                                       │  /v1/…
                                       ▼
   ┌───────────────────────────────────────────────────────────────┐
   │                    commerce-service (8083)                    │
   │                                                               │
   │  ┌────────────┐   ┌────────────┐   ┌────────────┐            │
   │  │ sales.api  │   │ orders.api │   │payments.api│            │
   │  └─────┬──────┘   └─────┬──────┘   └─────┬──────┘            │
   │        │                │                │                    │
   │        ▼                ▼                ▼                    │
   │  ┌────────────┐   ┌────────────┐   ┌────────────┐            │
   │  │SalesService│   │OrderService│   │PaymentSvc  │            │
   │  │OrderExpirationScheduler │   │MockPaymentEng  │            │
   │  │            │   │            │   │DelayReconcl│            │
   │  └─────┬──────┘   └─────┬──────┘   └─────┬──────┘            │
   │        │                │                │                    │
   │        └────────┬───────┴────────┬──────┘                    │
   │                 │                │                            │
   │                 ▼                ▼                            │
   │        ┌────────────────────────────────┐                    │
   │        │ Repositories (JPA + 조건부 UPD)│                    │
   │        └─────┬──────────────┬───────────┘                    │
   │              │              │                                │
   │              ▼              ▼                                │
   │        ┌─────────┐    ┌──────────────┐                       │
   │        │Postgres │    │ ShoppingClient│(interface)           │
   │        │ (Flyway │    │  ─stub 또는   │                       │
   │        │  V1)    │    │   Http (미도입)│                       │
   │        └─────────┘    └──────┬───────┘                       │
   │                              │                                │
   └──────────────────────────────┼────────────────────────────────┘
                                  │  REST (예정)
                                  ▼
                        ┌────────────────────┐
                        │  shopping-service  │
                        │  (준제, 별개 서비스)│
                        └────────────────────┘
```

- `commerce-service` 는 자체 DB (`commerce` 스키마) 만 소유.
- `shopping-service` 접근은 `ShoppingClient` 인터페이스 한 곳에만 의존.
- `live-service` 는 이번 스프린트에 참조하지 않는다. Live 는 오히려 이 서비스의 판매정보 GET API 를 소비할 계획.

---

## 2. PR 스택 · 무엇이 어디에 들어갔나

병합 순서 = 위에서 아래로 (각각 base 는 바로 위 브랜치).

| # | Issue · PR | 카드 | 신규/수정 | 핵심 산출물 | 테스트 수 |
|---|---|---|---|---|---|
| 1 | #11 · #12 | Flyway + V1 스키마 baseline | 5 파일 | `V1__commerce_baseline.sql` (4 테이블 · 7 인덱스 · CHECK 다수) · ERD 근거 문서 `docs/erd-p1-reasoning.md` | Flyway 부팅 통과 |
| 2 | #13 · #14 | 도메인 패키지 12개 + ShoppingClient 계약 | 15 파일 | `sales·orders·payments × api·application·domain·infrastructure` 12 개 `package-info.java` · `ShoppingClient` interface · `ShoppingUnavailableException` · `ProductSnapshot` | compile 통과 |
| 3 | #15 · #16 | 판매 2 · 가격 변경 | 9 파일 | `Sales` entity · `SalesStatus` enum · `SalesService.changePrice` · `PATCH /v1/sales/{id}/price` | 단위 4 |
| 4 | #17 · #18 | 판매 3 · 재고 확인·수정 | 9 파일 | `SalesStock` shared PK · 조건부 UPDATE `adjustAvailable` · `GET/PATCH /v1/sales/{id}/stock` | 단위 3 + 동시성 3 |
| 5 | #19 · #20 | 판매 4 · 상태 전이 | 10 파일 | `SalesStatus.canTransitionTo` · `transitionStatus` 조건부 UPDATE · `IllegalStateTransitionException` / `ConcurrentStateChangeException` · `PATCH /v1/sales/{id}/status` | 전이 규칙 20 + 서비스 7 + 동시성 3 |
| 6 | #21 · #22 | 주문 3 · 결과 조회 | 11 파일 | `Order` entity · `OrderStatus` enum · `PasswordEncoderConfig` (bcrypt) · `OrderService.findByOrderNumberAndPassword` · `GET /v1/orders/{orderNumber}` | 단위 4 |
| 7 | #23 · #24 | 주문 4 · 결제 전 취소 + 재고 복구 | 7 파일 | `cancelOrder` / `transitionStatus` 조건부 UPDATE · `restoreReserved` · `consumeReserved` · `POST /v1/orders/{orderNumber}/cancel` | 단위 4 + 동시성 2×3 |
| 8 | #25 · #26 | 주문 5 · 미결제 만료 스케줄러 | 4 파일 | `OrderExpirationScheduler` @Scheduled · `expireOrder` 조건부 UPDATE · `@EnableScheduling` | 통합 2 |
| 9 | #27 · #28 | 결제 1 · 결제 시작 + Payment 뼈대 | 10 파일 | `PaymentAttempt` entity · `PaymentStatus` / `PaymentScenario` enum · `PaymentService.startPayment` · `POST /v1/orders/{orderNumber}/payments` | 단위 4 |
| 10 | #29 · #30 | 결제 2 · 성공·실패 결과 처리 + Mock 엔진 | 4 파일 | `MockPaymentEngine` (ScheduledExecutor · ObjectProvider) · `resolveIfProcessing` 조건부 UPDATE · `PaymentService.resolvePayment` | 단위 7 |
| 11 | #31 · #32 | 결제 3 · 지연·응답 유실 재확인 + Reconciler | 6 파일 | `PaymentDelayReconciler` @Scheduled · `PaymentService.getPayment` · `GET /v1/orders/{orderNumber}/payments/{paymentId}` | 통합 3 |
| 12 | #33 · #34 | 결제 4 · Mock 시나리오 사전 지정 + 통합 검증 | 5 파일 | `DevPaymentScenarioRegistry` (@Profile !prod + @ConditionalOnProperty) · `PaymentScenarioController` · Awaitility 통합 테스트 | 통합 5 |
| 13 | #35 · #36 | Shopping stub · `InMemoryShoppingClientStub` | 3 파일 | @ConditionalOnProperty 로 활성 · ConcurrentHashMap · 테스트용 `register/clear` | 단위 6 |

**총합:** 약 100 파일 · 70+ 테스트.

---

## 3. DB 스키마 4 테이블 요약

Flyway `V1__commerce_baseline.sql` 이 만든 스키마. 세부 근거는 [`erd-p1-reasoning.md`](./erd-p1-reasoning.md) 참고.

### `sales_info` — 판매 조건
| 컬럼 | 뜻 |
|---|---|
| `id` PK | 판매정보 ID |
| `product_id` UNIQUE | Shopping 상품 참조 (FK 아님 · 다른 서비스 소유) |
| `price` | 가격 (양의 정수, 원 단위) |
| `status` | READY · ON_SALE · SOLD_OUT · PRIVATE (CHECK 강제) |
| `version` | JPA `@Version` 낙관적 락 |
| `created_at` · `updated_at` | 감사 (BaseEntity 자동) |

### `sales_stock` — 판매 재고 (sales_info 1:1 · shared PK/FK)
| 컬럼 | 뜻 |
|---|---|
| `sales_info_id` PK · FK | 판매정보 참조 |
| `available` | 지금 살 수 있는 수량 |
| `reserved` | 이미 주문에 배정된 수량 |
| 감사 | created_at · updated_at |

**중요:** 이 테이블은 setter · 도메인 메서드로 수정하지 않는다. 오직 조건부 UPDATE 로만 (`adjustAvailable` · `restoreReserved` · `consumeReserved`). check-then-act 갭에서 초과 판매를 원천 차단하기 위함.

### `orders` — 주문
| 컬럼 | 뜻 · 특성 |
|---|---|
| `id` PK | 내부 식별자 |
| `order_number` UNIQUE | 외부 노출용 (`OD-…`) |
| `sales_info_id` FK | 어느 판매정보의 주문인지 |
| `quantity` · `unit_price` · `total_amount` | 주문 시점 스냅샷 (updatable=false) |
| `status` | PENDING_PAYMENT · PAYMENT_CONFIRMING · PAID · FAILED · CANCELLED · EXPIRED |
| `buyer_name` · `buyer_phone` | 비회원 주문자 정보 (updatable=false) |
| `lookup_password_hash` | bcrypt 해시 (조회·취소용) |
| `product_name_snapshot` | 주문 시점 상품명 (Shopping 변경 무관) |
| `idempotency_key` UNIQUE NULL | 재전송 중복 주문 방지 |
| `expires_at` | 미결제 만료 판정용 (주문 5) |
| `cancelled_at` | 취소·만료 시점 |
| `version` | 낙관적 락 |

### `payment_attempt` — 결제 시도
| 컬럼 | 뜻 |
|---|---|
| `id` PK | |
| `order_id` FK | 주문 참조 |
| `scenario` | INSTANT_SUCCESS · INSTANT_FAIL · DELAYED_SUCCESS · DELAYED_FAIL (CHECK) |
| `status` | PENDING · PROCESSING · SUCCESS · FAILED · TIMEOUT |
| `requested_at` · `resolved_at` | 요청·확정 시각 |
| `scheduled_resolve_at` | 지연 시나리오 확정 예정 시각 (Reconciler 스캔용) |
| `version` | 낙관적 락 |

---

## 4. REST API 목록 (`/v1/…`)

**주의:** Infra Ingress 가 `/api/commerce/` prefix 를 벗겨서 넘긴다. 컨트롤러는 `/v1/…` 로 시작.

### 관리자용 (판매)
| Method | Path | Body | 응답 | 카드 |
|---|---|---|---|---|
| `PATCH` | `/v1/sales/{id}/price` | `{price}` | `SalesResponse` | 판매 2 |
| `GET` | `/v1/sales/{id}/stock` | — | `SalesStockResponse` | 판매 3 |
| `PATCH` | `/v1/sales/{id}/stock` | `{delta}` | `SalesStockResponse` | 판매 3 |
| `PATCH` | `/v1/sales/{id}/status` | `{status}` | `SalesResponse` | 판매 4 |

### 비회원 주문자용 (주문)
| Method | Path | Header | 응답 | 카드 |
|---|---|---|---|---|
| `GET` | `/v1/orders/{orderNumber}` | `X-Order-Password` | `OrderResponse` | 주문 3 |
| `POST` | `/v1/orders/{orderNumber}/cancel` | `X-Order-Password` | 204 | 주문 4 |

### 결제
| Method | Path | Header · Body | 응답 | 카드 |
|---|---|---|---|---|
| `POST` | `/v1/orders/{orderNumber}/payments` | `X-Order-Password` · `{scenario?}` | `PaymentAttemptResponse` | 결제 1 |
| `GET` | `/v1/orders/{orderNumber}/payments/{paymentId}` | `X-Order-Password` | `PaymentAttemptResponse` | 결제 3 |

### 개발 전용 (Non-prod)
| Method | Path | Body | 응답 | 카드 |
|---|---|---|---|---|
| `PUT` | `/v1/dev/payment-scenarios/{orderNumber}` | `{scenario}` | 204 | 결제 4 |
| `DELETE` | `/v1/dev/payment-scenarios/{orderNumber}` | — | 204 | 결제 4 |

---

## 5. 동시성·정합성 안전 장치

이번 스프린트의 가장 중요한 설계 결정. 통합검증 1 (재고 5·10건 동시 주문 → 성공 최대 5) 같은 P1 완료 기준을 만족하는 근거.

### 5.1 조건부 UPDATE 패턴

모든 재고·주문·결제 상태 변경은 SQL WHERE 절에 안전 조건을 함께 넣는다:

```sql
-- 재고 차감 (판매 3)
UPDATE sales_stock SET available = available + :delta, updated_at = CURRENT_TIMESTAMP
 WHERE sales_info_id = :id AND available + :delta >= 0;

-- 주문 취소 (주문 4)
UPDATE orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP, ...
 WHERE id = :orderId AND status = 'PENDING_PAYMENT';

-- 결제 확정 (결제 2)
UPDATE payment_attempt SET status = :outcome, resolved_at = CURRENT_TIMESTAMP, ...
 WHERE id = :attemptId AND status = 'PROCESSING' AND resolved_at IS NULL;
```

Postgres 는 row-level X-lock 을 자동 획득 · 순차 조건 평가. 동일 row 를 노리는 100개의 트랜잭션이 몰려도 조건을 통과한 트랜잭션만 성공 · 나머지는 `updated rows == 0` 을 반환한다. 서비스 계층은 이 반환값으로 실패 여부를 판단하고 예외를 던지거나 no-op 처리.

**Java `if (available >= qty)` 를 절대 쓰지 않는다.** check-then-act 사이에 다른 트랜잭션이 끼어들 수 있어 초과 판매 · 이중 취소가 발생한다.

### 5.2 낙관적 락 `@Version`

주로 관리자 편집 흐름에 적용:

- `sales_info.version` — 가격 변경 (판매 2) lost update 방어
- `orders.version` · `payment_attempt.version` — JPA 표준 낙관적 락

낙관적 락은 가격 · 상태 등 write 빈도 낮은 필드에 적합. 재고처럼 hot row 에는 조건부 UPDATE 만 쓴다 (낙관적 락은 99번 재시도 폭주 위험).

### 5.3 동시성 시나리오 매트릭스

| 시나리오 | 방어 방법 | 검증 테스트 |
|---|---|---|
| 재고 5·10건 동시 차감 | 조건부 UPDATE `WHERE available + delta >= 0` | `SalesStockConcurrencyTest` @RepeatedTest(3) |
| 두 관리자가 동시에 다른 상태로 전이 | 조건부 UPDATE `WHERE status = :expected` | `SalesStatusConcurrencyTest` @RepeatedTest(3) |
| 취소와 결제 시작 동시 요청 | `cancelOrder` 와 `transitionStatus` 각각 조건부 UPDATE | `OrderCancellationConcurrencyTest` @RepeatedTest(3) |
| Mock 엔진과 Reconciler 이중 확정 시도 | `resolveIfProcessing` 조건부 UPDATE | `PaymentDelayReconcilerTest` · `PaymentFlowIntegrationTest` |
| 이미 취소된 주문 재취소 (재고 이중 복구) | `cancelOrder` + `restoreReserved` 각각 조건부 UPDATE | 단위 테스트 |
| 만료 스캐너 이중 실행 | 조건부 UPDATE (`WHERE status='PENDING_PAYMENT'`) | `OrderExpirationSchedulerTest` |

---

## 6. Mock 결제 동작 방식 (결제 1·2·3·4 통합)

이번 스프린트에서 만든 것 중 가장 미묘한 부분. 재기동 안전까지 고려.

### 6.1 흐름

```
[사용자 결제 시작]
      │
      ▼
POST /v1/orders/{orderNumber}/payments
      │
      ▼
PaymentService.startPayment
      ├─ bcrypt 검증 (OrderService.findByOrderNumberAndPassword)
      ├─ Order 상태 조건부 UPDATE (PENDING_PAYMENT → PAYMENT_CONFIRMING)
      ├─ PaymentAttempt INSERT (status=PROCESSING, scheduled_resolve_at=now+delay)
      └─ MockPaymentEngine.schedule(attemptId, scenario) ← 비동기
      
      (별도 스레드에서)
      │
      ▼
ScheduledExecutorService (INSTANT=즉시, DELAYED=200ms 후)
      │
      ▼
PaymentService.resolvePayment(attemptId)  ← 트랜잭션 새로 열림
      ├─ 이미 terminal 이면 no-op (idempotent)
      ├─ 조건부 UPDATE (WHERE status=PROCESSING AND resolved_at IS NULL)
      ├─ SUCCESS 경로: Order → PAID, sales_stock.reserved 소진 (consumeReserved)
      └─ FAILED 경로: Order → FAILED, sales_stock reserved → available 복구 (restoreReserved)
```

### 6.2 재기동 안전망

`ScheduledExecutorService` 는 in-process. JVM 이 죽으면 예약된 콜백은 사라진다. 대응:

- `payment_attempt.scheduled_resolve_at` 컬럼에 확정 예정 시각 저장
- `PaymentDelayReconciler` 가 10 초마다 스캔: `WHERE status='PROCESSING' AND scheduled_resolve_at < now()`
- 발견된 attempt 를 `resolvePayment` 로 확정 (idempotent 라 Mock 엔진과 race 안전)

Reconciler 통합 테스트 `PaymentDelayReconcilerTest` 가 이 흐름을 검증. 재기동 시나리오는 Sprint 3 에 강화.

### 6.3 시나리오 결정 우선순위

`PaymentService.startPayment` 에서:

1. 요청 body 의 `scenario` 필드 (명시 시)
2. `DevPaymentScenarioRegistry` 에 사전 지정된 시나리오 (dev 프로파일만)
3. Default `INSTANT_SUCCESS`

Prod 프로파일에서는 Registry 가 미주입되어 body scenario 만 유효. (Sprint 3 에 prod 에서 body scenario 도 격리 예정.)

---

## 7. 다른 서비스와의 관계

### 7.1 Shopping ← Commerce (이번 스프린트에 정의)

Commerce 가 Shopping 을 참조하는 경우:
- **판매 1** (정수 몫): 판매정보 최초 설정 시 `product_id` 존재 확인 (`ShoppingClient.exists`)
- **주문 1·2** (정수 몫): 주문 생성 시 상품명 스냅샷을 위해 `ShoppingClient.findProduct` 조회

**구조:**
- `shopping.application.ShoppingClient` — Java interface (계약)
- `shopping.domain.ProductSnapshot` — record (스냅샷)
- `shopping.application.ShoppingUnavailableException` — 네트워크·5xx·timeout 시
- `shopping.infrastructure.InMemoryShoppingClientStub` — 개발·테스트용 (현재 활성)
- `HttpShoppingClient` — 화요일 페어링에서 준제와 함께 실 구현 (미도입)

**계약:**
- 상품이 없으면 `Optional.empty()`
- 5xx / timeout 은 `ShoppingUnavailableException`
- Stub 과 HTTP 구현체 모두 동일 시맨틱 (Javadoc 명시)

**의존 방향:** `commerce → shopping` (동기 REST). 반대 방향은 없다.

### 7.2 Live ← Commerce (아직 없음)

Live 서비스는 이번 스프린트에서 Commerce 를 호출하지 않는다. 단, P1 명세상 Live 는 방송 상품 노출 시 Commerce 의 판매정보 (가격·재고·상태) GET API 를 소비할 예정. 이 GET API 는 이번 스프린트에 만든 `/v1/sales/{id}/stock` · 판매정보 조회 endpoint 로 커버 가능 (판매 5 공개 조회는 준제 Shopping 몫이나 판매정보 자체 조회는 Commerce).

### 7.3 Kafka events

이번 스프린트에 `contracts:events` 이벤트 발행·소비 없음. Kafka producer/consumer 초기화만 되어 있고 (build 의존성) 실사용 없음. Sprint 3+ 에서 주문 이벤트로 알림·랭킹 연동 시 도입.

### 7.4 Backend 서비스 간 규약

Backend 저장소의 `module-boundary-conventions` 플러그인이 서비스 간 컴파일 의존을 빌드 시점에 차단. `commerce-service.build.gradle.kts` 에는 `contracts:events` + `libs:common-*` 만 있고 `services:*` 다른 서비스 모듈에 절대 의존 못 함.

---

## 8. 남은 것 · 정수·준제·종민 몫

### 8.1 정수 (월요일 ~ 화요일)

**판매 1 · 판매정보 최초 설정**
- `sales.application.SalesRegistrationService` 신설
- Shopping stub 으로 `productId` 존재 확인 (없으면 409)
- `Sales` · `SalesStock` 두 엔티티 함께 저장 (트랜잭션 안)
- `POST /v1/sales` body `{productId, price, initialAvailable}`

**주문 1 · 주문서 확인·입력**
- `orders.api.OrderCheckoutController.postPreview`
- `POST /v1/orders/preview` — 판매정보 + Shopping 상품 스냅샷 조합해 예상 주문 정보 반환
- **Shopping fetch 는 트랜잭션 밖** (외부 호출 시 DB 커넥션 오래 잡지 않기)

**주문 2 · 주문 생성 + 재고 차감**
- `orders.application.OrderCreationService`
- 흐름: (a) 트랜잭션 밖 Shopping fetch → (b) 멱등키 확인 → (c) 트랜잭션 시작 → (d) `SalesStockJpaRepository.adjustAvailable(-qty)` (조건부 UPDATE 로 reserved += qty · available -= qty) → (e) `Order` INSERT → (f) 커밋
- 재고 부족 시 409
- **주의:** 현재 `adjustAvailable` 은 `available` 만 조작. `reserved` 도 함께 증가시키려면 별도 native SQL 추가 필요 (예: `reserveStock(id, qty)`)

**정수 몫 테스트 필수:**
- `OrderCreationConcurrencyTest` — 재고 5·10건 동시 POST → 성공 최대 5

### 8.2 준제 (화요일 오전 페어링)

- `shopping.infrastructure.HttpShoppingClient` 구현
  - Spring `RestClient` + `common-resilience` Resilience4j (`@CircuitBreaker`, `@Retry`, `@TimeLimiter`)
  - 4xx → `Optional.empty()` · 5xx/timeout → `ShoppingUnavailableException`
- `application.yml` 에서 `commerce.shopping-client.mode: http` 로 스왑
- WireMock 통합 테스트 (정상·404·5xx·timeout 4 케이스)
- shopping-service 상품 API 스펙 공유 필요 (`contracts/api/shopping-service.yaml` 또는 코드 컨트롤러 슬랙 공유)

### 8.3 종민 (화요일 저녁, 필요 시)

- 방송에서 판매정보 조회하는 GET endpoint 확정 여부 (이번 스프린트에는 `GET /v1/sales/{id}/stock` 만. 판매정보 자체 GET 은 필요 시 추가)
- IVS 실 송출 검증은 통합검증 2 대상 (스프린트 스코프 안이면 전체 팀 협업)

---

## 9. 리스크 · 알아둘 것

### 9.1 스키마·컬럼 명명

- `cancelled_at` 컬럼을 CANCELLED 뿐 아니라 EXPIRED 도 재사용. P1 스펙 원문은 CANCELLED 전용이었지만 실용적 재활용. Sprint 3 에 `terminated_at` 으로 rename 검토.

### 9.2 테스트 인프라

- **H2 로만 통합 테스트.** Postgres row lock 시맨틱과 미세 차이 가능. Sprint 3 에 Postgres Testcontainers 로 재검증 필요.
- **Awaitility** 를 결제 통합 테스트에서 사용. 시나리오 delay 200ms 고정 · pollDelay 50~100ms · atMost 2s. flaky 하면 조정.

### 9.3 트랜잭션 경계

- `PaymentService.resolvePayment` 는 Mock 엔진 스레드 · Reconciler · 서비스 호출 등 여러 경로에서 호출된다. 각 호출마다 별도 `@Transactional` 트랜잭션.
- `MockPaymentEngine.schedule` 은 트랜잭션 커밋 후 콜백 실행이 이상적. 현재는 커밋 전에 예약. 만약 startPayment 트랜잭션이 롤백되면 attempt row 가 없어 콜백은 findById 실패 → no-op (안전). 다만 로그에 warn.

### 9.4 dev 시나리오 노출

- `PaymentScenarioController` · `DevPaymentScenarioRegistry` 는 `@Profile("!prod")` + `@ConditionalOnProperty(matchIfMissing=true)` 이중 방어. Prod 배포 시 `spring.profiles.active=prod` 로 실행하면 endpoint 미노출.
- **body scenario 필드는 아직 prod 프로파일에서도 활성**. Sprint 3 에 격리 필요.

### 9.5 순환 참조

- `PaymentService ↔ MockPaymentEngine` 순환은 `ObjectProvider<PaymentService>` 지연 lookup 으로 해결. Spring 표준 패턴.
- 새 서비스 추가 시 순환 발생하면 동일 패턴 사용.

### 9.6 `@Modifying` 쿼리 트랜잭션 요구

- Spring Data JPA 의 `@Modifying @Query` 는 호출 시 트랜잭션이 필요. 서비스 계층 `@Transactional` 이 항상 감싸주므로 정상 흐름은 문제 없음.
- 테스트에서 서비스 우회하고 리포지토리를 직접 호출할 때는 `TransactionTemplate` 로 감싸야 함. 이미 두 곳 (`OrderCancellationConcurrencyTest`, `PaymentDelayReconcilerTest`) 에서 사용.

### 9.7 스케줄러 초기 지연

- `OrderExpirationScheduler` · `PaymentDelayReconciler` 모두 `initialDelay = PT1M`. 테스트 컨텍스트에서 자동 실행되지 않아 데이터 간섭 방지. 실제 운영에선 부팅 후 1분 뒤 첫 실행.
- 실 운영에서 첫 실행이 늦으면 첫 만료 감지가 지연될 수 있음. 필요 시 initialDelay 를 짧게.

---

## 10. 실행·검증 방법

### 10.1 로컬 실행 (Postgres · Kafka 필요)

```bash
# Infra 저장소에서 Postgres + Kafka 로컬 스택 기동
cd ../infra
docker compose -f local/docker-compose.yml up -d postgres kafka

# commerce-service 실행 (local 프로파일)
cd ../Backend
./gradlew :services:commerce-service:bootRun -Pspring-profile=local
# 또는 IntelliJ 에서 Application 을 local 프로파일로 실행
```

- Flyway 가 자동으로 `V1__commerce_baseline.sql` 실행
- Shopping stub 이 활성 (기본값 `commerce.shopping-client.mode: stub`)

### 10.2 테스트 실행

```bash
./gradlew :services:commerce-service:test --no-daemon
```

- H2 in-memory 로 Flyway 마이그레이션 · 모든 단위/통합/동시성 테스트 실행
- 예상 시간: 약 1분

### 10.3 수동 검증 시나리오

**주문 조회·취소 full flow:**

```bash
# 1. 판매정보 등록 (판매 1 · 정수 몫 · 당장은 SQL 직접 삽입)
psql -d commerce -c "INSERT INTO sales_info (product_id, price, status, version, created_at, updated_at)
                     VALUES (100, 10000, 'ON_SALE', 0, now(), now()) RETURNING id;"
# → 1

psql -d commerce -c "INSERT INTO sales_stock (sales_info_id, available, reserved, created_at, updated_at)
                     VALUES (1, 5, 0, now(), now());"

# 2. Shopping stub 에 상품 등록은 아직 endpoint 없음 (Sprint 3+)
#    Sprint 2 에서는 InMemoryShoppingClientStub.register 를 프로그래마틱 호출

# 3. 재고 조회
curl http://localhost:8083/v1/sales/1/stock

# 4. 재고 추가
curl -X PATCH http://localhost:8083/v1/sales/1/stock \
     -H 'Content-Type: application/json' \
     -d '{"delta": 3}'

# 5. 상태 전환
curl -X PATCH http://localhost:8083/v1/sales/1/status \
     -H 'Content-Type: application/json' \
     -d '{"status": "PRIVATE"}'
```

**Mock 결제 시나리오 지정 (dev):**

```bash
# 특정 주문번호에 대한 Mock 시나리오를 사전 지정
curl -X PUT http://localhost:8083/v1/dev/payment-scenarios/OD-20260920-000001 \
     -H 'Content-Type: application/json' \
     -d '{"scenario": "DELAYED_FAIL"}'

# 이후 해당 주문의 결제 시작 요청 (body scenario 없이) 호출 시 위 시나리오 적용
```

### 10.4 병합 순서

**주의:** 6 개 PR (#12, #14, #16, #18, #20, #22, #24, #26, #28, #30, #32, #34, #36) 이 스택되어 있음. 병합 순서:

1. #12 (Flyway 스키마) → main
2. #14 (도메인 패키지 + ShoppingClient 계약) → main (#12 와 독립)
3. 이후 #16 → #18 → #20 → #22 → #24 → #26 → #28 → #30 → #32 → #34 → #36 순차

각 PR base 가 상위 병합 후 자동으로 상향된다. 충돌 없는 clean rebase 가능.

### 10.5 만약 CI 실패하면

- 테스트 로그 위치: `Backend/services/commerce-service/build/reports/tests/test/index.html`
- 가장 자주 나는 실패: `@Modifying` 쿼리 트랜잭션 누락 → `TransactionTemplate` 사용
- 두 번째: JPA 파생 쿼리 파라미터 타입 mismatch (enum vs String) → 시그니처 확인
- 세 번째: Awaitility flaky → pollDelay · atMost 조정

---

## 11. 코드 컨벤션 · 팀 규칙 준수 상태

| 규칙 | 상태 | 비고 |
|---|---|---|
| 컨트롤러 경로 `/v1/…` | ✅ 모든 컨트롤러 준수 | Infra Ingress 가 `/api/commerce/` prefix 제거 |
| module-boundary (다른 서비스 project 참조 금지) | ✅ | 빌드 성공 = 준수 |
| `common-*` 재구현 금지 | ✅ | `common-web` GlobalExceptionHandler · `X-Request-Id` 필터 그대로 사용. 이번 PR 들에서 재구현 없음 |
| Feign · Redis · Testcontainers · OpenAPI codegen 금지 | ✅ | 의존성 확인 완료 |
| `Backend/docs/decisions/` ADR 파일 신설 금지 | ✅ | 이번 스프린트에 ADR 파일 없음. 서비스 로컬 문서 (`erd-p1-reasoning.md` · 이 리포트) 만 |
| PR 크기 max L | ✅ | 모든 PR size/L 이하 |
| 슬랙 AI 산출물 복붙 금지 | ⏳ | 슬랙 게시는 작성자 담당. 리포트 내용을 요약해 작성자가 슬랙에 재작성 필요 |

---

## 12. 다음 스텝 요약 (팀별 액션)

**정수 · 월요일 아침:**
1. `feat/#35` 브랜치 pull 하거나 필요한 것 병합 완료된 main pull
2. `./gradlew :services:commerce-service:test` 로 기본 부팅 확인
3. 판매 1 · 주문 1 · 주문 2 카드 이슈 만들고 진행 (issue #, feat/#N 브랜치)
4. `InMemoryShoppingClientStub` 을 test setup 에서 `.register(...)` 로 상품 데이터 삽입해 테스트

**준제 · 화요일 오전:**
1. 정수와 페어링해 `HttpShoppingClient` 구현
2. `application.yml` 의 `commerce.shopping-client.mode: http` 로 스왑
3. WireMock 통합 테스트

**종민 · 화요일 저녁:**
1. IVS 실 송출 준비 완료 확인
2. 통합검증 2 (방송 경유 e2e) 시도 or Sprint 3 로 이관 결정

**정 (본인) · 화요일:**
1. 정수 · 준제 PR 리뷰
2. 통합검증 1 (방송 없는 일반 구매 e2e) 통합 테스트 완성 검증
3. Sprint 2 회고 슬랙에 공유 (이 리포트 링크)

---

**끝.** 궁금한 부분 · 이상하다 싶은 부분 · 뭘 놓쳤는지 의심 가는 부분은 슬랙 스프린트 스레드에 던져주세요.
