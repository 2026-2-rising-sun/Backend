# CommerceCore Service API / order

<!-- generated: OpenAPI -->

[계약 YAML](../../../../../contracts/api/commercecore-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

order 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.

## 공통 인증과 서버

```yaml
security:
- BearerAuth: []
securitySchemes:
  BearerAuth:
    $ref: ./common.yaml#/components/securitySchemes/BearerAuth
  ServiceToken:
    type: apiKey
    in: header
    name: X-Service-Token
    description: 대상 서비스가 설정한 opaque token으로 호출 서비스 신원을 판정한다. 사용자 JWT와 분리한다.
servers:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/orders/checkout

주문 가능 여부와 최신 금액 확인

구 productId 호환 API이며 SKU가 하나인 상품만 지원한다. Sales 이력 없는 SKU도 허용하고 SKU 없음404·복수SKU409이며 응답 skuId는 LegacyOrderSkuService가 단일 SKU를 실제 조회한 식별자다. 기존 salesId 호환 필드는 유지하며 상품/판매정보 ID와의 동일성을 추정하지 않는다. 이 skuId와 할인후 payableAmount를 새 order-sheets 입력에 사용한다. 재고·쿠폰 예약 없음. couponId가 있으면 회원 보유·사용 가능 기간 및 상품 대상 여부를 서버에서 검증한다. 쿠폰 적용 대상 상품을 주문할 수 없는 경우도 409. 주문 생성 때 가격·판매·재고를 다시 검사한다. 곱셈 overflow는 400.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | query | 필수 | integer |  |
| quantity | query | 필수 | string |  |
| couponId | query | 선택 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| salesId | integer | 필수 | 기존 호환 이름. 신규 호출자는 명시적 skuId 사용 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| skuId | integer | 필수 | 단일 SKU 실제 조회 결과. productId/과거 Sales ID에서 추정하지 않음 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productName | string | 필수 |  | — |
| unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| available | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| salesStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;&#93;} |
| orderable | boolean | 필수 |  | — |
| reason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;enum&quot;: &#91;&quot;NOT_ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;EXCEEDS_STOCK&quot;, null&#93;} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
tags:
- Orders
summary: 주문 가능 여부와 최신 금액 확인
operationId: previewOrder
description: 구 productId 호환 API이며 SKU가 하나인 상품만 지원한다. Sales 이력 없는 SKU도 허용하고 SKU 없음404·복수SKU409이며
  응답 skuId는 LegacyOrderSkuService가 단일 SKU를 실제 조회한 식별자다. 기존 salesId 호환 필드는 유지하며 상품/판매정보
  ID와의 동일성을 추정하지 않는다. 이 skuId와 할인후 payableAmount를 새 order-sheets 입력에 사용한다. 재고·쿠폰 예약
  없음. couponId가 있으면 회원 보유·사용 가능 기간 및 상품 대상 여부를 서버에서 검증한다. 쿠폰 적용 대상 상품을 주문할 수 없는 경우도
  409. 주문 생성 때 가격·판매·재고를 다시 검사한다. 곱셈 overflow는 400.
parameters:
- name: productId
  in: query
  required: true
  schema:
    type: integer
    format: int64
    minimum: 1
- name: quantity
  in: query
  required: true
  schema:
    type: string
    pattern: ^[1-9][0-9]{0,8}$
- name: couponId
  in: query
  required: false
  schema:
    type: string
    maxLength: 64
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/OrderCheckout'
        examples:
          normal:
            value:
              productId: 1
              salesId: 501
              skuId: 501
              productName: 가을 니트
              unitPrice: 15000
              quantity: 2
              totalAmount: 30000
              available: 10
              salesStatus: ON_SALE
              orderable: true
              reason: null
              couponId: null
              discountAmount: 0
              payableAmount: 30000
          couponApplied:
            value:
              productId: 1
              salesId: 501
              skuId: 501
              productName: 가을 니트
              unitPrice: 15000
              quantity: 2
              totalAmount: 30000
              available: 10
              salesStatus: ON_SALE
              orderable: true
              reason: null
              couponId: 33333333-3333-4333-8333-333333333334
              discountAmount: 5000
              payableAmount: 25000
          soldOut:
            value:
              productId: 1
              salesId: 501
              skuId: 501
              productName: 가을 니트
              unitPrice: 15000
              quantity: 2
              totalAmount: 30000
              available: 0
              salesStatus: SOLD_OUT
              orderable: false
              reason: SOLD_OUT
              couponId: null
              discountAmount: 0
              payableAmount: 30000
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/ShoppingUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/orders

회원 단건 주문 생성

구 productId 호환 API이며 SKU가 하나인 상품만 지원한다. Sales 이력 없는 SKU도 허용하고 SKU 없음404·복수SKU409이며 신규 SKU 주문서 공개 경로는 후속이다. couponId는 선택이며 지정 시 보유 쿠폰과 대상 상품을 검증해 할인액·실부담액을 저장하고 결제 묶음에서 예약한다. expectedTotalAmount는 할인 전 주문 총액이다. 서로 다른 회원은 같은 키를 독립 사용한다. 같은 회원의 키 재사용은 상품·수량·구매자 정보·expectedTotalAmount·couponId를 비교한다. 단건도 주문 하나를 가진 결제 묶음으로 생성하고 groupNumber를 반환한다. 회원당 활성 묶음은 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 다른 새 주문은 409, 동일 입력 재전송은 허용한다. 새 주문 저장·재고 예약·쿠폰 예약은 원자적이며 새 주문 생성 기한은 기본600초다. 승인 실행 접수는 별도600초 기한과 영속 HTTP 전달 요청을 함께 저장한다. 저장된 기한은 변경하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| X-Idempotency-Key | header | 선택 | string | 회원별 멱등키. 공백만 허용하지 않으며 동일 키의 입력 변경은 409. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| buyerName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| buyerPhone | string | 필수 |  | {&quot;pattern&quot;: &quot;^&#91;0-9-&#93;{9,32}$&quot;, &quot;writeOnly&quot;: true} |
| expectedTotalAmount | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |

```json
{
  "productId": 1,
  "quantity": 2,
  "buyerName": "구매자",
  "buyerPhone": "010-1234-5678",
  "expectedTotalAmount": 30000,
  "couponId": "coupon-uuid"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |
| 200 | 동일 회원·멱등키·입력 재전송 |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| productName | string | 필수 |  | — |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| buyerName | string | 필수 |  | — |
| createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |

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

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| productName | string | 필수 |  | — |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| buyerName | string | 필수 |  | — |
| createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |

### 계약 원문

```yaml
tags:
- Orders
summary: 회원 단건 주문 생성
operationId: createOrder
description: 구 productId 호환 API이며 SKU가 하나인 상품만 지원한다. Sales 이력 없는 SKU도 허용하고 SKU 없음404·복수SKU409이며
  신규 SKU 주문서 공개 경로는 후속이다. couponId는 선택이며 지정 시 보유 쿠폰과 대상 상품을 검증해 할인액·실부담액을 저장하고 결제
  묶음에서 예약한다. expectedTotalAmount는 할인 전 주문 총액이다. 서로 다른 회원은 같은 키를 독립 사용한다. 같은 회원의 키
  재사용은 상품·수량·구매자 정보·expectedTotalAmount·couponId를 비교한다. 단건도 주문 하나를 가진 결제 묶음으로 생성하고
  groupNumber를 반환한다. 회원당 활성 묶음은 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 다른 새 주문은
  409, 동일 입력 재전송은 허용한다. 새 주문 저장·재고 예약·쿠폰 예약은 원자적이며 새 주문 생성 기한은 기본600초다. 승인 실행 접수는
  별도600초 기한과 영속 HTTP 전달 요청을 함께 저장한다. 저장된 기한은 변경하지 않는다.
parameters:
- $ref: '#/components/parameters/OptionalIdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/CreateOrderRequest'
      example:
        productId: 1
        quantity: 2
        buyerName: 구매자
        buyerPhone: 010-1234-5678
        expectedTotalAmount: 30000
        couponId: coupon-uuid
x-required-roles:
- USER
- SELLER
responses:
  '201':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Order'
        examples:
          created:
            value:
              orderNumber: 20261001-000001
              productName: 가을 니트
              quantity: 2
              unitPrice: 15000
              totalAmount: 30000
              discountAmount: 0
              payableAmount: 30000
              status: PENDING_PAYMENT
              buyerName: 구매자
              createdAt: '2026-10-01T00:00:00Z'
              expiresAt: '2026-10-01T00:10:00Z'
              cancelledAt: null
              groupNumber: PG-00000000-0000-0000-0000-000000000149
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/ShoppingUnavailable'
  '200':
    description: 동일 회원·멱등키·입력 재전송
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Order'
        examples:
          replay:
            value:
              orderNumber: 20261001-000001
              productName: 가을 니트
              quantity: 2
              unitPrice: 15000
              totalAmount: 30000
              discountAmount: 0
              payableAmount: 30000
              status: PENDING_PAYMENT
              buyerName: 구매자
              createdAt: '2026-10-01T00:00:00Z'
              expiresAt: '2026-10-01T00:10:00Z'
              cancelledAt: null
              groupNumber: PG-00000000-0000-0000-0000-000000000149
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/orders

본인 주문 내역

createdAt 및 id 내림차순. SELLER도 본인 거래만 조회한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| page | query | 선택 | integer |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| items | array | 필수 |  | — |
| items&#91;&#93;.orderNumber | string | 필수 |  | — |
| items&#91;&#93;.groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| items&#91;&#93;.productName | string | 필수 |  | — |
| items&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| items&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| items&#91;&#93;.buyerName | string | 필수 |  | — |
| items&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| items&#91;&#93;.expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| items&#91;&#93;.cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| items&#91;&#93;.salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |
| page | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| size | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 100} |
| totalElements | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| totalPages | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
tags:
- Orders
summary: 본인 주문 내역
operationId: listMyOrders
description: createdAt 및 id 내림차순. SELLER도 본인 거래만 조회한다.
parameters:
- name: page
  in: query
  required: false
  schema:
    type: integer
    minimum: 0
    default: 0
- name: size
  in: query
  required: false
  schema:
    type: integer
    minimum: 1
    maximum: 100
    default: 20
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/OrderHistory'
        examples:
          normal:
            value:
              items:
              - orderNumber: 20261001-000001
                productName: 가을 니트
                quantity: 2
                unitPrice: 15000
                totalAmount: 30000
                discountAmount: 0
                payableAmount: 30000
                status: PENDING_PAYMENT
                buyerName: 구매자
                createdAt: '2026-10-01T00:00:00Z'
                expiresAt: '2026-10-01T00:10:00Z'
                cancelledAt: null
                groupNumber: PG-00000000-0000-0000-0000-000000000149
              page: 0
              size: 20
              totalElements: 1
              totalPages: 1
          empty:
            value:
              items: []
              page: 0
              size: 20
              totalElements: 0
              totalPages: 0
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/orders/{orderNumber}

본인 주문 조회



**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| productName | string | 필수 |  | — |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| buyerName | string | 필수 |  | — |
| createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |

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
tags:
- Orders
summary: 본인 주문 조회
operationId: getMyOrder
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Order'
        examples:
          normal:
            value:
              orderNumber: 20261001-000001
              productName: 가을 니트
              quantity: 2
              unitPrice: 15000
              totalAmount: 30000
              discountAmount: 0
              payableAmount: 30000
              status: PENDING_PAYMENT
              buyerName: 구매자
              createdAt: '2026-10-01T00:00:00Z'
              expiresAt: '2026-10-01T00:10:00Z'
              cancelledAt: null
              groupNumber: PG-00000000-0000-0000-0000-000000000149
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/orders/{orderNumber}/cancel

결제 전 본인 주문 취소

여러 주문이 연결된 묶음의 개별 주문 취소는 409이며 묶음 전체 취소 API를 사용한다. 단건 묶음은 기존 주문 경로로 전체 취소하고 CANCELLED 재전송은 204. 그룹 없는 기존 주문의 반복 취소와 결제 진행·완료·실패·만료는 409.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 취소 및 예약 재고 복구, 본문 없음 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

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
tags:
- Orders
summary: 결제 전 본인 주문 취소
operationId: cancelMyOrder
description: 여러 주문이 연결된 묶음의 개별 주문 취소는 409이며 묶음 전체 취소 API를 사용한다. 단건 묶음은 기존 주문 경로로 전체
  취소하고 CANCELLED 재전송은 204. 그룹 없는 기존 주문의 반복 취소와 결제 진행·완료·실패·만료는 409.
x-required-roles:
- USER
- SELLER
responses:
  '204':
    description: 취소 및 예약 재고 복구, 본문 없음
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/orders/{orderNumber}/payments

본인 주문 결제 시작

결제 결과는 Mock gateway가 결정한다. 단건 묶음은 이 경로로 결제하고 기존 시도를 재전송에 반환한다. 여러 주문이 연결된 묶음의 개별 결제는 409이며 통합 결제 API를 사용한다. 응답은 요청 접수 시점 상태이며 이후 조회로 확인한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payments

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |

### 요청 본문

본문: 선택

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

```json
{}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;, &quot;readOnly&quot;: true} |
| status | string | 필수 | 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은 첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다. 이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가 기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며 새 시간 초과 결과는 UNKNOWN으로 처리한다. | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;, &quot;TIMEOUT&quot;&#93;} |
| requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| scheduledResolveAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은 남는다. | {&quot;format&quot;: &quot;date-time&quot;} |

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
tags:
- Payments
summary: 본인 주문 결제 시작
operationId: startMyPayment
description: 결제 결과는 Mock gateway가 결정한다. 단건 묶음은 이 경로로 결제하고 기존 시도를 재전송에 반환한다. 여러 주문이
  연결된 묶음의 개별 결제는 409이며 통합 결제 API를 사용한다. 응답은 요청 접수 시점 상태이며 이후 조회로 확인한다.
requestBody:
  required: false
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/StartPaymentRequest'
      example: {}
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentAttempt'
        examples:
          pending:
            value:
              paymentId: 1
              orderId: 1
              scenario: INSTANT_SUCCESS
              status: PROCESSING
              requestedAt: '2026-10-01T00:00:00Z'
              resolvedAt: null
              scheduledResolveAt: '2026-10-01T00:00:00Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/orders/{orderNumber}/payments/{paymentId}

본인 주문의 결제 시도 조회

다른 회원 주문 또는 다른 주문의 paymentId는 모두 404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payments

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |
| paymentId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;, &quot;readOnly&quot;: true} |
| status | string | 필수 | 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은 첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다. 이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가 기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며 새 시간 초과 결과는 UNKNOWN으로 처리한다. | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;, &quot;TIMEOUT&quot;&#93;} |
| requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| scheduledResolveAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은 남는다. | {&quot;format&quot;: &quot;date-time&quot;} |

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
tags:
- Payments
summary: 본인 주문의 결제 시도 조회
operationId: getMyPayment
description: 다른 회원 주문 또는 다른 주문의 paymentId는 모두 404.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentAttempt'
        examples:
          pending:
            value:
              paymentId: 1
              orderId: 1
              scenario: INSTANT_SUCCESS
              status: PROCESSING
              requestedAt: '2026-10-01T00:00:00Z'
              resolvedAt: null
              scheduledResolveAt: '2026-10-01T00:00:00Z'
          success:
            value:
              paymentId: 1
              orderId: 1
              scenario: INSTANT_SUCCESS
              status: SUCCESS
              requestedAt: '2026-10-01T00:00:00Z'
              resolvedAt: '2026-10-01T00:00:01Z'
              scheduledResolveAt: '2026-10-01T00:00:00Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
  - $ref: '#/components/parameters/PaymentId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/active

