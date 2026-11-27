# Frontend 새 주문서 계약 인계

초기 브라우저 실행 Backend SHA: `7a64bbbd11cc37f2960854886de18f4750241cf0`. 보존 정책2 active 누락 보완 뒤 SDK callback 추가 검사는 `4e99a0f5f86fb7f302aa8c2eb09bf02040f8650c` 불변jar를 사용했다. 실제 jar와 설정은 환경 manifest에 보존한다. 계약 원본은 [CommerceCore OpenAPI](../../../../../contracts/api/commercecore-service.yaml), 동작 기준은 [현재 계획](processing-deadline.md)이다. Backend 작성/환경 책임자는 Order 담당, UI 작성/브라우저 조작 책임자는 Frontend `ctx_ff5b03f8532d`다. Payment 소유 원본은 변경하지 않는다.

## 호출과 응답

모든 URL은 Core 서비스 기준이다. Gateway의 `/api/commercecore` 접두어는 서비스 URL에 포함되지 않는다. `Authorization: Bearer <USER 또는 SELLER JWT>`가 필요하다. 성공 JSON은 아래 raw 객체이며 `{success,data,error}`로 감싸지 않는다. 오류는 공통 오류 봉투다.

| 호출 | 입력 | 성공 응답 |
|---|---|---|
| POST `/v1/order-sheets` | `X-Idempotency-Key` 1~64자, 생성 본문 | 신규201/같은요청200 `{order,payment}` |
| GET `/v1/order-sheets/active` | 본문 없음 | 진행 중 주문200 `{order,payment}`, 없으면204. 이전 미연결 내부 주문/중복 활성 주문409 |
| GET `/v1/order-sheets/{number}` | 저장한 `order.orderNumber` | 200 `{order,payment}` |
| POST `/{number}/payment` (접두어 `/v1/order-sheets`) | 본문/새 키 없음 | 200 `payment` 객체. 미준비 생성 원문 재전달 |
| POST `/{number}/payment/checkouts` | 본문 없음 | 200 `payment` 객체. 새 결제창은 비멱등이며 pgOrderId 변경 가능 |
| POST `/{number}/payment/approval` | 승인 시도마다 새 `X-Idempotency-Key`(같은 시도의 재전송은 같은 키), `{paymentKey,pgOrderId,amount}` | 202+Location 또는200 `payment` 객체 |
| POST `/{number}/cancel` | 본문 없음 | 204. PENDING 준비 전후 취소 가능, PROCESSING409 |

생성 본문은 아래 둘 중 하나다. 상품 ID와 SKU ID는 서로 다른 식별자다.

```json
{"items":[{"skuId":93001,"quantity":2}],"buyerName":"테스트 구매자","buyerPhone":"01012345678","expectedAmount":8000,"couponId":null,"couponSkuId":null}
```

Cart는 `items` 대신 `cartItems:[{itemId,version}]`를 보낸다. Cart GET의 실제 item ID/version을 그대로 보존한다. 수량은 저장한 Cart 선택에서 가져오며 요청에 별도 덮어쓰지 않는다. `expectedAmount`는 선택 SKU 쿠폰 적용 후 실결제액, 양수다. 쿠폰은 주문당1장/선택SKU1개에만 적용하고 `couponId`와 `couponSkuId`를 함께 보낸다. 새 Payment는 판매자별 금액도 양수여야 한다. 기존 legacy0원 경로를 새 경로로 임의 치환하지 않는다.

`order`에는 `orderNumber,status,failureReason,totalAmount,discountAmount,payableAmount,expiresAt,processingExpiresAt,paymentId,couponId,couponOrderItemId,items`가 있다. item에는 `orderItemId,skuId,productId,productName,sellerId,mainImageUrl,selectedOptions,quantity,unitPrice,totalAmount,discountAmount,payableAmount,sourceCartItemId,sourceCartVersion`가 있다.

`payment`는 `orderNumber,orderStatus,paymentId,paymentStatus,pgOrderId,amount,expiresAt,processingExpiresAt,refundStatus`다. 준비 중 ID/pgOrderId/processingExpiresAt은 null일 수 있다. `orderStatus`가 화면의 주문 판단 기준이다. Payment의 `PENDING`은 결제 응답 상태이며 Order PENDING과 다르다. HTTP200 자체도 결제 성공을 뜻하지 않는다.

## 상태·재시도·SDK

