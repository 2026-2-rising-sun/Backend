# Payment Service 내부 API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/payment-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

브라우저 공개 경로가 없는 결제 실행 서비스. Commerce(commercecore)만 X-Service-Token으로 부른다. 결제 1건의 업무 키는 Commerce가 준 orderRef이고, 토스 결제창 orderId는 `{orderRef}-{결제창 회차}`인 pgOrderId다. 토스를 부르는 API는 승인 API 하나다. 시간 판단(만료)은 Payment DB 시각으로 한다.

## 공통 인증과 서버

```yaml
security:
- ServiceToken: []
securitySchemes:
  ServiceToken:
    type: apiKey
    in: header
    name: X-Service-Token
    description: COMMERCECORE_PAYMENT_SERVICE_TOKEN으로 인증된 commercecore 호출자만 허용한다.
      허용 목록 밖의 /v1/internal 경로는 토큰이 맞아도 403이다.
servers:
- url: http://localhost:8086
```

## POST /v1/internal/payments

결제 생성(같은 orderRef는 멱등)

새 orderRef면 CREATED 결제와 판매자별 금액을 저장하고 201, 첫 pgOrderId는 `{orderRef}-1`이다. 같은 orderRef가 이미 있으면 위에서부터 처음 맞는 규칙으로 응답한다. (1) amount 또는 buyerId가 다르면 409 AMOUNT_CONFLICT. (2) CREATED이고 만료 전이거나 APPROVING·UNKNOWN·APPROVED면 200으로 현재 값을 준다. (3) 만료된 CREATED·CANCELED·PARTIALLY_REFUNDED·REFUNDED면 409 PAYMENT_CLOSED. 재요청의 expiresAt·sellerAmounts는 비교하지 않고 처음 값을 유지한다. 동시 요청도 결제 1건이다. 검증 순서는 Bean Validation(INVALID_REQUEST) → orderRef 형식 → 판매자 금액 합 → 요청 안 판매자 주문 번호 중복 → (같은 orderRef가 없을 때만) expiresAt이다. 같은 orderRef가 있으면 요청의 expiresAt이 지났어도 검사하지 않고 위 규칙으로 응답한다.