본인의 활성 결제 묶음 조회

회원의 PENDING_PAYMENT 또는 PAYMENT_CONFIRMING 묶음 하나를 반환한다. 생성·결제 응답 유실 또는 새로고침 후 결과 복구에 사용한다. 활성 묶음이 없으면 204이며 실패·취소·만료·완료 묶음은 활성 조회에서 제외한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 204 | 활성 묶음 없음, 본문 없음 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| groupNumber | string | 필수 |  | — |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;maxLength&quot;: 64} |
| expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 실행 접수부터600초. 재전송으로 연장하지 않는다. 실행 전 및 V105 이전 확인중 주문은 null. | {&quot;format&quot;: &quot;date-time&quot;} |
| orders | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| orders&#91;&#93;.orderNumber | string | 필수 |  | — |
| orders&#91;&#93;.groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| orders&#91;&#93;.productName | string | 필수 |  | — |
| orders&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| orders&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| orders&#91;&#93;.buyerName | string | 필수 |  | — |
| orders&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

```json
{
  "groupNumber": "PG-00000000-0000-0000-0000-000000000149",
  "status": "PENDING_PAYMENT",
  "totalAmount": 25000,
  "discountAmount": 0,
  "payableAmount": 25000,
  "couponId": null,
  "expiresAt": "2026-10-08T00:10:00Z",
  "processingExpiresAt": null,
  "orders": [
    {
      "orderNumber": "OD-20261008-000001",
      "productName": "상품 1",
      "quantity": 2,
      "unitPrice": 10000,
      "totalAmount": 20000,
      "discountAmount": 0,
      "payableAmount": 20000,
      "status": "PENDING_PAYMENT",
      "buyerName": "구매자",
      "createdAt": "2026-10-08T00:00:00Z",
      "expiresAt": "2026-10-08T00:10:00Z",
      "cancelledAt": null,
      "groupNumber": "PG-00000000-0000-0000-0000-000000000149"
    },
    {
      "orderNumber": "OD-20261008-000002",
      "productName": "상품 2",
      "quantity": 1,
      "unitPrice": 5000,
      "totalAmount": 5000,
      "discountAmount": 0,
      "payableAmount": 5000,
      "status": "PENDING_PAYMENT",
      "buyerName": "구매자",
      "createdAt": "2026-10-08T00:00:00Z",
      "expiresAt": "2026-10-08T00:10:00Z",
      "cancelledAt": null,
      "groupNumber": "PG-00000000-0000-0000-0000-000000000149"
    }
  ],
  "paymentId": null
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

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

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
tags:
- Payment Groups
summary: 본인의 활성 결제 묶음 조회
operationId: getMyActivePaymentGroup
description: 회원의 PENDING_PAYMENT 또는 PAYMENT_CONFIRMING 묶음 하나를 반환한다. 생성·결제 응답 유실 또는
  새로고침 후 결과 복구에 사용한다. 활성 묶음이 없으면 204이며 실패·취소·만료·완료 묶음은 활성 조회에서 제외한다.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentGroup'
        example:
          groupNumber: PG-00000000-0000-0000-0000-000000000149
          status: PENDING_PAYMENT
          totalAmount: 25000
          discountAmount: 0
          payableAmount: 25000
          couponId: null
          expiresAt: '2026-10-08T00:10:00Z'
          processingExpiresAt: null
          orders:
          - orderNumber: OD-20261008-000001
            productName: 상품 1
            quantity: 2
            unitPrice: 10000
            totalAmount: 20000
            discountAmount: 0
            payableAmount: 20000
            status: PENDING_PAYMENT
            buyerName: 구매자
            createdAt: '2026-10-08T00:00:00Z'
            expiresAt: '2026-10-08T00:10:00Z'
            cancelledAt: null
            groupNumber: PG-00000000-0000-0000-0000-000000000149
          - orderNumber: OD-20261008-000002
            productName: 상품 2
            quantity: 1
            unitPrice: 5000
            totalAmount: 5000
            discountAmount: 0
            payableAmount: 5000
            status: PENDING_PAYMENT
            buyerName: 구매자
            createdAt: '2026-10-08T00:00:00Z'
            expiresAt: '2026-10-08T00:10:00Z'
            cancelledAt: null
            groupNumber: PG-00000000-0000-0000-0000-000000000149
          paymentId: null
  '204':
    description: 활성 묶음 없음, 본문 없음
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/{number}

본인 결제 묶음과 상품별 주문 조회

다른 회원 묶음과 없는 묶음은 동일 404. 합계와 상품별 주문 목록 및 결제 시도 ID를 반환한다. 각 주문은 내 주문 목록·상세에서 별도로 조회된다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| groupNumber | string | 필수 |  | — |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;maxLength&quot;: 64} |
| expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 실행 접수부터600초. 재전송으로 연장하지 않는다. 실행 전 및 V105 이전 확인중 주문은 null. | {&quot;format&quot;: &quot;date-time&quot;} |
| orders | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| orders&#91;&#93;.orderNumber | string | 필수 |  | — |
| orders&#91;&#93;.groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| orders&#91;&#93;.productName | string | 필수 |  | — |
| orders&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| orders&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| orders&#91;&#93;.buyerName | string | 필수 |  | — |
| orders&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| orders&#91;&#93;.salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

```json
{
  "groupNumber": "PG-00000000-0000-0000-0000-000000000149",
  "status": "PENDING_PAYMENT",
  "totalAmount": 25000,
  "discountAmount": 0,
  "payableAmount": 25000,
  "couponId": null,
  "expiresAt": "2026-10-08T00:10:00Z",
  "processingExpiresAt": null,
  "orders": [
    {
      "orderNumber": "OD-20261008-000001",
      "productName": "상품 1",
      "quantity": 2,
      "unitPrice": 10000,
      "totalAmount": 20000,
      "discountAmount": 0,
      "payableAmount": 20000,
      "status": "PENDING_PAYMENT",
      "buyerName": "구매자",
      "createdAt": "2026-10-08T00:00:00Z",
      "expiresAt": "2026-10-08T00:10:00Z",
      "cancelledAt": null,
      "groupNumber": "PG-00000000-0000-0000-0000-000000000149"
    },
    {
      "orderNumber": "OD-20261008-000002",
      "productName": "상품 2",
      "quantity": 1,
      "unitPrice": 5000,
      "totalAmount": 5000,
      "discountAmount": 0,
      "payableAmount": 5000,
      "status": "PENDING_PAYMENT",
      "buyerName": "구매자",
      "createdAt": "2026-10-08T00:00:00Z",
      "expiresAt": "2026-10-08T00:10:00Z",
      "cancelledAt": null,
      "groupNumber": "PG-00000000-0000-0000-0000-000000000149"
    }
  ],
  "paymentId": null
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
tags:
- Payment Groups
summary: 본인 결제 묶음과 상품별 주문 조회
operationId: getMyPaymentGroup
description: 다른 회원 묶음과 없는 묶음은 동일 404. 합계와 상품별 주문 목록 및 결제 시도 ID를 반환한다. 각 주문은 내 주문 목록·상세에서
  별도로 조회된다.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentGroup'
        example:
          groupNumber: PG-00000000-0000-0000-0000-000000000149
          status: PENDING_PAYMENT
          totalAmount: 25000
          discountAmount: 0
          payableAmount: 25000
          couponId: null
          expiresAt: '2026-10-08T00:10:00Z'
          processingExpiresAt: null
          orders:
          - orderNumber: OD-20261008-000001
            productName: 상품 1
            quantity: 2
            unitPrice: 10000
            totalAmount: 20000
            discountAmount: 0
            payableAmount: 20000
            status: PENDING_PAYMENT
            buyerName: 구매자
            createdAt: '2026-10-08T00:00:00Z'
            expiresAt: '2026-10-08T00:10:00Z'
            cancelledAt: null
            groupNumber: PG-00000000-0000-0000-0000-000000000149
          - orderNumber: OD-20261008-000002
            productName: 상품 2
            quantity: 1
            unitPrice: 5000
            totalAmount: 5000
            discountAmount: 0
            payableAmount: 5000
            status: PENDING_PAYMENT
            buyerName: 구매자
            createdAt: '2026-10-08T00:00:00Z'
            expiresAt: '2026-10-08T00:10:00Z'
            cancelledAt: null
            groupNumber: PG-00000000-0000-0000-0000-000000000149
          paymentId: null
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/payment-groups/{number}/payments

통합 결제 시작

양수 결제 금액은 Mock gateway 승인을 한 번 요청한다. 결제 금액이 0원이면 금전 승인을 요청하지 않고 주문·재고·쿠폰을 동기 확정하며 응답 시 결제 시도와 묶음 상태가 SUCCESS/PAID다. 같은 X-Idempotency-Key 재전송은 기존 결제 시도를 반환한다. 이미 시작된 결제에 다른 키를 보내면 409. 결제 전 만료시각을 검사하며 기한이 지난 묶음은 시작할 수 없다. 양수 승인 결과 불명/응답 유실은 확인 중으로 유지하고 조회·재확인으로 복구한다. Mock 승인 결과는 주문 반영 트랜잭션과 별도로 보관하여 승인 뒤 반영 실패에도 같은 시도를 재확인한다. 실제 외부 PG 승인을 보장하는 구현은 아니다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |
| X-Idempotency-Key | header | 필수 | string | 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며 재전송 HTTP 상태는 각 API의 응답 정의를 따른다. |

### 요청 본문

본문: 선택

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

```json
{}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 0원 결제 즉시 완료 또는 완료된 동일 결제 시도 재전송 |
| 202 | 양수 결제 승인 요청 접수 또는 진행 중인 동일 결제 시도 재전송 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;, &quot;readOnly&quot;: true} |
| status | string | 필수 | 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은 첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다. 이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가 기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며 새 시간 초과 결과는 UNKNOWN으로 처리한다. | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;, &quot;TIMEOUT&quot;&#93;} |
| requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| scheduledResolveAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은 남는다. | {&quot;format&quot;: &quot;date-time&quot;} |

```json
{
  "paymentId": 2,
  "orderId": 2,
  "scenario": "INSTANT_SUCCESS",
  "status": "SUCCESS",
  "requestedAt": "2026-10-08T00:00:00Z",
  "resolvedAt": "2026-10-08T00:00:00Z",
  "scheduledResolveAt": "2026-10-08T00:00:00Z"
}
```

#### 202 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;, &quot;readOnly&quot;: true} |
| status | string | 필수 | 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은 첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다. 이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가 기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며 새 시간 초과 결과는 UNKNOWN으로 처리한다. | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;, &quot;TIMEOUT&quot;&#93;} |
| requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| scheduledResolveAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은 남는다. | {&quot;format&quot;: &quot;date-time&quot;} |

```json
{
  "paymentId": 1,
  "orderId": 1,
  "scenario": "INSTANT_SUCCESS",
  "status": "PROCESSING",
  "requestedAt": "2026-10-08T00:00:00Z",
  "resolvedAt": null,
  "scheduledResolveAt": "2026-10-08T00:00:00Z"
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
tags:
- Payment Groups
summary: 통합 결제 시작
operationId: startMyGroupPayment
description: 양수 결제 금액은 Mock gateway 승인을 한 번 요청한다. 결제 금액이 0원이면 금전 승인을 요청하지 않고 주문·재고·쿠폰을
  동기 확정하며 응답 시 결제 시도와 묶음 상태가 SUCCESS/PAID다. 같은 X-Idempotency-Key 재전송은 기존 결제 시도를 반환한다.
  이미 시작된 결제에 다른 키를 보내면 409. 결제 전 만료시각을 검사하며 기한이 지난 묶음은 시작할 수 없다. 양수 승인 결과 불명/응답 유실은
  확인 중으로 유지하고 조회·재확인으로 복구한다. Mock 승인 결과는 주문 반영 트랜잭션과 별도로 보관하여 승인 뒤 반영 실패에도 같은 시도를
  재확인한다. 실제 외부 PG 승인을 보장하는 구현은 아니다.
x-required-roles:
- USER
- SELLER
parameters:
- $ref: '#/components/parameters/RequiredIdempotencyKey'
requestBody:
  required: false
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/StartPaymentRequest'
      example: {}
responses:
  '200':
    description: 0원 결제 즉시 완료 또는 완료된 동일 결제 시도 재전송
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentAttempt'
        example:
          paymentId: 2
          orderId: 2
          scenario: INSTANT_SUCCESS
          status: SUCCESS
          requestedAt: '2026-10-08T00:00:00Z'
          resolvedAt: '2026-10-08T00:00:00Z'
          scheduledResolveAt: '2026-10-08T00:00:00Z'
  '202':
    description: 양수 결제 승인 요청 접수 또는 진행 중인 동일 결제 시도 재전송
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentAttempt'
        example:
          paymentId: 1
          orderId: 1
          scenario: INSTANT_SUCCESS
          status: PROCESSING
          requestedAt: '2026-10-08T00:00:00Z'
          resolvedAt: null
          scheduledResolveAt: '2026-10-08T00:00:00Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/{number}/payments/{id}

통합 결제 시도 결과 조회

본인 묶음에 연결된 결제 시도만 조회한다. 타인 묶음·다른 묶음의 시도·없는 시도는 동일 404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;, &quot;readOnly&quot;: true} |
| status | string | 필수 | 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은 첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다. 이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가 기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며 새 시간 초과 결과는 UNKNOWN으로 처리한다. | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;, &quot;TIMEOUT&quot;&#93;} |
| requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| scheduledResolveAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은 남는다. | {&quot;format&quot;: &quot;date-time&quot;} |

```json
{
  "paymentId": 1,
  "orderId": 1,
  "scenario": "INSTANT_SUCCESS",
  "status": "PROCESSING",
  "requestedAt": "2026-10-08T00:00:00Z",
  "resolvedAt": null,
  "scheduledResolveAt": "2026-10-08T00:00:00Z"
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
tags:
- Payment Groups
summary: 통합 결제 시도 결과 조회
operationId: getMyGroupPayment
description: 본인 묶음에 연결된 결제 시도만 조회한다. 타인 묶음·다른 묶음의 시도·없는 시도는 동일 404.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PaymentAttempt'
        example:
          paymentId: 1
          orderId: 1
          scenario: INSTANT_SUCCESS
          status: PROCESSING
          requestedAt: '2026-10-08T00:00:00Z'
          resolvedAt: null
          scheduledResolveAt: '2026-10-08T00:00:00Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
  - $ref: '#/components/parameters/GroupPaymentId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/payment-groups/{number}/cancel

결제 전 전체 주문 취소

PENDING_PAYMENT 묶음의 상품별 주문 전체를 취소하고 예약 재고를 함께 반환한다. CANCELLED 재전송은 204. 결제 확인 중·완료·실패·만료는 409. 이 경로는 묶음 전체 취소만 지원하며 결제 후 환불은 별도 환불 API를 사용한다. 카트는 삭제하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 전체 취소 및 예약 재고 복구, 본문 없음 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

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
tags:
- Payment Groups
summary: 결제 전 전체 주문 취소
operationId: cancelMyPaymentGroup
description: PENDING_PAYMENT 묶음의 상품별 주문 전체를 취소하고 예약 재고를 함께 반환한다. CANCELLED 재전송은 204.
  결제 확인 중·완료·실패·만료는 409. 이 경로는 묶음 전체 취소만 지원하며 결제 후 환불은 별도 환불 API를 사용한다. 카트는 삭제하지 않는다.
x-required-roles:
- USER
- SELLER
responses:
  '204':
    description: 전체 취소 및 예약 재고 복구, 본문 없음
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/{number}/refundable-targets

결제 묶음의 선택 환불 대상 조회

commercecore.refunds.api-enabled=true일 때만 제공한다. 본인 결제 묶음의 주문별 할인 반영 환불액과 선택 가능 여부를 반환한다. cartItemId가 없는 직접 주문·레거시 주문 및 연결이 모호한 주문은 선택 환불에서 제외된다. 전체 환불 정책은 변경하지 않는다. 조회 결과는 예약이 아니므로 접수 시 소유권·168시간 기한·결제 상태·중복 대상을 다시 검증한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 주문별 환불 대상과 불가 이유 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 404 | 본인 결제 묶음이 없거나 API가 비활성화됨 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.productName | string | 필수 |  | — |
| data&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data&#91;&#93;.refundAmount | integer | 필수 | 대상 주문 전체 수량의 할인 반영 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.refundable | boolean | 필수 |  | — |
| data&#91;&#93;.unavailableReason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 선택 가능하면 null. ALREADY_REQUESTED는 처리 중·결과 확인 중·완료 대상을 포함한다. | {&quot;enum&quot;: &#91;null, &quot;PAYMENT_NOT_COMPLETED&quot;, &quot;REFUND_WINDOW_EXPIRED&quot;, &quot;ALREADY_REQUESTED&quot;, &quot;CART_ITEM_UNAVAILABLE&quot;, &quot;CART_ITEM_AMBIGUOUS&quot;&#93;} |
| error | null | 필수 |  | — |

#### 401 · application/json

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
tags:
- Payment Groups
operationId: listRefundableTargets
summary: 결제 묶음의 선택 환불 대상 조회
description: commercecore.refunds.api-enabled=true일 때만 제공한다. 본인 결제 묶음의 주문별 할인 반영 환불액과
  선택 가능 여부를 반환한다. cartItemId가 없는 직접 주문·레거시 주문 및 연결이 모호한 주문은 선택 환불에서 제외된다. 전체 환불 정책은
  변경하지 않는다. 조회 결과는 예약이 아니므로 접수 시 소유권·168시간 기한·결제 상태·중복 대상을 다시 검증한다.
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
responses:
  '200':
    description: 주문별 환불 대상과 불가 이유
    content:
      application/json:
        schema:
          type: object
          required:
          - success
          - data
          - error
          properties:
            success:
              const: true
            data:
              type: array
              items:
                $ref: '#/components/schemas/RefundableTarget'
            error:
              type: 'null'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    description: 본인 결제 묶음이 없거나 API가 비활성화됨
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/payment-groups/{number}/refunds

본인 결제 묶음 전체 또는 장바구니 항목 전체 수량 환불 접수

환불 API와 자동 실행·DB 복구는 기본 활성화된다. commercecore.refunds.api-enabled=false로 경로를 비활성화하면 404이다. execution-enabled=false는 새 자동 실행·스캔을 중지하며 이미 접수한 요청은 DB에 보존되고 재활성화 시 복구한다.
회원 본인 결제 묶음만 접수한다. 본문 생략은 결제 묶음 전체 환불, 본문을 보내면 비어 있지 않은 cartItemIds 배열이 필요하다. 값 지정은 해당 장바구니 항목 ID로 생성된 주문의 전체 수량을 뜻하며 빈 배열/null/빈 객체/알 수 없는 필드는 400으로 거절한다. 금액은 서버에 저장된 주문별 실부담액으로 계산하며 요청 금액/주문번호/부분 수량은 받지 않는다.
결제 완료 후 168시간 경계 직전까지 서버 접수해야 한다. 장바구니 행은 결제 뒤 삭제되므로 주문 source_cart_item_id를 검증한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |
| Idempotency-Key | header | 필수 | string | 환불 요청 멱등 키. 회원별 같은 입력은 기존 요청을 반환하며 다른 결제 묶음/장바구니 ID 입력은 409. |

### 요청 본문

본문: 선택

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| cartItemIds | array | 필수 | 하나 이상의 장바구니 항목 ID. ID별 연결 주문의 전체 수량을 환불한다. 전체 묶음 환불은 요청 본문을 생략한다. | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |

```json
{
  "cartItemIds": [
    101
  ]
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 환불 접수 및 대상·실부담액 스냅샷 저장 |
| 200 | 동일 멱등 키·동일 입력 재요청, 기존 요청 반환 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.paymentGroupNumber | string | 필수 |  | — |
| data.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.cumulativeRefundAmount | integer | 필수 | 결제 묶음에서 SUCCESS 확정된 환불 요청의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.targets | array | 필수 |  | — |
| data.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productName | string | 필수 |  | — |
| data.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data.recovery.retryExhausted | boolean | 필수 |  | — |
| data.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "id": 51,
    "paymentGroupNumber": "PG-EXAMPLE",
    "refundAmount": 1900,
    "cumulativeRefundAmount": 0,
    "status": "PROCESSING",
    "requestedAt": "2026-10-09T00:00:00Z",
    "resolvedAt": null,
    "targets": [
      {
        "cartItemId": 101,
        "orderId": 301,
        "productId": 1,
        "productName": "A",
        "quantity": 2,
        "refundAmount": 1900
      }
    ],
    "recovery": {
      "retryCount": 0,
      "retryExhausted": false,
      "nextActionAt": "2026-10-09T00:00:00Z"
    }
  },
  "error": null
}
```

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.paymentGroupNumber | string | 필수 |  | — |
| data.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.cumulativeRefundAmount | integer | 필수 | 결제 묶음에서 SUCCESS 확정된 환불 요청의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.targets | array | 필수 |  | — |
| data.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productName | string | 필수 |  | — |
| data.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data.recovery.retryExhausted | boolean | 필수 |  | — |
| data.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
tags:
- Payment Groups
operationId: requestMyRefund
summary: 본인 결제 묶음 전체 또는 장바구니 항목 전체 수량 환불 접수
description: '환불 API와 자동 실행·DB 복구는 기본 활성화된다. commercecore.refunds.api-enabled=false로
  경로를 비활성화하면 404이다. execution-enabled=false는 새 자동 실행·스캔을 중지하며 이미 접수한 요청은 DB에 보존되고
  재활성화 시 복구한다.

  회원 본인 결제 묶음만 접수한다. 본문 생략은 결제 묶음 전체 환불, 본문을 보내면 비어 있지 않은 cartItemIds 배열이 필요하다. 값
  지정은 해당 장바구니 항목 ID로 생성된 주문의 전체 수량을 뜻하며 빈 배열/null/빈 객체/알 수 없는 필드는 400으로 거절한다. 금액은
  서버에 저장된 주문별 실부담액으로 계산하며 요청 금액/주문번호/부분 수량은 받지 않는다.

  결제 완료 후 168시간 경계 직전까지 서버 접수해야 한다. 장바구니 행은 결제 뒤 삭제되므로 주문 source_cart_item_id를 검증한다.'
x-required-roles:
- USER
- SELLER
parameters:
- $ref: '#/components/parameters/RequiredRefundIdempotencyKey'
requestBody:
  required: false
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RefundRequest'
      example:
        cartItemIds:
        - 101
responses:
  '201':
    description: 환불 접수 및 대상·실부담액 스냅샷 저장
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RefundViewEnvelope'
        example:
          success: true
          data:
            id: 51
            paymentGroupNumber: PG-EXAMPLE
            refundAmount: 1900
            cumulativeRefundAmount: 0
            status: PROCESSING
            requestedAt: '2026-10-09T00:00:00Z'
            resolvedAt: null
            targets:
            - cartItemId: 101
              orderId: 301
              productId: 1
              productName: A
              quantity: 2
              refundAmount: 1900
            recovery:
              retryCount: 0
              retryExhausted: false
              nextActionAt: '2026-10-09T00:00:00Z'
          error: null
  '200':
    description: 동일 멱등 키·동일 입력 재요청, 기존 요청 반환
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RefundViewEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/{number}/refunds

본인 결제 묶음 환불 이력



**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 회원 본인 결제 묶음의 환불 요청 목록 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.paymentGroupNumber | string | 필수 |  | — |
| data&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.cumulativeRefundAmount | integer | 필수 | 결제 묶음에서 SUCCESS 확정된 환불 요청의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data&#91;&#93;.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.targets | array | 필수 |  | — |
| data&#91;&#93;.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.productName | string | 필수 |  | — |
| data&#91;&#93;.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data&#91;&#93;.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data&#91;&#93;.recovery.retryExhausted | boolean | 필수 |  | — |
| data&#91;&#93;.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

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
tags:
- Payment Groups
operationId: listMyRefunds
summary: 본인 결제 묶음 환불 이력
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 회원 본인 결제 묶음의 환불 요청 목록
    content:
      application/json:
        schema:
          type: object
          required:
          - success
          - data
          - error
          properties:
            success:
              const: true
            data:
              type: array
              items:
                $ref: '#/components/schemas/RefundView'
            error:
              type: 'null'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/payment-groups/{number}/refunds/{id}

본인 환불 요청 상세



**인증:** `[{"BearerAuth": []}]`
**태그:** Payment Groups

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string | 본인 결제 묶음 번호 |
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 환불 요청과 전체 대상 상세 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.paymentGroupNumber | string | 필수 |  | — |
| data.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.cumulativeRefundAmount | integer | 필수 | 결제 묶음에서 SUCCESS 확정된 환불 요청의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.targets | array | 필수 |  | — |
| data.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productName | string | 필수 |  | — |
| data.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data.recovery.retryExhausted | boolean | 필수 |  | — |
| data.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
tags:
- Payment Groups
operationId: getMyRefund
summary: 본인 환불 요청 상세
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 환불 요청과 전체 대상 상세
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RefundViewEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/GroupNumber'
  - $ref: '#/components/parameters/RefundId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/seller/refunds

본인 대상 주문의 환불 상태 목록

혼합 판매자 요청에서 본인 대상 주문·실부담액만 반환한다. 결제 묶음 총액·다른 판매자 주문과 대상은 반환하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| page | query | 선택 | integer |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 판매자 본인 대상 환불만 조회 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.refundAmount | integer | 필수 | 해당 판매자 대상 주문만의 실부담액 합계 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.cumulativeRefundAmount | integer | 필수 | 해당 판매자 주문에서 SUCCESS 확정된 환불의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data&#91;&#93;.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.targets | array | 필수 |  | — |
| data&#91;&#93;.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.productName | string | 필수 |  | — |
| data&#91;&#93;.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data&#91;&#93;.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data&#91;&#93;.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data&#91;&#93;.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data&#91;&#93;.recovery.retryExhausted | boolean | 필수 |  | — |
| data&#91;&#93;.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

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
tags:
- Seller Sales
operationId: listMySellerRefunds
summary: 본인 대상 주문의 환불 상태 목록
description: 혼합 판매자 요청에서 본인 대상 주문·실부담액만 반환한다. 결제 묶음 총액·다른 판매자 주문과 대상은 반환하지 않는다.
x-required-roles:
- SELLER
parameters:
- name: page
  in: query
  schema:
    type: integer
    minimum: 0
    default: 0
- name: size
  in: query
  schema:
    type: integer
    minimum: 1
    maximum: 100
    default: 20
responses:
  '200':
    description: 판매자 본인 대상 환불만 조회
    content:
      application/json:
        schema:
          type: object
          required:
          - success
          - data
          - error
          properties:
            success:
              const: true
            data:
              type: array
              items:
                $ref: '#/components/schemas/SellerRefundView'
            error:
              type: 'null'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/seller/refunds/{id}

본인 대상 주문의 환불 상세

요청의 판매자별 대상 금액과 주문만 반환하며 타 판매자 정보와 결제 묶음 총액은 숨긴다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 판매자 본인 환불 대상만 조회 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.refundAmount | integer | 필수 | 해당 판매자 대상 주문만의 실부담액 합계 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.cumulativeRefundAmount | integer | 필수 | 해당 판매자 주문에서 SUCCESS 확정된 환불의 누적 금액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PROCESSING&quot;, &quot;UNKNOWN&quot;, &quot;SUCCESS&quot;, &quot;FAILED&quot;&#93;} |
| data.requestedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.resolvedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.targets | array | 필수 |  | — |
| data.targets&#91;&#93;.cartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 레거시 주문에 원본 cart item ID가 없으면 null | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.orderId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.productName | string | 필수 |  | — |
| data.targets&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data.targets&#91;&#93;.refundAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.recovery | &#91;&#x27;object&#x27;, &#x27;null&#x27;&#93; | 필수 | PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가 재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리 대상 시각이며 완료 시각을 보장하지 않는다. | — |
| data.recovery.retryCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 3} |
| data.recovery.retryExhausted | boolean | 필수 |  | — |
| data.recovery.nextActionAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
tags:
- Seller Sales
operationId: getMySellerRefund
summary: 본인 대상 주문의 환불 상세
description: 요청의 판매자별 대상 금액과 주문만 반환하며 타 판매자 정보와 결제 묶음 총액은 숨긴다.
x-required-roles:
- SELLER
responses:
  '200':
    description: 판매자 본인 환불 대상만 조회
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SellerRefundViewEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/RefundId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PUT /v1/dev/payment-scenarios/{orderNumber}

본인 주문 Mock 결과 지정

local 또는 test 프로필 AND dev/prod 비활성 AND commercecore.dev.payment-scenario.enabled=true에서만 제공. SELLER도 본인 주문만 허용. 외부 dev/prod는 금지.

**인증:** `[{"BearerAuth": []}]`
**태그:** Local Test

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| scenario | string | 필수 | Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다. SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초 응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로 승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도 복구 정책이다. | {&quot;enum&quot;: &#91;&quot;INSTANT_SUCCESS&quot;, &quot;INSTANT_FAIL&quot;, &quot;DELAYED_SUCCESS&quot;, &quot;DELAYED_FAIL&quot;, &quot;SUCCESS_RESPONSE_LOST&quot;, &quot;FAILURE_RESPONSE_LOST&quot;, &quot;UNAVAILABLE_BEFORE_RESULT&quot;&#93;} |

```json
{
  "scenario": "DELAYED_SUCCESS"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 지정, 본문 없음 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

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
tags:
- Local Test
summary: 본인 주문 Mock 결과 지정
operationId: setLocalPaymentScenario
description: local 또는 test 프로필 AND dev/prod 비활성 AND commercecore.dev.payment-scenario.enabled=true에서만
  제공. SELLER도 본인 주문만 허용. 외부 dev/prod는 금지.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/SetPaymentScenarioRequest'
      example:
        scenario: DELAYED_SUCCESS
x-required-roles:
- SELLER
x-availability: opt-in local/test only
responses:
  '204':
    description: 지정, 본문 없음
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## DELETE /v1/dev/payment-scenarios/{orderNumber}

본인 주문 Mock 결과 지정 제거

PUT과 동일한 opt-in 프로필 및 본인 SELLER 조건.

**인증:** `[{"BearerAuth": []}]`
**태그:** Local Test

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| orderNumber | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 지정 제거, 본문 없음 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

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
tags:
- Local Test
summary: 본인 주문 Mock 결과 지정 제거
operationId: clearLocalPaymentScenario
description: PUT과 동일한 opt-in 프로필 및 본인 SELLER 조건.
x-required-roles:
- SELLER
x-availability: opt-in local/test only
responses:
  '204':
    description: 지정 제거, 본문 없음
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/OrderNumber'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/order-sheets

새 Payment 계약의 주문 생성과 결제 준비

SKU 또는 장바구니 선택으로 주문과 재고·쿠폰 선점, 판매자별 할인후 금액·설정 수수료 스냅샷, Payment 생성 전달 원문을 같은 Core 트랜잭션에 저장한다. 커밋 뒤 Payment 생성을 시도하며 준비 성공도 PENDING을 유지한다. 준비 응답 유실은 같은 orderRef·저장 원문으로 복구한다. 설정 commerce.commission-rate가 필요하며 임의 기본 요율은 없다. 이 새 경로의 결제액과 각 판매자 금액은 팀원 계약대로 양수다. 기존 legacy 0원 API는 바꾸지 않는다. 같은 키·같은 입력은 원래 주문·금액·기한을 돌려준다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| X-Idempotency-Key | header | 필수 | string | 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며 재전송 HTTP 상태는 각 API의 응답 정의를 따른다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| items | &#91;&#x27;array&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| cartItems | &#91;&#x27;array&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxItems&quot;: 100} |
| buyerName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| buyerPhone | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 32} |
| expectedAmount | integer | 필수 | 쿠폰 적용 후 예상 실결제액 | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |
| couponSkuId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 | 쿠폰을 선택한 경우 적용할 주문 항목의 SKU | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;oneOf 1&#93;.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| &#91;oneOf 2&#93;.cartItems | array | 필수 |  | {&quot;minItems&quot;: 1} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 새 주문 저장. paymentId가 null이고 paymentStatus가 PREPARING이면 준비 지연이며 결제 준비 성공이 아니다. |
| 200 | 동일 입력 재요청 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| order | object | 필수 |  | — |
| order.orderNumber | string | 필수 |  | — |
| order.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| order.failureReason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.couponOrderItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| order.items&#91;&#93;.orderItemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.skuId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productName | string | 필수 |  | — |
| order.items&#91;&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| order.items&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.selectedOptions | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| order.items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.sourceCartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.sourceCartVersion | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payment | object | 필수 |  | — |
| payment.orderNumber | string | 필수 |  | — |
| payment.orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| payment.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| payment.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| payment.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| order | object | 필수 |  | — |
| order.orderNumber | string | 필수 |  | — |
| order.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| order.failureReason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.couponOrderItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| order.items&#91;&#93;.orderItemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.skuId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productName | string | 필수 |  | — |
| order.items&#91;&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| order.items&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.selectedOptions | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| order.items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.sourceCartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.sourceCartVersion | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payment | object | 필수 |  | — |
| payment.orderNumber | string | 필수 |  | — |
| payment.orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| payment.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| payment.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| payment.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: createRelayOrderSheet
summary: 새 Payment 계약의 주문 생성과 결제 준비
description: SKU 또는 장바구니 선택으로 주문과 재고·쿠폰 선점, 판매자별 할인후 금액·설정 수수료 스냅샷, Payment 생성 전달
  원문을 같은 Core 트랜잭션에 저장한다. 커밋 뒤 Payment 생성을 시도하며 준비 성공도 PENDING을 유지한다. 준비 응답 유실은 같은
  orderRef·저장 원문으로 복구한다. 설정 commerce.commission-rate가 필요하며 임의 기본 요율은 없다. 이 새 경로의 결제액과
  각 판매자 금액은 팀원 계약대로 양수다. 기존 legacy 0원 API는 바꾸지 않는다. 같은 키·같은 입력은 원래 주문·금액·기한을 돌려준다.