- 생성→PENDING/생성부터600초. 준비 성공과 결제창 열기는 PROCESSING을 시작하지 않는다.
- 실제 인증 callback 뒤 승인 접수→PROCESSING/이 시도의 접수부터 별도600초. 같은 시도의 재요청은 같은 키·원문을 보존하며 연장하지 않는다. 네트워크 실패로 새 주문/새 승인 키를 발급하지 않는다.
- 202/UNKNOWN은 실패가 아니다. Location 또는 위 GET으로 조회하며 처리 기한까지 PROCESSING을 유지한다. 이 GET은 DB 상태 조회이며 브라우저가 Payment 내부 API를 호출하지 않는다. Core가 1초마다 Payment를 조회해 결과를 반영한다.
- 유효 승인→PAID. 승인 거절→주문이 PENDING으로 돌아오고 예약은 유지된다(#418). 응답 모양은 `orderStatus: PENDING`, `paymentStatus: DECLINED`, `pgOrderId: null`이다. 거절이 202 뒤에 확정되거나 Payment가 토스에 보내지 못해 503 `PG_UNAVAILABLE`로 답한 경우에는 승인 응답이 먼저 202 PROCESSING이고, 1초쯤 뒤 GET이 같은 모양을 보여 준다.
- 거절 뒤에는 원래 `expiresAt` 전까지 새 checkouts로 결제창을 다시 열고, 새 `pgOrderId`와 **새 `X-Idempotency-Key`**로 승인한다. 거절된 시도의 키를 새 결제창 정보로 다시 쓰면 409("거절된 승인 시도의 키입니다…")다. 같은 키·같은 본문 재전송은 그 시도의 결과(새 승인 없음)를 돌려준다. 결제창 인증 실패·닫힘은 아직 승인 접수 전이라 PENDING 유지하고 새 checkouts를 요청할 수 있다.
- Payment가 결제를 CANCELED로 끝내면(토스 승인 응답이 결제와 다름) 주문은 FAILED다. 재결제 대상이 아니다.
- PENDING이 `expiresAt`에 닿으면 EXPIRED. PROCESSING이 처리 기한에 닿으면 COMPENSATION_PENDING(보상 대기)이고 예약은 이때 해제된다. 결과 SUCCEEDED/NO_CHARGE로 CANCELLED가 된다. 보상 없이 닫힌 주문에 늦게 승인이 오면 주문 종료를 유지하고 시스템 보상을 요청한다. 요청·Kafka 발행 성공은 환불 완료가 아니다.
- 성공 시 같은 Cart ID/version만 제거한다. 사용자가 수정한 Cart는 보존한다. 쿠폰 사용은 승인 확정 시 반영한다.

Core가 SDK 준비 값으로 주는 것은 `pgOrderId,amount,expiresAt`이다. SDK의 `orderId`에는 **받은 pgOrderId**를 쓴다. callback의 `paymentKey,orderId,amount`에서 `orderId`를 Core 승인 본문의 `pgOrderId`에 매핑한다. 결제창을 재발급했다면 이전 ID를 재사용하지 않는다. `buyerId`는 본문으로 보내지 않고 Core가 JWT에서 공급한다.

SDK clientKey/customerKey·성공/실패 URL·결제 UI 설정은 Core 응답에 없다. 다만 **SDK 흐름 자체는 팀원 설계에 있다.** 팀원 dev Payment design.html의 인증 흐름과 위젯 절은 SDK 초기화·금액/UI 렌더링·requestPayment·successUrl의 paymentKey/orderId/amount·failUrl을 설명한다. [공식 v2 위젯 가이드](https://docs.tosspayments.com/guides/v2/payment-widget/integration)도 인증 결과를 서버에서 검증한 뒤 승인하는 순서다. Frontend PR81의 provider/callback 연결은 94b1bee1에서 추가했고 명시적 SDK 인터페이스 대역으로 실Core 승인까지 검사했다. 실제 키 세트·customerKey 운영 공급과 원격 SDK 실행은 미확인이다. 이를 “전체 SDK 설계 없음”으로 확대하지 않는다.

[공식 코어 API](https://docs.tosspayments.com/reference)는 orderId PG조회가 위젯 `gsk` 키를 지원하지 않는다고 명시한다. [API 키 기준](https://docs.tosspayments.com/reference/using-api/api-keys)은 client/secret을 같은 세트로 쓰며 위젯 키는 승인·paymentKey 조회를 지원한다고 설명한다. 현재 최신800b41fd(실행2c6aa250과 생산동일) `TossPaymentsClient`는 confirm·paymentKey 조회·cancel만 호출한다. `PaymentExpiryJob`은 DB CREATED만 닫고 orderId로 PG를 조회하지 않는다. 따라서 이 제한과 현재 실행 코드의 직접 충돌은 미발견이다. 공급 설정은 단일 `PAYMENT_TOSS_SECRET_KEY`이며 실제 키 종류/세트는 확인하지 않았다. 임의 API 개별 키 혼용이나 별도 Payment 키 정책을 만들지 않는다.

이번 공유 환경은 명시적인 PG/인증 대역을 사용하며 실계좌·실결제는 실행하지 않는다. 대역 callback 검증을 실 Toss SDK 인증 검증이라고 보고하지 않는다. 실제 provider 코드·주입 설정·SDK 대역 검사·실 SDK 실행을 따로 기록한다.

## 식별자와 복구 제약

Cart GET 그룹 item의 `skuId`는 실제 주문 입력 원본이다. 직접 구매는 기존 GET `/v1/orders/checkout?productId={id}&quantity={n}` 응답의 **명시적인 `skuId`**를 사용한다. 서버는 Product→단일 SKU를 조회해 선택하며 기존 `salesId` 호환 필드도 유지한다. 두 필드는 이 단일 SKU 조회 결과를 가리킬 뿐 일반적인 상품ID/판매정보ID와 SKU ID의 동일성을 보장하지 않는다. 복수 SKU 상품409·삭제 상품404 정책은 유지한다. `orderable`, 할인 후 `payableAmount`와 쿠폰 견적을 확인하고, 생성 때 가격·재고·상품 판매 가능 여부를 다시 검증한다. 판매자 전용 API나 숨은 Cart 생성을 사용하지 않는다. 공개 다중SKU/옵션 정책은 재개하지 않는다.

기존 조회는 GET `/v1/orders`, `/v1/orders/{orderNumber}`, `/v1/payment-groups/active`(200/없으면204), `/v1/payment-groups/{number}`다. 이는 legacy 소유 조회이며 새 policy1 주문서를 반환하지 않는다. 새 주문은 order-sheets GET과 **GET `/v1/order-sheets/active`**를 사용하고 번호를 종류와 함께 보존한다. 생성 전·응답 유실·재진입 시 두 종류의 active 조회로 기존 진행 주문을 회복하고 새 POST 중복 생성을 막는다. 새 active 조회는 서버 소유 기한에 도달한 주문을 한 번만 만료·해제하며 HTTP 결제 호출이나 기한 연장을 하지 않는다. 이전 내부 policy1/2에 새 Payment 연동 기록이 없으면409로 조정 필요를 알린다. 이를 없는 주문으로 해석해 새 주문을 만들지 않는다. 다른 구매자의 주문은 노출하지 않는다.

## 공유 검증 환경 조정

사용한 전용 포트는 PostgreSQL57036 / Kafka57037 / Core57038 / 팀원Payment57039 / PG대역57040 / 세션대역57041, Frontend Vite5379다. 실제 readiness·JWT/세션HTTP·seed·Kafka 소비자 할당을 확인한 뒤 Frontend에 manifest를 전달했고 실API 검증을 완료했다. Order 담당은 Backend 앱/대역/컨테이너만 소유·정리했고 Frontend는 HTTP로 조작하고 본인 Vite를 정리한다. 검증 종료 통보 후 전용 DB5개를 백업하고 앱/본인 컨테이너2개를 종료·제거했다. 최종 `/tmp/order331-followup-browser/manifest.json`의 ready는 false이며 상시 실행 환경이 아니다. 공유 kind·기존 컨테이너는 사용하지 않았다.

최신 팀원 앱은 생성a4cd70be·승인API47d4850a·복구2c6aa250이다. 실제 RefundRequested 소비/RefundSucceeded·Failed 생산은 아직 미발견이고 UNKNOWN30분 복구중단은 GET에 노출되지 않는다. 이 부재를 UI 타임아웃 실패나 환불 완료로 대체하지 않는다.

### 개발 인증 대역 요청

Frontend는 Vite `127.0.0.1:5379`를 쓰고 Core57038·세션/알림57041로 proxy한다. 대역 로그인/refresh는 `success/data(accessToken,refreshToken,tokenType,expiresIn)`, me는 `memberId,email,displayName,roles:[USER]`, unread-count는0을 반환한다. Core JWT 검증·세션 확인 HTTP 경계는 실제 구현을 사용하지만 Member/Notification 앱을 실행한 증거는 아니다.

PG 인증 대역57040의 POST `/fixture/authentications`는 `{orderNumber,pgOrderId,amount,outcome,scenario}`를 받는다. outcome은 `authenticated|cancelled|failed`, scenario는 `normal|pending|late|declined`다. 인증 성공만 `{label:PG_AUTH_DOUBLE,outcome:authenticated,paymentKey,orderId,amount}`를 반환한다. 취소/실패는 승인 키를 만들지 않는다. Payment 승인 HTTP에서 이 키·orderId·amount를 재검증한다. pending은 팀원8초 제한 뒤202/Kafka를 확인하기 위해 PG 응답을 지연한다. late는 Backend가 전용 DB 기한을 앞당겨 만료를 확인한 뒤 대역 응답을 해제한다. 강제 시간 이동은 실제600초 대기와 구분한다. 실제 SDK clientKey/customerKey·실 PG 인증을 구현한 것으로 보고하지 않는다.