**인증:** `[{"ServiceToken": []}]`
**태그:** 없음

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderRef | string | 필수 | Commerce 주문 참조. 형식이 틀리면 400 INVALID_ORDER_REF(비었으면 INVALID_REQUEST). | {&quot;pattern&quot;: &quot;^&#91;A-Za-z0-9_-&#93;{6,50}$&quot;} |
| buyerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expiresAt | string | 필수 | 오프셋을 포함한 결제 만료 시각. 새 결제를 만들 때 현재 이하면 400 INVALID_EXPIRES_AT(같은 orderRef가 있으면 검사하지 않는다). | {&quot;format&quot;: &quot;date-time&quot;} |
| sellerAmounts | array | 필수 | 금액 합은 amount와 같아야 한다(다르면 400 SUM_MISMATCH). sellerOrderNumber는 요청 안에서 겹치지 않는다. | {&quot;minItems&quot;: 1} |
| sellerAmounts&#91;&#93;.sellerOrderNumber | string | 필수 | Commerce 판매자 주문 번호. 모든 결제에서 유일하다. | {&quot;maxLength&quot;: 64} |
| sellerAmounts&#91;&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| sellerAmounts&#91;&#93;.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| sellerAmounts&#91;&#93;.commissionRate | number | 필수 | 주문 시점 수수료율. 소수 4자리 이하. | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 1} |

```json
{
  "orderRef": "OD-20261110-K7Q2M9XA",
  "buyerId": "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13",
  "amount": 30000,
  "expiresAt": "2026-11-10T14:10:00+09:00",
  "sellerAmounts": [
    {
      "sellerOrderNumber": "OD-20261110-K7Q2M9XA-S1",
      "sellerId": "7a1c0000-0000-4000-8000-000000000001",
      "amount": 20000,
      "commissionRate": 0.1
    },
    {
      "sellerOrderNumber": "OD-20261110-K7Q2M9XA-S2",
      "sellerId": "7a1c0000-0000-4000-8000-000000000002",
      "amount": 10000,
      "commissionRate": 0.055
    }
  ]
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 새 결제 |
| 200 | 같은 orderRef의 기존 결제(현재 상태·현재 pgOrderId). 결제창이 비워졌으면 pgOrderId는 null이다. |
| 400 | INVALID_REQUEST(형식·판매자 주문 번호 중복·이미 다른 결제에 쓰인 판매자 주문 번호), INVALID_ORDER_REF, SUM_MISMATCH, INVALID_EXPIRES_AT |
| 401 | UNAUTHORIZED. X-Service-Token이 없거나 다르다. |
| 409 | AMOUNT_CONFLICT, PAYMENT_CLOSED |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;CREATED&quot;, &quot;APPROVING&quot;, &quot;APPROVED&quot;, &quot;UNKNOWN&quot;, &quot;CANCELED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;REFUNDED&quot;&#93;} |
| data.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 현재 결제창의 토스 orderId. 승인 거절 뒤에는 null이며 결제창 API로 다시 연다. | — |
| data.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "paymentId": 1001,
    "status": "CREATED",
    "pgOrderId": "OD-20261110-K7Q2M9XA-1",
    "amount": 30000,
    "expiresAt": "2026-11-10T05:10:00Z"
  },
  "error": null
}
```

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;CREATED&quot;, &quot;APPROVING&quot;, &quot;APPROVED&quot;, &quot;UNKNOWN&quot;, &quot;CANCELED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;REFUNDED&quot;&#93;} |
| data.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 현재 결제창의 토스 orderId. 승인 거절 뒤에는 null이며 결제창 API로 다시 연다. | — |
| data.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "AMOUNT_CONFLICT",
    "message": "같은 주문 참조에 금액이나 구매자가 다른 결제가 있습니다.",
    "requestId": "7f1c2b3a-0000-4000-8000-000000000001"
  }
}
```

### 계약 원문

```yaml
operationId: createPayment
summary: 결제 생성(같은 orderRef는 멱등)
description: 새 orderRef면 CREATED 결제와 판매자별 금액을 저장하고 201, 첫 pgOrderId는 `{orderRef}-1`이다.
  같은 orderRef가 이미 있으면 위에서부터 처음 맞는 규칙으로 응답한다. (1) amount 또는 buyerId가 다르면 409 AMOUNT_CONFLICT.
  (2) CREATED이고 만료 전이거나 APPROVING·UNKNOWN·APPROVED면 200으로 현재 값을 준다. (3) 만료된 CREATED·CANCELED·PARTIALLY_REFUNDED·REFUNDED면
  409 PAYMENT_CLOSED. 재요청의 expiresAt·sellerAmounts는 비교하지 않고 처음 값을 유지한다. 동시 요청도 결제
  1건이다. 검증 순서는 Bean Validation(INVALID_REQUEST) → orderRef 형식 → 판매자 금액 합 → 요청 안 판매자
  주문 번호 중복 → (같은 orderRef가 없을 때만) expiresAt이다. 같은 orderRef가 있으면 요청의 expiresAt이 지났어도
  검사하지 않고 위 규칙으로 응답한다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/CreatePaymentRequest'
      example:
        orderRef: OD-20261110-K7Q2M9XA
        buyerId: 5c2e8f1a-0000-4000-8000-6d4c0b9e7a13
        amount: 30000
        expiresAt: '2026-11-10T14:10:00+09:00'
        sellerAmounts:
        - sellerOrderNumber: OD-20261110-K7Q2M9XA-S1
          sellerId: 7a1c0000-0000-4000-8000-000000000001
          amount: 20000
          commissionRate: 0.1
        - sellerOrderNumber: OD-20261110-K7Q2M9XA-S2
          sellerId: 7a1c0000-0000-4000-8000-000000000002
          amount: 10000
          commissionRate: 0.055