x-required-roles:
- USER
- SELLER
parameters:
- $ref: '#/components/parameters/RequiredIdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RelayOrderRequest'
responses:
  '201':
    description: 새 주문 저장. paymentId가 null이고 paymentStatus가 PREPARING이면 준비 지연이며 결제
      준비 성공이 아니다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayOrderView'
  '200':
    description: 동일 입력 재요청
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayOrderView'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/order-sheets/active

본인의 유효한 새 활성 주문서 복구

새 Payment 연결이 있는 PENDING/PROCESSING 주문을 반환한다. 기한 도달은 같은 잠금 경로에서 만료·해제한 뒤 없으면204다. legacy 활성 조회는 별도다. 이전 policy1/2 미연결 진행중 주문은 없음으로 숨기지 않고409로 보고하며 새 주문 자동 생성·재승인하지 않는다. 조회는 Payment HTTP를 실행하지 않고 기한을 연장하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 본인 새 활성 주문서 |
| 204 | 새 활성 주문서 없음. legacy active도 별도로 확인해야 한다. |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| order | object | 필수 |  | — |
| order.orderNumber | string | 필수 |  | — |
| order.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| order.failureReason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.couponOrderItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| order.items&#91;&#93;.orderItemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.skuId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productName | string | 필수 |  | — |
| order.items&#91;&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| order.items&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.selectedOptions | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| order.items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.sourceCartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.sourceCartVersion | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payment | object | 필수 |  | — |
| payment.orderNumber | string | 필수 |  | — |
| payment.orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| payment.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| payment.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| payment.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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
tags:
- Orders
operationId: getActiveRelayOrderSheet
summary: 본인의 유효한 새 활성 주문서 복구
description: 새 Payment 연결이 있는 PENDING/PROCESSING 주문을 반환한다. 기한 도달은 같은 잠금 경로에서 만료·해제한
  뒤 없으면204다. legacy 활성 조회는 별도다. 이전 policy1/2 미연결 진행중 주문은 없음으로 숨기지 않고409로 보고하며 새 주문
  자동 생성·재승인하지 않는다. 조회는 Payment HTTP를 실행하지 않고 기한을 연장하지 않는다.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 본인 새 활성 주문서
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayOrderView'
  '204':
    description: 새 활성 주문서 없음. legacy active도 별도로 확인해야 한다.
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/order-sheets/{number}

본인 주문과 결제·늦은 승인 환불 상태



**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 현재 주문·결제 상태. 저장 기한 도달 시 만료를 반영한다. |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| order | object | 필수 |  | — |
| order.orderNumber | string | 필수 |  | — |
| order.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| order.failureReason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| order.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.couponOrderItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| order.items&#91;&#93;.orderItemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.skuId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.productName | string | 필수 |  | — |
| order.items&#91;&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| order.items&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.selectedOptions | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| order.items&#91;&#93;.quantity | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| order.items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| order.items&#91;&#93;.sourceCartItemId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| order.items&#91;&#93;.sourceCartVersion | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payment | object | 필수 |  | — |
| payment.orderNumber | string | 필수 |  | — |
| payment.orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| payment.paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| payment.pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| payment.amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| payment.expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| payment.refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: getRelayOrderSheet
summary: 본인 주문과 결제·늦은 승인 환불 상태
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
responses:
  '200':
    description: 현재 주문·결제 상태. 저장 기한 도달 시 만료를 반영한다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayOrderView'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/order-sheets/{number}/payment

저장된 결제 생성 원문 재전달

유효한 PENDING에서만 준비한다. 성공해도 승인 실행이 아니므로 처리 기한을 시작하지 않는다. 이미 준비된 결제창을 새로 열 때는 checkouts를 사용한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 현재 준비 상태 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: prepareRelayPayment
summary: 저장된 결제 생성 원문 재전달
description: 유효한 PENDING에서만 준비한다. 성공해도 승인 실행이 아니므로 처리 기한을 시작하지 않는다. 이미 준비된 결제창을 새로
  열 때는 checkouts를 사용한다.
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
responses:
  '200':
    description: 현재 준비 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayPaymentState'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/order-sheets/{number}/payment/checkouts