responses:
  '201':
    description: 새 결제
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CreatePaymentEnvelope'
        example:
          success: true
          data:
            paymentId: 1001
            status: CREATED
            pgOrderId: OD-20261110-K7Q2M9XA-1
            amount: 30000
            expiresAt: '2026-11-10T05:10:00Z'
          error: null
  '200':
    description: 같은 orderRef의 기존 결제(현재 상태·현재 pgOrderId). 결제창이 비워졌으면 pgOrderId는 null이다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CreatePaymentEnvelope'
  '400':
    description: INVALID_REQUEST(형식·판매자 주문 번호 중복·이미 다른 결제에 쓰인 판매자 주문 번호), INVALID_ORDER_REF,
      SUM_MISMATCH, INVALID_EXPIRES_AT
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '409':
    description: AMOUNT_CONFLICT, PAYMENT_CLOSED
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: AMOUNT_CONFLICT
            message: 같은 주문 참조에 금액이나 구매자가 다른 결제가 있습니다.
            requestId: 7f1c2b3a-0000-4000-8000-000000000001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8086
```

## GET /v1/internal/payments/{orderRef}

결제 조회



**인증:** `[{"ServiceToken": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderRef | path | 필수 | string | 결제 생성 때 준 주문 참조 |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 결제 상태·금액·환불액·현재 pgOrderId·승인 시각(승인 전 null) |
| 401 | UNAUTHORIZED. X-Service-Token이 없거나 다르다. |
| 404 | PAYMENT_NOT_FOUND |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;CREATED&quot;, &quot;APPROVING&quot;, &quot;APPROVED&quot;, &quot;UNKNOWN&quot;, &quot;CANCELED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;REFUNDED&quot;&#93;} |
| data.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.refundedAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| data.approvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "paymentId": 1001,
    "status": "APPROVED",
    "amount": 30000,
    "refundedAmount": 0,
    "pgOrderId": "OD-20261110-K7Q2M9XA-2",
    "approvedAt": "2026-11-10T05:03:11Z"
  },
  "error": null
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

### 계약 원문

```yaml
operationId: getPayment
summary: 결제 조회
parameters:
- $ref: '#/components/parameters/OrderRef'
responses:
  '200':
    description: 결제 상태·금액·환불액·현재 pgOrderId·승인 시각(승인 전 null)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentEnvelope'
        example:
          success: true
          data:
            paymentId: 1001
            status: APPROVED
            amount: 30000
            refundedAmount: 0
            pgOrderId: OD-20261110-K7Q2M9XA-2
            approvedAt: '2026-11-10T05:03:11Z'
          error: null
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    description: PAYMENT_NOT_FOUND
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8086
```

## POST /v1/internal/payments/{orderRef}/checkouts

결제창 다시 열기

CREATED이고 만료 전이면 결제창 회차를 1 올리고 새 pgOrderId(`{orderRef}-{회차}`)를 준다. 부를 때마다 새 결제창이 생긴다(멱등 아님). 요청 본문은 없다.

**인증:** `[{"ServiceToken": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderRef | path | 필수 | string | 결제 생성 때 준 주문 참조 |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 새 결제창 |
| 401 | UNAUTHORIZED. X-Service-Token이 없거나 다르다. |
| 404 | NOT_READY. 결제 생성 요청이 아직 도착하지 않았다. |
| 409 | PAYMENT_CLOSED. CREATED가 아니거나 만료됐다. APPROVING·UNKNOWN이면 message가 &quot;결제를 처리하고 있습니다.&quot;다. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.pgOrderId | string | 필수 |  | — |
| data.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "pgOrderId": "OD-20261110-K7Q2M9XA-2",
    "amount": 30000,
    "expiresAt": "2026-11-10T05:10:00Z"
  },
  "error": null
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

### 계약 원문

```yaml
operationId: openCheckout
summary: 결제창 다시 열기
description: CREATED이고 만료 전이면 결제창 회차를 1 올리고 새 pgOrderId(`{orderRef}-{회차}`)를 준다. 부를
  때마다 새 결제창이 생긴다(멱등 아님). 요청 본문은 없다.
parameters:
- $ref: '#/components/parameters/OrderRef'
responses:
  '201':
    description: 새 결제창
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CheckoutEnvelope'
        example:
          success: true
          data:
            pgOrderId: OD-20261110-K7Q2M9XA-2
            amount: 30000
            expiresAt: '2026-11-10T05:10:00Z'
          error: null
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    description: NOT_READY. 결제 생성 요청이 아직 도착하지 않았다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '409':
    description: PAYMENT_CLOSED. CREATED가 아니거나 만료됐다. APPROVING·UNKNOWN이면 message가
      "결제를 처리하고 있습니다."다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8086
```

## POST /v1/internal/payments/{orderRef}/approval

결제 승인(토스 승인 호출)

브라우저가 토스 결제창에서 받은 값으로 승인한다. 검사는 이 순서로 하고 처음 걸린 하나만 응답하며, 검사에 걸리면 토스를 부르지 않는다. (1) 결제 없음 404 PAYMENT_NOT_FOUND. (2) pgOrderId의 마지막 `-` 앞부분이 orderRef가 아니면 400 ORDER_ID_MISMATCH. (3) buyerId가 다르면 403 BUYER_MISMATCH. (4) amount가 다르면 400 AMOUNT_MISMATCH. (5) pgOrderId가 현재 결제창 것이 아니면(거절 뒤 비워진 경우 포함) 409 STALE_CHECKOUT. (6) 상태가 APPROVED면 200, APPROVING·UNKNOWN이면 202, CANCELED·PARTIALLY_REFUNDED·REFUNDED면 409 PAYMENT_CLOSED. (7) CREATED인데 만료됐으면 409 PAYMENT_CLOSED. 통과하면 APPROVING으로 바꾸고 멱등 키 `confirm-{pgOrderId}`로 토스 승인을 부른다. 같은 결제에 동시에 와도 토스 호출은 1번이고 나머지는 202다. 응답은 요청을 받은 때부터 최대 8초 안에 준다. 그 안에 결과가 없거나 결과를 모르면 202 PENDING을 주고 처리는 계속한다. 결과는 조회 API와 PaymentApproved 이벤트로 확인한다.

**인증:** `[{"ServiceToken": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderRef | path | 필수 | string | 결제 생성 때 준 주문 참조 |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| buyerId | string | 필수 | Commerce가 로그인 토큰에서 꺼낸 구매자 | {&quot;format&quot;: &quot;uuid&quot;} |
| paymentKey | string | 필수 | 브라우저가 받은 토스 paymentKey | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 200} |
| pgOrderId | string | 필수 | 브라우저가 받은 토스 orderId(`{orderRef}-{결제창 회차}`) | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

```json
{
  "buyerId": "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13",
  "paymentKey": "tgen_20261110140211abcd",
  "pgOrderId": "OD-20261110-K7Q2M9XA-1",
  "amount": 30000
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 승인됨. 이미 승인된 결제의 재요청도 같다. |
| 202 | 처리 중. 8초 안에 토스 결과가 없었거나, 결과를 몰라 UNKNOWN이 됐거나, 이미 처리 중이다. Location의 조회 API나 이벤트로 결과를 확인한다. |
| 400 | INVALID_REQUEST(형식, 다른 결제가 이미 쓴 paymentKey), ORDER_ID_MISMATCH, AMOUNT_MISMATCH |
| 401 | UNAUTHORIZED. X-Service-Token이 없거나 다르다. |
| 403 | BUYER_MISMATCH |
| 404 | PAYMENT_NOT_FOUND |
| 409 | STALE_CHECKOUT(예전 결제창. 결제창을 다시 연다), PAYMENT_CLOSED(만료·닫힘, 또는 토스 승인 응답이 맞지 않아 전액 취소함) |
| 422 | DECLINED. 토스가 거절했다. 결제는 CREATED로 돌아가고 결제창이 비워진다. 결제창 API로 새 결제창을 열어 다시 승인한다. data.code·message는 토스 오류 코드·메시지다. |
| 503 | PG_UNAVAILABLE. 요청이 토스에 가지 않았다(연결 실패, 우리 키·설정 문제, 호출 제한). 결제는 CREATED로 돌아가고 결제창이 비워진다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.status | string | 필수 | 200은 APPROVED, 202는 PENDING | {&quot;enum&quot;: &#91;&quot;APPROVED&quot;, &quot;PENDING&quot;&#93;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "paymentId": 1001,
    "status": "APPROVED"
  },
  "error": null
}
```

#### 202 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.status | string | 필수 | 200은 APPROVED, 202는 PENDING | {&quot;enum&quot;: &#91;&quot;APPROVED&quot;, &quot;PENDING&quot;&#93;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "paymentId": 1001,
    "status": "PENDING"
  },
  "error": null
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "STALE_CHECKOUT",
    "message": "예전 결제창의 승인 요청입니다. 결제창을 다시 열어 주세요.",
    "requestId": "7f1c2b3a-0000-4000-8000-000000000001"
  }
}
```

#### 422 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.status | string | 필수 |  | — |
| data.code | string | 필수 | 토스 오류 코드 | — |
| data.message | string | 필수 | 토스 오류 메시지 | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

```json
{
  "success": false,
  "data": {
    "status": "DECLINED",
    "code": "REJECT_CARD_PAYMENT",
    "message": "한도초과 혹은 잔액부족으로 결제에 실패했습니다."
  },
  "error": {
    "code": "DECLINED",
    "message": "카드사에서 결제를 거절했습니다.",
    "requestId": "7f1c2b3a-0000-4000-8000-000000000001"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

### 계약 원문

```yaml
operationId: approvePayment
summary: 결제 승인(토스 승인 호출)
description: 브라우저가 토스 결제창에서 받은 값으로 승인한다. 검사는 이 순서로 하고 처음 걸린 하나만 응답하며, 검사에 걸리면 토스를
  부르지 않는다. (1) 결제 없음 404 PAYMENT_NOT_FOUND. (2) pgOrderId의 마지막 `-` 앞부분이 orderRef가
  아니면 400 ORDER_ID_MISMATCH. (3) buyerId가 다르면 403 BUYER_MISMATCH. (4) amount가 다르면
  400 AMOUNT_MISMATCH. (5) pgOrderId가 현재 결제창 것이 아니면(거절 뒤 비워진 경우 포함) 409 STALE_CHECKOUT.
  (6) 상태가 APPROVED면 200, APPROVING·UNKNOWN이면 202, CANCELED·PARTIALLY_REFUNDED·REFUNDED면
  409 PAYMENT_CLOSED. (7) CREATED인데 만료됐으면 409 PAYMENT_CLOSED. 통과하면 APPROVING으로 바꾸고
  멱등 키 `confirm-{pgOrderId}`로 토스 승인을 부른다. 같은 결제에 동시에 와도 토스 호출은 1번이고 나머지는 202다. 응답은
  요청을 받은 때부터 최대 8초 안에 준다. 그 안에 결과가 없거나 결과를 모르면 202 PENDING을 주고 처리는 계속한다. 결과는 조회 API와
  PaymentApproved 이벤트로 확인한다.
parameters:
- $ref: '#/components/parameters/OrderRef'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ApprovalRequest'
      example:
        buyerId: 5c2e8f1a-0000-4000-8000-6d4c0b9e7a13
        paymentKey: tgen_20261110140211abcd
        pgOrderId: OD-20261110-K7Q2M9XA-1
        amount: 30000
responses:
  '200':
    description: 승인됨. 이미 승인된 결제의 재요청도 같다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ApprovalEnvelope'
        example:
          success: true
          data:
            paymentId: 1001
            status: APPROVED
          error: null
  '202':
    description: 처리 중. 8초 안에 토스 결과가 없었거나, 결과를 몰라 UNKNOWN이 됐거나, 이미 처리 중이다. Location의
      조회 API나 이벤트로 결과를 확인한다.
    headers:
      Location:
        description: 결제 조회 경로 `/v1/internal/payments/{orderRef}`
        schema:
          type: string
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ApprovalEnvelope'
        example:
          success: true
          data:
            paymentId: 1001
            status: PENDING
          error: null
  '400':
    description: INVALID_REQUEST(형식, 다른 결제가 이미 쓴 paymentKey), ORDER_ID_MISMATCH, AMOUNT_MISMATCH
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    description: BUYER_MISMATCH
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '404':
    description: PAYMENT_NOT_FOUND
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '409':
    description: STALE_CHECKOUT(예전 결제창. 결제창을 다시 연다), PAYMENT_CLOSED(만료·닫힘, 또는 토스 승인
      응답이 맞지 않아 전액 취소함)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: STALE_CHECKOUT
            message: 예전 결제창의 승인 요청입니다. 결제창을 다시 열어 주세요.
            requestId: 7f1c2b3a-0000-4000-8000-000000000001
  '422':
    description: DECLINED. 토스가 거절했다. 결제는 CREATED로 돌아가고 결제창이 비워진다. 결제창 API로 새 결제창을
      열어 다시 승인한다. data.code·message는 토스 오류 코드·메시지다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/DeclinedEnvelope'
        example:
          success: false
          data:
            status: DECLINED
            code: REJECT_CARD_PAYMENT
            message: 한도초과 혹은 잔액부족으로 결제에 실패했습니다.
          error:
            code: DECLINED
            message: 카드사에서 결제를 거절했습니다.
            requestId: 7f1c2b3a-0000-4000-8000-000000000001
  '503':
    description: PG_UNAVAILABLE. 요청이 토스에 가지 않았다(연결 실패, 우리 키·설정 문제, 호출 제한). 결제는 CREATED로
      돌아가고 결제창이 비워진다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8086
```

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### common.yaml

```yaml
components:
  schemas:
    ApiError:
      type: object
      required:
      - code
      - message
      - requestId
      properties:
        code:
          type: string
        message:
          type: string
        requestId:
          type:
          - string
          - 'null'
```

### payment-service.yaml

```yaml
components:
  schemas:
    CreatePaymentRequest:
      type: object
      required:
      - orderRef
      - buyerId
      - amount
      - expiresAt
      - sellerAmounts
      properties:
        orderRef:
          type: string
          pattern: ^[A-Za-z0-9_-]{6,50}$
          description: Commerce 주문 참조. 형식이 틀리면 400 INVALID_ORDER_REF(비었으면 INVALID_REQUEST).
        buyerId:
          type: string
          format: uuid
        amount:
          type: integer
          format: int64
          minimum: 1
        expiresAt:
          type: string
          format: date-time
          description: 오프셋을 포함한 결제 만료 시각. 새 결제를 만들 때 현재 이하면 400 INVALID_EXPIRES_AT(같은
            orderRef가 있으면 검사하지 않는다).
        sellerAmounts:
          type: array
          minItems: 1
          description: 금액 합은 amount와 같아야 한다(다르면 400 SUM_MISMATCH). sellerOrderNumber는
            요청 안에서 겹치지 않는다.
          items:
            $ref: '#/components/schemas/SellerAmount'
    SellerAmount:
      type: object
      required:
      - sellerOrderNumber
      - sellerId
      - amount
      - commissionRate
      properties:
        sellerOrderNumber:
          type: string
          maxLength: 64
          description: Commerce 판매자 주문 번호. 모든 결제에서 유일하다.
        sellerId:
          type: string
          format: uuid
        amount:
          type: integer
          format: int64
          minimum: 1
        commissionRate:
          type: number
          minimum: 0
          maximum: 1
          description: 주문 시점 수수료율. 소수 4자리 이하.
    CreatePaymentEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: true
        data:
          $ref: '#/components/schemas/CreatePayment'
        error:
          type: 'null'
    CreatePayment:
      type: object
      required:
      - paymentId
      - status
      - pgOrderId
      - amount
      - expiresAt
      properties:
        paymentId:
          type: integer
          format: int64
        status:
          $ref: '#/components/schemas/PaymentStatus'
        pgOrderId:
          type:
          - string
          - 'null'
          description: 현재 결제창의 토스 orderId. 승인 거절 뒤에는 null이며 결제창 API로 다시 연다.
        amount:
          type: integer
          format: int64
        expiresAt:
          type: string
          format: date-time
    PaymentStatus:
      type: string
      enum:
      - CREATED
      - APPROVING
      - APPROVED
      - UNKNOWN
      - CANCELED
      - PARTIALLY_REFUNDED
      - REFUNDED
    ErrorEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: false
        data:
          type: 'null'
        error:
          $ref: '#/components/schemas/ApiError'
    ApiError:
      $ref: ./common.yaml#/components/schemas/ApiError
    PaymentEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: true
        data:
          $ref: '#/components/schemas/Payment'
        error:
          type: 'null'
    Payment:
      type: object
      required:
      - paymentId
      - status
      - amount
      - refundedAmount
      - pgOrderId
      - approvedAt
      properties:
        paymentId:
          type: integer
          format: int64
        status:
          $ref: '#/components/schemas/PaymentStatus'
        amount:
          type: integer
          format: int64
        refundedAmount:
          type: integer
          format: int64
        pgOrderId:
          type:
          - string
          - 'null'
        approvedAt:
          type:
          - string
          - 'null'
          format: date-time
    CheckoutEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: true
        data:
          $ref: '#/components/schemas/Checkout'
        error:
          type: 'null'
    Checkout:
      type: object
      required:
      - pgOrderId
      - amount
      - expiresAt
      properties:
        pgOrderId:
          type: string
        amount:
          type: integer
          format: int64
        expiresAt:
          type: string
          format: date-time
    ApprovalRequest:
      type: object
      required:
      - buyerId
      - paymentKey
      - pgOrderId
      - amount
      properties:
        buyerId:
          type: string
          format: uuid
          description: Commerce가 로그인 토큰에서 꺼낸 구매자
        paymentKey:
          type: string
          minLength: 1
          maxLength: 200
          description: 브라우저가 받은 토스 paymentKey
        pgOrderId:
          type: string
          minLength: 1
          maxLength: 64
          description: 브라우저가 받은 토스 orderId(`{orderRef}-{결제창 회차}`)
        amount:
          type: integer
          format: int64
          minimum: 1
    ApprovalEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: true
        data:
          $ref: '#/components/schemas/Approval'
        error:
          type: 'null'
    Approval:
      type: object
      required:
      - paymentId
      - status
      properties:
        paymentId:
          type: integer
          format: int64
        status:
          type: string
          enum:
          - APPROVED
          - PENDING
          description: 200은 APPROVED, 202는 PENDING
    DeclinedEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          type: boolean
          const: false
        data:
          $ref: '#/components/schemas/Declined'
        error:
          $ref: '#/components/schemas/ApiError'
    Declined:
      type: object
      required:
      - status
      - code
      - message
      properties:
        status:
          type: string
          const: DECLINED
        code:
          type: string
          description: 토스 오류 코드
        message:
          type: string
          description: 토스 오류 메시지
  responses:
    Unauthorized:
      description: UNAUTHORIZED. X-Service-Token이 없거나 다르다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
  parameters:
    OrderRef:
      name: orderRef
      in: path
      required: true
      description: 결제 생성 때 준 주문 참조
      schema:
        type: string
  securitySchemes:
    ServiceToken:
      type: apiKey
      in: header
      name: X-Service-Token
      description: COMMERCECORE_PAYMENT_SERVICE_TOKEN으로 인증된 commercecore 호출자만 허용한다.
        허용 목록 밖의 /v1/internal 경로는 토큰이 맞아도 403이다.
```

입력 SHA-256: `15d4bd4c6fd53f06e0af8af9403efa0f705fdf751c0c33dc587559f1cf61148e`