PENDING 주문의 새 결제창 요청

Payment의 비멱등 checkout 계약을 사용하므로 호출마다 pgOrderId가 바뀔 수 있다. 미준비 상태면 저장된 생성을 재전달한다. 인증 후 승인에는 가장 최근 pgOrderId를 사용한다. PENDING 생성 기한은 연장하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 현재 결제창·준비 상태 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: openRelayCheckout
summary: PENDING 주문의 새 결제창 요청
description: Payment의 비멱등 checkout 계약을 사용하므로 호출마다 pgOrderId가 바뀔 수 있다. 미준비 상태면 저장된
  생성을 재전달한다. 인증 후 승인에는 가장 최근 pgOrderId를 사용한다. PENDING 생성 기한은 연장하지 않는다.
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
responses:
  '200':
    description: 현재 결제창·준비 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayPaymentState'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/order-sheets/{number}/payment/approval

인증 완료 후 승인 실행 접수

JWT 구매자와 저장 금액·현재 결제창을 확인한다. 유효 PENDING을 PROCESSING으로 바꾸며 첫 전환+600초와 승인 HTTP 전달 원문을 같은 Core 트랜잭션에 저장한다. 같은 키·원문은 같은 전달 요청이며 기한을 연장하지 않는다. 202와 UNKNOWN은 실패가 아니다. HTTP 응답 유실은 조회·같은 pgOrderId 원문 재전달로 복구한다. 기한 후에는 새 승인을 보내지 않고 조회만 하며, 늦은 승인은 주문을 복구하지 않고 RefundRequested를 기록한다. Payment 실패를 확정할 수 없는 조회 상태는 실패로 추정하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |
| X-Idempotency-Key | header | 필수 | string | 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며 재전송 HTTP 상태는 각 API의 응답 정의를 따른다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| paymentKey | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 200} |
| pgOrderId | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 현재 완료·실패·종료 상태. orderStatus와 refundStatus를 확인하며 HTTP 200 자체는 결제 성공을 뜻하지 않는다. |
| 202 | 승인 결과 미확정 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

#### 202 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| orderNumber | string | 필수 |  | — |
| orderStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING&quot;, &quot;PROCESSING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;EXPIRED&quot;, &quot;CANCELLED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;&#93;} |
| paymentId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| paymentStatus | string | 필수 | PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED 등. 주문 상태와 구분한다. | — |
| pgOrderId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| amount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expiresAt | string | 필수 | 변경하지 않는 PENDING 생성 기한 | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| refundStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다. | {&quot;enum&quot;: &#91;null, &quot;REQUESTED&quot;, &quot;SUCCEEDED&quot;, &quot;FAILED&quot;&#93;} |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: approveRelayPayment
summary: 인증 완료 후 승인 실행 접수
description: JWT 구매자와 저장 금액·현재 결제창을 확인한다. 유효 PENDING을 PROCESSING으로 바꾸며 첫 전환+600초와
  승인 HTTP 전달 원문을 같은 Core 트랜잭션에 저장한다. 같은 키·원문은 같은 전달 요청이며 기한을 연장하지 않는다. 202와 UNKNOWN은
  실패가 아니다. HTTP 응답 유실은 조회·같은 pgOrderId 원문 재전달로 복구한다. 기한 후에는 새 승인을 보내지 않고 조회만 하며, 늦은
  승인은 주문을 복구하지 않고 RefundRequested를 기록한다. Payment 실패를 확정할 수 없는 조회 상태는 실패로 추정하지 않는다.
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
- $ref: '#/components/parameters/RequiredIdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        type: object
        required:
        - paymentKey
        - pgOrderId
        - amount
        properties:
          paymentKey:
            type: string
            minLength: 1
            maxLength: 200
          pgOrderId:
            type: string
            minLength: 1
            maxLength: 64
          amount:
            type: integer
            format: int64
            minimum: 1
responses:
  '200':
    description: 현재 완료·실패·종료 상태. orderStatus와 refundStatus를 확인하며 HTTP 200 자체는 결제 성공을
      뜻하지 않는다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayPaymentState'
  '202':
    description: 승인 결과 미확정
    headers:
      Location:
        schema:
          type: string
        description: 본인 주문 상태 조회 경로
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/RelayPaymentState'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/order-sheets/{number}/cancel

결제 준비 전후 PENDING 주문 취소

준비된 paymentId가 있어도 아직 PENDING이면 취소·선점해제한다. PROCESSING은 즉시 취소하지 않는다. Payment에 주문 종료 API나 취소 이벤트를 보내지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Orders

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| number | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 취소 또는 이미 취소된 상태 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |

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

### 계약 원문

```yaml
tags:
- Orders
operationId: cancelRelayOrderSheet
summary: 결제 준비 전후 PENDING 주문 취소
description: 준비된 paymentId가 있어도 아직 PENDING이면 취소·선점해제한다. PROCESSING은 즉시 취소하지 않는다. Payment에
  주문 종료 API나 취소 이벤트를 보내지 않는다.
x-required-roles:
- USER
- SELLER
parameters:
- name: number
  in: path
  required: true
  schema:
    type: string
responses:
  '204':
    description: 취소 또는 이미 취소된 상태
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/Conflict'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### commercecore-service.yaml

```yaml
components:
  schemas:
    OrderCheckout:
      type: object
      required:
      - productId
      - salesId
      - skuId
      - productName
      - unitPrice
      - quantity
      - totalAmount
      - available
      - salesStatus
      - orderable
      - reason
      - discountAmount
      - payableAmount
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
        salesId:
          type: integer
          format: int64
          minimum: 1
          description: 기존 호환 이름. 신규 호출자는 명시적 skuId 사용
        skuId:
          type: integer
          format: int64
          minimum: 1
          description: 단일 SKU 실제 조회 결과. productId/과거 Sales ID에서 추정하지 않음
        productName:
          type: string
        unitPrice:
          type: integer
          format: int64
          minimum: 1
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
        totalAmount:
          type: integer
          format: int64
          minimum: 1
        available:
          type: integer
          format: int64
          minimum: 0
        salesStatus:
          $ref: '#/components/schemas/SalesStatus'
        orderable:
          type: boolean
        reason:
          type:
          - string
          - 'null'
          enum:
          - NOT_ON_SALE
          - SOLD_OUT
          - EXCEEDS_STOCK
          - null
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
        discountAmount:
          type: integer
          format: int64
          minimum: 0
        payableAmount:
          type: integer
          format: int64
          minimum: 0
    SalesStatus:
      type: string
      enum:
      - READY
      - ON_SALE
      - SOLD_OUT
      - PRIVATE
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
    CreateOrderRequest:
      type: object
      required:
      - productId
      - quantity
      - buyerName
      - buyerPhone
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
        buyerName:
          type: string
          minLength: 1
          maxLength: 64
        buyerPhone:
          type: string
          pattern: ^[0-9-]{9,32}$
          writeOnly: true
        expectedTotalAmount:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
      description: 소유자는 JWT sub이다. 비회원 조회 비밀번호는 지원하지 않는다. expectedTotalAmount는 직접
        주문에서 선택이며 할인 전 금액을 보내면 최신 상품 합계와 일치해야 한다. couponId는 선택이며 같은 멱등키 재요청에서 쿠폰 ID가
        달라지면 409다.
    Order:
      type: object
      required:
      - orderNumber
      - productName
      - quantity
      - unitPrice
      - totalAmount
      - discountAmount
      - payableAmount
      - status
      - buyerName
      - createdAt
      - expiresAt
      - cancelledAt
      - groupNumber
      properties:
        orderNumber:
          type: string
        groupNumber:
          type:
          - string
          - 'null'
          description: 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null.
        productName:
          type: string
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
        unitPrice:
          type: integer
          format: int64
          minimum: 1
        totalAmount:
          type: integer
          format: int64
          minimum: 1
        discountAmount:
          type: integer
          format: int64
          minimum: 0
        payableAmount:
          type: integer
          format: int64
          minimum: 0
        status:
          type: string
          enum:
          - PENDING_PAYMENT
          - PAYMENT_CONFIRMING
          - PAID
          - FAILED
          - CANCELLED
          - EXPIRED
          - REFUNDED
          - PARTIALLY_REFUNDED
        buyerName:
          type: string
        createdAt:
          type: string
          format: date-time
        expiresAt:
          type:
          - string
          - 'null'
          format: date-time
        cancelledAt:
          type:
          - string
          - 'null'
          format: date-time
        salesInfoId:
          anyOf:
          - type: integer
            format: int64
            minimum: 1
          - type: 'null'
          description: 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의
            skuId를 사용한다.
    OrderHistory:
      type: object
      required:
      - items
      - page
      - size
      - totalElements
      - totalPages
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/Order'
        page:
          type: integer
          format: int64
          minimum: 0
        size:
          type: integer
          minimum: 1
          maximum: 100
        totalElements:
          type: integer
          format: int64
          minimum: 0
        totalPages:
          type: integer
          format: int64
          minimum: 0
    StartPaymentRequest:
      type:
      - object
      - 'null'
      maxProperties: 0
      additionalProperties: false
      description: 본문 생략, JSON null 또는 빈 객체만 허용. scenario 등 필드가 하나라도 있으면 400.
    PaymentAttempt:
      type: object
      required:
      - paymentId
      - orderId
      - scenario
      - status
      - requestedAt
      - resolvedAt
      - scheduledResolveAt
      properties:
        paymentId:
          type: integer
          format: int64
          minimum: 1
        orderId:
          type: integer
          format: int64
          minimum: 1
        scenario:
          $ref: '#/components/schemas/PaymentScenario'
          readOnly: true
        status:
          type: string
          enum:
          - PENDING
          - PROCESSING
          - UNKNOWN
          - SUCCESS
          - FAILED
          - TIMEOUT
          description: 신규 결제는 PROCESSING으로 시작한다. UNKNOWN은 승인 결과 미확정이다. Order의 PAYMENT_CONFIRMING은
            첫 실행 접수부터600초 기한이다. UNKNOWN은 기한 전 실패로 확정하지 않으며 주문 만료 뒤 승인은 환불 필요 사실로 기록한다.
            이 스키마는 기존 legacy 경로다. 새 Payment 조회·환불 전달은 별도 order-sheets 중계를 사용한다. 서버가
            기존 결제 결과를 재확인하며 재시도 소진만으로 실패 종료하지 않는다. PENDING·TIMEOUT은 기존 데이터 호환용 값이며
            새 시간 초과 결과는 UNKNOWN으로 처리한다.
        requestedAt:
          type: string
          format: date-time
        resolvedAt:
          type:
          - string
          - 'null'
          format: date-time
        scheduledResolveAt:
          type:
          - string
          - 'null'
          format: date-time
          description: 미확정 결제의 다음 자동 처리 대상 시각이며 완료 시각을 보장하지 않는다. 확정 결제에서도 마지막 예약 시각은
            남는다.
      description: 현재 결제 게이트웨이는 Mock이다. scenario는 서버 결과이며 일반 결제 요청에서 선택할 수 없다.
    PaymentScenario:
      type: string
      enum:
      - INSTANT_SUCCESS
      - INSTANT_FAIL
      - DELAYED_SUCCESS
      - DELAYED_FAIL
      - SUCCESS_RESPONSE_LOST
      - FAILURE_RESPONSE_LOST
      - UNAVAILABLE_BEFORE_RESULT
      description: Mock 결과 시나리오이며 일반 결제 요청에서 선택할 수 없다. local/test의 서버 시나리오 설정으로만 제어한다.
        SUCCESS_RESPONSE_LOST와 FAILURE_RESPONSE_LOST는 각각 승인 성공·확정 실패 결과를 저장한 뒤 최초
        응답을 유실하고 같은 결제 시도 ID의 저장 결과는 보존한다. UNAVAILABLE_BEFORE_RESULT는 결과 저장 전 일시 장애로
        승인·확정 실패 결과를 만들지 않는다. 결과 조회는 저장 결과만 확인하며 새 승인을 만들지 않는다. 자동 재실행 횟수·조회 주기는 별도
        복구 정책이다.
    PaymentGroup:
      type: object
      required:
      - groupNumber
      - status
      - totalAmount
      - discountAmount
      - payableAmount
      - couponId
      - expiresAt
      - processingExpiresAt
      - orders
      - paymentId
      properties:
        groupNumber:
          type: string
        status:
          type: string
          enum:
          - PENDING_PAYMENT
          - PAYMENT_CONFIRMING
          - PAID
          - FAILED
          - CANCELLED
          - EXPIRED
          - REFUNDED
          - PARTIALLY_REFUNDED
        totalAmount:
          type: integer
          format: int64
          minimum: 1
        discountAmount:
          type: integer
          format: int64
          minimum: 0
        payableAmount:
          type: integer
          format: int64
          minimum: 0
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
        expiresAt:
          type: string
          format: date-time
        processingExpiresAt:
          type:
          - string
          - 'null'
          format: date-time
          description: 첫 승인 실행 접수부터600초. 재전송으로 연장하지 않는다. 실행 전 및 V105 이전 확인중 주문은 null.
        orders:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/Order'
        paymentId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
      description: 기존 productId API는 SKU가 하나인 상품만 지원한다. Sales 이력 없는 신규 SKU도 지원하며 SKU가
        없으면404, 여러 개면409이고 신규 SKU 공개 주문은 후속이다. Cart 옵션 SKU를 임의 기본 SKU로 바꾸지 않는다. 이미
        생성된 버전0은 원래 가격과 TTL로 완료·해제한다.  totalAmount는 할인 전 주문 원금 합계, discountAmount는
        할인 합계, payableAmount는 결제 실부담액이다. 결제 시작 전 paymentId는 null. payableAmount가 0인
        묶음은 결제 시작 요청 안에서 금전 승인 없이 PAID로 확정된다. 공개 구API는 policy0으로 일부 항목 환불 뒤 PAID를
        유지하고 전량 환불이면 REFUNDED다. 새 실행은 processingExpiresAt 기한에 만료하고 선점을 해제한다. V105
        이전 확인중 주문은 기한을 임의 역산하지 않아 processingExpiresAt이 null이다. 신규 내부 주문서 공개 결제/수량환불
        및 새 Payment 생성·승인·환불 이벤트 연동은 미완이다.
    RefundableTarget:
      type: object
      required:
      - orderId
      - cartItemId
      - productName
      - quantity
      - refundAmount
      - refundable
      - unavailableReason
      properties:
        orderId:
          type: integer
          format: int64
        cartItemId:
          type:
          - integer
          - 'null'
          format: int64
        productName:
          type: string
        quantity:
          type: integer
          minimum: 1
        refundAmount:
          type: integer
          format: int64
          minimum: 0
          description: 대상 주문 전체 수량의 할인 반영 금액
        refundable:
          type: boolean
        unavailableReason:
          type:
          - string
          - 'null'
          enum:
          - null
          - PAYMENT_NOT_COMPLETED
          - REFUND_WINDOW_EXPIRED
          - ALREADY_REQUESTED
          - CART_ITEM_UNAVAILABLE
          - CART_ITEM_AMBIGUOUS
          description: 선택 가능하면 null. ALREADY_REQUESTED는 처리 중·결과 확인 중·완료 대상을 포함한다.
    RefundRequest:
      type: object
      additionalProperties: false
      required:
      - cartItemIds
      properties:
        cartItemIds:
          type: array
          minItems: 1
          maxItems: 100
          uniqueItems: true
          items:
            type: integer
            format: int64
            minimum: 1
          description: 하나 이상의 장바구니 항목 ID. ID별 연결 주문의 전체 수량을 환불한다. 전체 묶음 환불은 요청 본문을
            생략한다.
    RefundViewEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: '#/components/schemas/RefundView'
        error:
          type: 'null'
    RefundView:
      type: object
      required:
      - id
      - paymentGroupNumber
      - refundAmount
      - cumulativeRefundAmount
      - status
      - requestedAt
      - resolvedAt
      - targets
      - recovery
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        paymentGroupNumber:
          type: string
        refundAmount:
          type: integer
          format: int64
          minimum: 0
        cumulativeRefundAmount:
          type: integer
          format: int64
          minimum: 0
          description: 결제 묶음에서 SUCCESS 확정된 환불 요청의 누적 금액
        status:
          $ref: '#/components/schemas/RefundStatus'
        requestedAt:
          type: string
          format: date-time
        resolvedAt:
          type:
          - string
          - 'null'
          format: date-time
        targets:
          type: array
          items:
            $ref: '#/components/schemas/RefundTarget'
        recovery:
          $ref: '#/components/schemas/RefundRecovery'
    RefundStatus:
      type: string
      enum:
      - PROCESSING
      - UNKNOWN
      - SUCCESS
      - FAILED
    RefundTarget:
      type: object
      required:
      - cartItemId
      - orderId
      - productId
      - productName
      - quantity
      - refundAmount
      properties:
        cartItemId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
          description: 레거시 주문에 원본 cart item ID가 없으면 null
        orderId:
          type: integer
          format: int64
          minimum: 1
        productId:
          type: integer
          format: int64
          minimum: 1
        productName:
          type: string
        quantity:
          type: integer
          minimum: 1
        refundAmount:
          type: integer
          format: int64
          minimum: 0
    RefundRecovery:
      type:
      - object
      - 'null'
      description: PROCESSING/UNKNOWN의 복구 상태. SUCCESS/FAILED는 null. 최초 실행을 제외한 추가
        재시도 횟수와 소진 여부를 표시한다. 소진 뒤에도 기존 결과를 조회하며 자동 실패 종료하지 않는다. nextActionAt은 다음 처리
        대상 시각이며 완료 시각을 보장하지 않는다.
      required:
      - retryCount
      - retryExhausted
      - nextActionAt
      properties:
        retryCount:
          type: integer
          minimum: 0
          maximum: 3
        retryExhausted:
          type: boolean
        nextActionAt:
          type: string
          format: date-time
    SellerRefundView:
      type: object
      required:
      - id
      - refundAmount
      - cumulativeRefundAmount
      - status
      - requestedAt
      - resolvedAt
      - targets
      - recovery
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        refundAmount:
          type: integer
          format: int64
          minimum: 0
          description: 해당 판매자 대상 주문만의 실부담액 합계
        cumulativeRefundAmount:
          type: integer
          format: int64
          minimum: 0
          description: 해당 판매자 주문에서 SUCCESS 확정된 환불의 누적 금액
        status:
          $ref: '#/components/schemas/RefundStatus'
        requestedAt:
          type: string
          format: date-time
        resolvedAt:
          type:
          - string
          - 'null'
          format: date-time
        targets:
          type: array
          items:
            $ref: '#/components/schemas/RefundTarget'
        recovery:
          $ref: '#/components/schemas/RefundRecovery'
    SellerRefundViewEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: '#/components/schemas/SellerRefundView'
        error:
          type: 'null'
    SetPaymentScenarioRequest:
      type: object
      required:
      - scenario
      properties:
        scenario:
          $ref: '#/components/schemas/PaymentScenario'
    RelayOrderRequest:
      type: object
      required:
      - buyerName
      - buyerPhone
      - expectedAmount
      oneOf:
      - required:
        - items
        properties:
          items:
            type: array
            minItems: 1
      - required:
        - cartItems
        properties:
          cartItems:
            type: array
            minItems: 1
      properties:
        items:
          type:
          - array
          - 'null'
          items:
            type: object
            required:
            - skuId
            - quantity
            properties:
              skuId:
                type: integer
                format: int64
                minimum: 1
              quantity:
                type: integer
                minimum: 1
        cartItems:
          type:
          - array
          - 'null'
          maxItems: 100
          items:
            $ref: '#/components/schemas/CartSelection'
        buyerName:
          type: string
          minLength: 1
          maxLength: 64
        buyerPhone:
          type: string
          minLength: 1
          maxLength: 32
        expectedAmount:
          type: integer
          format: int64
          minimum: 1
          description: 쿠폰 적용 후 예상 실결제액
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
        couponSkuId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
          description: 쿠폰을 선택한 경우 적용할 주문 항목의 SKU
    CartSelection:
      type: object
      required:
      - itemId
      - version
      properties:
        itemId:
          type: integer
          format: int64
          minimum: 1
        version:
          type: integer
          format: int64
          minimum: 0
    RelayOrderView:
      type: object
      required:
      - order
      - payment
      properties:
        order:
          $ref: '#/components/schemas/RelayOrderSheet'
        payment:
          $ref: '#/components/schemas/RelayPaymentState'
    RelayOrderSheet:
      type: object
      required:
      - orderNumber
      - status
      - failureReason
      - totalAmount
      - discountAmount
      - payableAmount
      - expiresAt
      - processingExpiresAt
      - paymentId
      - couponId
      - couponOrderItemId
      - items
      properties:
        orderNumber:
          type: string
        status:
          type: string
          enum:
          - PENDING
          - PROCESSING
          - PAID
          - FAILED
          - EXPIRED
          - CANCELLED
          - REFUNDED
          - PARTIALLY_REFUNDED
        failureReason:
          type:
          - string
          - 'null'
        totalAmount:
          type: integer
          format: int64
          minimum: 1
        discountAmount:
          type: integer
          format: int64
          minimum: 0
        payableAmount:
          type: integer
          format: int64
          minimum: 1
        expiresAt:
          type: string
          format: date-time
        processingExpiresAt:
          type:
          - string
          - 'null'
          format: date-time
        paymentId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        couponId:
          type:
          - string
          - 'null'
        couponOrderItemId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        items:
          type: array
          minItems: 1
          items:
            type: object
            required:
            - orderItemId
            - skuId
            - productId
            - productName
            - sellerId
            - mainImageUrl
            - selectedOptions
            - quantity
            - unitPrice
            - totalAmount
            - discountAmount
            - payableAmount
            - sourceCartItemId
            - sourceCartVersion
            properties:
              orderItemId:
                type: integer
                format: int64
                minimum: 1
              skuId:
                type: integer
                format: int64
                minimum: 1
              productId:
                type: integer
                format: int64
                minimum: 1
              productName:
                type: string
              sellerId:
                type: string
                format: uuid
              mainImageUrl:
                type:
                - string
                - 'null'
              selectedOptions:
                type:
                - string
                - 'null'
              quantity:
                type: integer
                minimum: 1
              unitPrice:
                type: integer
                format: int64
                minimum: 0
              totalAmount:
                type: integer
                format: int64
                minimum: 0
              discountAmount:
                type: integer
                format: int64
                minimum: 0
              payableAmount:
                type: integer
                format: int64
                minimum: 0
              sourceCartItemId:
                type:
                - integer
                - 'null'
                format: int64
                minimum: 1
              sourceCartVersion:
                type:
                - integer
                - 'null'
                format: int64
                minimum: 0
    RelayPaymentState:
      type: object
      required:
      - orderNumber
      - orderStatus
      - paymentId
      - paymentStatus
      - pgOrderId
      - amount
      - expiresAt
      - processingExpiresAt
      - refundStatus
      properties:
        orderNumber:
          type: string
        orderStatus:
          type: string
          enum:
          - PENDING
          - PROCESSING
          - PAID
          - FAILED
          - EXPIRED
          - CANCELLED
          - REFUNDED
          - PARTIALLY_REFUNDED
        paymentId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        paymentStatus:
          type: string
          description: PREPARING·PREPARATION_FAILED 또는 Payment에서 마지막으로 확인한 CREATED·APPROVING·PENDING·UNKNOWN·APPROVED·DECLINED
            등. 주문 상태와 구분한다.
        pgOrderId:
          type:
          - string
          - 'null'
        amount:
          type: integer
          format: int64
          minimum: 1
        expiresAt:
          type: string
          format: date-time
          description: 변경하지 않는 PENDING 생성 기한
        processingExpiresAt:
          type:
          - string
          - 'null'
          format: date-time
          description: 첫 승인 접수+600초. 준비 단계는 null이며 재요청으로 연장하지 않는다.
        refundStatus:
          type:
          - string
          - 'null'
          enum:
          - null
          - REQUESTED
          - SUCCEEDED
          - FAILED
          description: 늦은 승인 환불의 실제 결과. REQUESTED나 Kafka 발행 성공은 환불 완료가 아니다.
  responses:
    BadRequest:
      description: 요청 값·형식 또는 금액 범위 오류
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            invalidRequest:
              value:
                success: false
                data: null
                error:
                  code: INVALID_REQUEST
                  message: 잘못된 요청입니다.
                  requestId: request-001
    Unauthorized:
      description: 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            unauthorized:
              value:
                success: false
                data: null
                error:
                  code: UNAUTHORIZED
                  message: 인증이 필요합니다.
                  requestId: request-001
    Forbidden:
      description: 검증된 주체의 권한 부족 또는 비활성 개발 API
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            forbidden:
              value:
                success: false
                data: null
                error:
                  code: FORBIDDEN
                  message: 권한이 없습니다.
                  requestId: request-001
    NotFound:
      description: 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            notFound:
              value:
                success: false
                data: null
                error:
                  code: NOT_FOUND
                  message: 대상을 찾을 수 없습니다.
                  requestId: request-001
    Conflict:
      description: 중복·상태·동시 수정·멱등키 입력 충돌
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            conflict:
              value:
                success: false
                data: null
                error:
                  code: CONFLICT
                  message: 요청이 현재 상태와 충돌합니다.
                  requestId: request-001
            insufficientStock:
              value:
                success: false
                data: null
                error:
                  code: CONFLICT
                  message: 재고가 부족합니다.
                  requestId: request-001
            amountChanged:
              value:
                success: false
                data: null
                error:
                  code: CONFLICT
                  message: 주문 금액이 변경되었습니다.
                  requestId: request-001
            duplicateCartItem:
              value:
                success: false
                data: null
                error:
                  code: CONFLICT
                  message: 이미 장바구니에 있는 상품입니다. 수량을 변경해 주세요.
                  requestId: request-001
    ShoppingUnavailable:
      description: Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션
        인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            shoppingUnavailable:
              value:
                success: false
                data: null
                error:
                  code: SHOPPING_UNAVAILABLE
                  message: 상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
                  requestId: request-001
            authUnavailable:
              value:
                success: false
                data: null
                error:
                  code: SERVICE_UNAVAILABLE
                  message: 인증 상태를 확인할 수 없습니다.
                  requestId: request-001
    AuthUnavailable:
      description: JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            authUnavailable:
              value:
                success: false
                data: null
                error:
                  code: SERVICE_UNAVAILABLE
                  message: 인증 상태를 확인할 수 없습니다.
                  requestId: request-001
  parameters:
    OptionalIdempotencyKey:
      name: X-Idempotency-Key
      in: header
      required: false
      schema:
        type: string
        minLength: 1
        maxLength: 64
      description: 회원별 멱등키. 공백만 허용하지 않으며 동일 키의 입력 변경은 409.
    OrderNumber:
      name: orderNumber
      in: path
      required: true
      schema:
        type: string
    PaymentId:
      name: paymentId
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
    GroupNumber:
      name: number
      in: path
      required: true
      schema:
        type: string
      description: 본인 결제 묶음 번호
    RequiredIdempotencyKey:
      name: X-Idempotency-Key
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 64
      description: 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며
        재전송 HTTP 상태는 각 API의 응답 정의를 따른다.
    GroupPaymentId:
      name: id
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
    RequiredRefundIdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 64
      description: 환불 요청 멱등 키. 회원별 같은 입력은 기존 요청을 반환하며 다른 결제 묶음/장바구니 ID 입력은 409.
    RefundId:
      name: id
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
  securitySchemes:
    BearerAuth:
      $ref: ./common.yaml#/components/securitySchemes/BearerAuth
    ServiceToken:
      type: apiKey
      in: header
      name: X-Service-Token
      description: 대상 서비스가 설정한 opaque token으로 호출 서비스 신원을 판정한다. 사용자 JWT와 분리한다.
```

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
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: Member RS256 JWT. sub와 sid는 canonical UUID, iss=shoppinglive-member,
        aud=shoppinglive-api, exp/iat/jti 필수; USER/SELLER. 로컬 서명 검증 후 Member 권위 저장소의
        세션 상태를 무캐시 확인한다. 확인 실패 503, 보안 폐기·탈퇴 401. 쿠키·임의 회원/역할 헤더는 인증 수단이 아니다.
```

입력 SHA-256: `6848661917bc2a2466ef759c83984ec57e78f444b76fac8a96d7ec7578edbd7b`
