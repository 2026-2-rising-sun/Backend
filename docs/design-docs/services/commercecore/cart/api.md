# CommerceCore Service API / cart

<!-- generated: OpenAPI -->

[계약 YAML](../../../../../contracts/api/commercecore-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

cart 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.

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

## GET /v1/cart/items

본인 장바구니를 상품별 SKU 항목으로 조회

담긴 SKU만 상품별로 묶는다. 가격·재고는 현재 조회값이며 예약하지 않는다. 미매핑·삭제된 항목은 보존하고 purchasable=false로 표시한다. purchasable은 표시용이며 주문서 생성 시 원본을 다시 검증한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 상품별 묶음, 없으면 빈 배열 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| &#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.productName | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| &#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| &#91;&#93;.saleEnabled | boolean | 필수 |  | — |
| &#91;&#93;.items | array | 필수 |  | {&quot;minItems&quot;: 1} |
| &#91;&#93;.items&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.items&#91;&#93;.skuId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.items&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| &#91;&#93;.items&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| &#91;&#93;.items&#91;&#93;.unitPrice | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.items&#91;&#93;.available | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 0} |
| &#91;&#93;.items&#91;&#93;.skuStatus | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;, null&#93;} |
| &#91;&#93;.items&#91;&#93;.purchasable | boolean | 필수 |  | — |

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
- Cart
operationId: listMyCart
summary: 본인 장바구니를 상품별 SKU 항목으로 조회
description: 담긴 SKU만 상품별로 묶는다. 가격·재고는 현재 조회값이며 예약하지 않는다. 미매핑·삭제된 항목은 보존하고 purchasable=false로
  표시한다. purchasable은 표시용이며 주문서 생성 시 원본을 다시 검증한다.
x-required-roles:
- USER
- SELLER
responses:
  '200':
    description: 상품별 묶음, 없으면 빈 배열
    content:
      application/json:
        schema:
          type: array
          items:
            $ref: '#/components/schemas/CartProductGroup'
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

## POST /v1/cart/items

본인 장바구니에 SKU 추가

skuId 또는 기존 productId 중 하나만 지정한다. productId는 SKU가 정확히 하나일 때만 호환하고 0개/여러 개이면 409다. 같은 회원의 같은 SKU 중복도 409이며 수량을 누적하지 않는다. 판매 준비·중지·품절 여부로 담기 자체를 예약으로 바꾸지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| skuId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| &#91;oneOf 1&#93;.skuId | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| &#91;oneOf 1&#93;.productId | null | 선택 |  | — |
| &#91;oneOf 2&#93;.productId | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| &#91;oneOf 2&#93;.skuId | null | 선택 |  | — |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | SKU 항목 생성 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| skuId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
- Cart
operationId: addMyCartItem
summary: 본인 장바구니에 SKU 추가
description: skuId 또는 기존 productId 중 하나만 지정한다. productId는 SKU가 정확히 하나일 때만 호환하고 0개/여러
  개이면 409다. 같은 회원의 같은 SKU 중복도 409이며 수량을 누적하지 않는다. 판매 준비·중지·품절 여부로 담기 자체를 예약으로 바꾸지
  않는다.
x-required-roles:
- USER
- SELLER
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/AddCartItemRequest'
responses:
  '201':
    description: SKU 항목 생성
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CartItem'
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

## PATCH /v1/cart/items/{itemId}

본인 SKU 항목의 절대 수량 변경



**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| itemId | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 변경한 항목 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| skuId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
- Cart
operationId: updateMyCartQuantity
summary: 본인 SKU 항목의 절대 수량 변경
x-required-roles:
- USER
- SELLER
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/QuantityRequest'
responses:
  '200':
    description: 변경한 항목
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CartItem'
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
  - $ref: '#/components/parameters/ItemId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## DELETE /v1/cart/items/{itemId}

본인 SKU 항목 삭제



**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| itemId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 삭제, 응답 본문 없음 |
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
- Cart
operationId: deleteMyCartItem
summary: 본인 SKU 항목 삭제
x-required-roles:
- USER
- SELLER
responses:
  '204':
    description: 삭제, 응답 본문 없음
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
  - $ref: '#/components/parameters/ItemId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/cart/items/{itemId}/orders

장바구니 한 항목 주문

기존 한 항목 주문 경로를 유지하며 주문 하나를 가진 결제 묶음으로 생성한다. 회원당 활성 묶음 하나 제한은 단건에도 적용한다. 선택 항목의 상품·수량 변경과 현재 가격·판매·재고를 재검증하며 주문 저장·예약은 원자적이다. 생성 시 카트를 유지하고 최초 결제 성공 때 본인 장바구니의 구매 SKU 행 전체를 삭제한다. 수량 변경·삭제 후 재추가도 같은 SKU이면 삭제하며 다른 SKU·회원은 보존한다. 중복 성공 이후 재담기는 보존한다. 대기 중 수량 변경·삭제는 허용하며 주문 스냅샷은 바뀌지 않는다. 동일 키·입력 재전송은 카트 삭제 뒤에도 원래 주문을 반환한다. 여러 항목은 POST /v1/cart/orders를 사용한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| itemId | path | 필수 | integer |  |
| X-Idempotency-Key | header | 필수 | string | 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며 재전송 HTTP 상태는 각 API의 응답 정의를 따른다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| buyerName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| buyerPhone | string | 필수 |  | {&quot;pattern&quot;: &quot;^&#91;0-9-&#93;{9,32}$&quot;, &quot;writeOnly&quot;: true} |
| expectedTotalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

```json
{
  "buyerName": "구매자",
  "buyerPhone": "010-1234-5678",
  "expectedTotalAmount": 30000
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
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
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
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
| buyerName | string | 필수 |  | — |
| createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| expiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| cancelledAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| salesInfoId | schema | 선택 | 기존 Sales 식별자 보존. 별도 Sales가 없는 신규 SKU 항목은 null이며 내부 주문서 도메인의 skuId를 사용한다. | — |

### 계약 원문

```yaml
tags:
- Cart
summary: 장바구니 한 항목 주문
operationId: orderMyCartItem
description: 기존 한 항목 주문 경로를 유지하며 주문 하나를 가진 결제 묶음으로 생성한다. 회원당 활성 묶음 하나 제한은 단건에도 적용한다.
  선택 항목의 상품·수량 변경과 현재 가격·판매·재고를 재검증하며 주문 저장·예약은 원자적이다. 생성 시 카트를 유지하고 최초 결제 성공 때 본인
  장바구니의 구매 SKU 행 전체를 삭제한다. 수량 변경·삭제 후 재추가도 같은 SKU이면 삭제하며 다른 SKU·회원은 보존한다. 중복 성공 이후
  재담기는 보존한다. 대기 중 수량 변경·삭제는 허용하며 주문 스냅샷은 바뀌지 않는다. 동일 키·입력 재전송은 카트 삭제 뒤에도 원래 주문을 반환한다.
  여러 항목은 POST /v1/cart/orders를 사용한다.
parameters:
- $ref: '#/components/parameters/RequiredIdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/CartOrderRequest'
      example:
        buyerName: 구매자
        buyerPhone: 010-1234-5678
        expectedTotalAmount: 30000
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
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ItemId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/cart/checkout

선택 상품 통합 주문서 확인

1~100개 항목의 소유권·버전·판매 상태·재고와 최신 가격을 확인한다. couponId가 있으면 회원 보유·사용 가능 기간과 대상 상품을 서버에서 확인하고 대상 주문에만 정액 할인을 비례 배분한다. 중복 ID는 400, 변경된 버전·판매 불가·재고 부족·사용할 수 없는 쿠폰은 409. 조회 단계는 쿠폰·재고를 예약하거나 주문을 만들지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| items | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| items&#91;&#93;.itemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |

```json
{
  "items": [
    {
      "itemId": 1,
      "version": 0
    },
    {
      "itemId": 2,
      "version": 0
    }
  ],
  "couponId": "33333333-3333-4333-8333-333333333334"
}
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
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| items | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| items&#91;&#93;.itemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.productName | string | 필수 |  | — |
| items&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| items&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| items&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

```json
{
  "items": [
    {
      "itemId": 1,
      "version": 0,
      "productId": 1,
      "productName": "상품 1",
      "quantity": 2,
      "unitPrice": 10000,
      "totalAmount": 20000,
      "discountAmount": 2000,
      "payableAmount": 18000
    },
    {
      "itemId": 2,
      "version": 0,
      "productId": 2,
      "productName": "상품 2",
      "quantity": 1,
      "unitPrice": 5000,
      "totalAmount": 5000,
      "discountAmount": 0,
      "payableAmount": 5000
    }
  ],
  "totalAmount": 25000,
  "couponId": "33333333-3333-4333-8333-333333333334",
  "discountAmount": 2000,
  "payableAmount": 23000
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
- Cart
summary: 선택 상품 통합 주문서 확인
operationId: previewMyCartGroup
description: 1~100개 항목의 소유권·버전·판매 상태·재고와 최신 가격을 확인한다. couponId가 있으면 회원 보유·사용 가능 기간과
  대상 상품을 서버에서 확인하고 대상 주문에만 정액 할인을 비례 배분한다. 중복 ID는 400, 변경된 버전·판매 불가·재고 부족·사용할 수 없는
  쿠폰은 409. 조회 단계는 쿠폰·재고를 예약하거나 주문을 만들지 않는다.
x-required-roles:
- USER
- SELLER
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/CartCheckoutRequest'
      example:
        items:
        - itemId: 1
          version: 0
        - itemId: 2
          version: 0
        couponId: 33333333-3333-4333-8333-333333333334
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CartCheckout'
        example:
          items:
          - itemId: 1
            version: 0
            productId: 1
            productName: 상품 1
            quantity: 2
            unitPrice: 10000
            totalAmount: 20000
            discountAmount: 2000
            payableAmount: 18000
          - itemId: 2
            version: 0
            productId: 2
            productName: 상품 2
            quantity: 1
            unitPrice: 5000
            totalAmount: 5000
            discountAmount: 0
            payableAmount: 5000
          totalAmount: 25000
          couponId: 33333333-3333-4333-8333-333333333334
          discountAmount: 2000
          payableAmount: 23000
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

## POST /v1/cart/orders

상품별 주문과 결제 묶음 생성

선택 상품별 주문을 생성하고 하나의 결제 묶음에 연결한다. couponId는 선택이며 지정 시 회원 쿠폰을 주문 생성 트랜잭션에서 예약하고, 할인액·대상 주문별 배분·결제 실부담액을 스냅샷으로 저장한다. expectedTotalAmount는 할인 전 총액이다. 회원당 활성 묶음은 단건 구매를 포함해 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 새로운 구매는 409. 회원별 동일 키·정렬된 항목 ID/버전·구매자·기대 총액·쿠폰 ID가 같은 요청은 기존 묶음을 200으로 반환하고 다른 입력은 409. 카트 잠금·버전/가격/상태 재검증·쿠폰 예약·전체 주문 저장·재고 예약은 한 트랜잭션이며 하나라도 실패하면 전부 롤백한다. 카트는 생성 시 삭제하지 않으며 편집할 수 있다. 최초 성공 때 본인 장바구니의 구매 SKU 행 전체를 삭제하며 결제 중 수량 변경·삭제 후 재등록도 정리한다. 다른 SKU·회원과 성공 이후 재담기는 보존한다. 새 주문 생성 기한은 기본600초다. 이 legacy 경로의 주문은 #390 뒤 Payment 호출이 없어 결제되지 않고 기한에 만료된다(Frontend #89 전환 전까지 유지). 취소/만료 뒤에는 새 묶음을 만들어야 한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Cart

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| X-Idempotency-Key | header | 필수 | string | 회원별 요청 식별자. 동일 키·입력 재전송은 카트 변경·삭제 뒤에도 기존 결과로 연결한다. 다른 입력은 409이며 재전송 HTTP 상태는 각 API의 응답 정의를 따른다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| items | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| items&#91;&#93;.itemId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| items&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| buyerName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 64} |
| buyerPhone | string | 필수 |  | {&quot;pattern&quot;: &quot;^&#91;0-9-&#93;{9,32}$&quot;, &quot;writeOnly&quot;: true} |
| expectedTotalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;maxLength&quot;: 64} |

```json
{
  "items": [
    {
      "itemId": 1,
      "version": 0
    },
    {
      "itemId": 2,
      "version": 0
    }
  ],
  "buyerName": "구매자",
  "buyerPhone": "01012345678",
  "expectedTotalAmount": 25000
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 동일 입력 재전송, 현재 묶음 결과 |
| 201 | 새 묶음과 상품별 주문 생성 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| groupNumber | string | 필수 |  | — |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;maxLength&quot;: 64} |
| expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 승인 시도 접수+600초. 같은 시도의 재전송으로 연장하지 않는다. 준비 단계·거절로 PENDING에 돌아온 동안·V105 이전 확인중 주문은 null. | {&quot;format&quot;: &quot;date-time&quot;} |
| orders | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| orders&#91;&#93;.orderNumber | string | 필수 |  | — |
| orders&#91;&#93;.groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| orders&#91;&#93;.productName | string | 필수 |  | — |
| orders&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| orders&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
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

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| groupNumber | string | 필수 |  | — |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
| totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| couponId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;maxLength&quot;: 64} |
| expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| processingExpiresAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 승인 시도 접수+600초. 같은 시도의 재전송으로 연장하지 않는다. 준비 단계·거절로 PENDING에 돌아온 동안·V105 이전 확인중 주문은 null. | {&quot;format&quot;: &quot;date-time&quot;} |
| orders | array | 필수 |  | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| orders&#91;&#93;.orderNumber | string | 필수 |  | — |
| orders&#91;&#93;.groupNumber | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 연결된 결제 묶음 번호. 기존 그룹 없는 주문은 null. | — |
| orders&#91;&#93;.productName | string | 필수 |  | — |
| orders&#91;&#93;.quantity | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 2147483647} |
| orders&#91;&#93;.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.totalAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| orders&#91;&#93;.discountAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.payableAmount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| orders&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PENDING_PAYMENT&quot;, &quot;PAYMENT_CONFIRMING&quot;, &quot;PAID&quot;, &quot;FAILED&quot;, &quot;CANCELLED&quot;, &quot;EXPIRED&quot;, &quot;REFUNDED&quot;, &quot;PARTIALLY_REFUNDED&quot;, &quot;CANCEL_PENDING&quot;, &quot;COMPENSATION_PENDING&quot;&#93;} |
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
- Cart
summary: 상품별 주문과 결제 묶음 생성
operationId: createMyCartGroup
description: '선택 상품별 주문을 생성하고 하나의 결제 묶음에 연결한다. couponId는 선택이며 지정 시 회원 쿠폰을 주문 생성 트랜잭션에서
  예약하고, 할인액·대상 주문별 배분·결제 실부담액을 스냅샷으로 저장한다. expectedTotalAmount는 할인 전 총액이다. 회원당 활성
  묶음은 단건 구매를 포함해 하나이며 PENDING_PAYMENT/PAYMENT_CONFIRMING 중 새로운 구매는 409. 회원별 동일 키·정렬된
  항목 ID/버전·구매자·기대 총액·쿠폰 ID가 같은 요청은 기존 묶음을 200으로 반환하고 다른 입력은 409. 카트 잠금·버전/가격/상태 재검증·쿠폰
  예약·전체 주문 저장·재고 예약은 한 트랜잭션이며 하나라도 실패하면 전부 롤백한다. 카트는 생성 시 삭제하지 않으며 편집할 수 있다. 최초 성공
  때 본인 장바구니의 구매 SKU 행 전체를 삭제하며 결제 중 수량 변경·삭제 후 재등록도 정리한다. 다른 SKU·회원과 성공 이후 재담기는 보존한다.
  새 주문 생성 기한은 기본600초다. 이 legacy 경로의 주문은 #390 뒤 Payment 호출이 없어 결제되지 않고 기한에 만료된다(Frontend
  #89 전환 전까지 유지). 취소/만료 뒤에는 새 묶음을 만들어야 한다.'
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
        $ref: '#/components/schemas/CreateCartGroupRequest'
      example:
        items:
        - itemId: 1
          version: 0
        - itemId: 2
          version: 0
        buyerName: 구매자
        buyerPhone: 01012345678
        expectedTotalAmount: 25000
responses:
  '200':
    description: 동일 입력 재전송, 현재 묶음 결과
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
  '201':
    description: 새 묶음과 상품별 주문 생성
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

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### commercecore-service.yaml

```yaml
components:
  schemas:
    CartProductGroup:
      type: object
      required:
      - productId
      - productName
      - mainImageUrl
      - saleEnabled
      - items
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
        productName:
          type:
          - string
          - 'null'
        mainImageUrl:
          type:
          - string
          - 'null'
        saleEnabled:
          type: boolean
        items:
          type: array
          minItems: 1
          items:
            $ref: '#/components/schemas/CartSkuView'
    CartSkuView:
      type: object
      required:
      - id
      - skuId
      - quantity
      - version
      - unitPrice
      - available
      - skuStatus
      - purchasable
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        skuId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
        version:
          type: integer
          format: int64
          minimum: 0
        unitPrice:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        available:
          type:
          - integer
          - 'null'
          format: int32
          minimum: 0
        skuStatus:
          type:
          - string
          - 'null'
          enum:
          - READY
          - ON_SALE
          - SOLD_OUT
          - PRIVATE
          - null
        purchasable:
          type: boolean
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
    AddCartItemRequest:
      type: object
      required:
      - quantity
      properties:
        skuId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        productId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
          deprecated: true
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
      oneOf:
      - required:
        - skuId
        properties:
          skuId:
            type: integer
            minimum: 1
          productId:
            type: 'null'
      - required:
        - productId
        properties:
          productId:
            type: integer
            minimum: 1
          skuId:
            type: 'null'
    CartItem:
      type: object
      required:
      - id
      - productId
      - skuId
      - quantity
      - version
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        productId:
          type: integer
          format: int64
          minimum: 1
        skuId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
        version:
          type: integer
          format: int64
          minimum: 0
    QuantityRequest:
      type: object
      required:
      - quantity
      properties:
        quantity:
          type: integer
          format: int32
          minimum: 1
          maximum: 2147483647
      description: 증감량이 아닌 최종 절대 수량.
    CartOrderRequest:
      type: object
      required:
      - buyerName
      - buyerPhone
      - expectedTotalAmount
      properties:
        buyerName:
          type: string
          minLength: 1
          maxLength: 64
        buyerPhone:
          type: string
          pattern: ^[0-9-]{9,32}$
          writeOnly: true
        expectedTotalAmount:
          type: integer
          format: int64
          minimum: 1
      description: 선택 항목 하나를 단건 주문으로 전환한다. expectedTotalAmount 필수.
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
          - CANCEL_PENDING
          - COMPENSATION_PENDING
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
    CartCheckoutRequest:
      type: object
      required:
      - items
      properties:
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/CartSelection'
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
      description: 항목별 현재 버전이 필수이며 항목 ID 중복은 허용하지 않는다. 배열 순서는 멱등성 비교에 영향을 주지 않는다.
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
    CartCheckout:
      type: object
      required:
      - items
      - totalAmount
      - discountAmount
      - payableAmount
      properties:
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/CartCheckoutItem'
        totalAmount:
          type: integer
          format: int64
          minimum: 1
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
    CartCheckoutItem:
      type: object
      required:
      - itemId
      - version
      - productId
      - productName
      - quantity
      - unitPrice
      - totalAmount
      - discountAmount
      - payableAmount
      properties:
        itemId:
          type: integer
          format: int64
          minimum: 1
        version:
          type: integer
          format: int64
          minimum: 0
        productId:
          type: integer
          format: int64
          minimum: 1
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
    CreateCartGroupRequest:
      type: object
      required:
      - items
      - buyerName
      - buyerPhone
      - expectedTotalAmount
      properties:
        items:
          type: array
          minItems: 1
          maxItems: 100
          items:
            $ref: '#/components/schemas/CartSelection'
        buyerName:
          type: string
          minLength: 1
          maxLength: 64
        buyerPhone:
          type: string
          pattern: ^[0-9-]{9,32}$
          writeOnly: true
        expectedTotalAmount:
          type: integer
          format: int64
          minimum: 1
        couponId:
          type:
          - string
          - 'null'
          maxLength: 64
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
          - CANCEL_PENDING
          - COMPENSATION_PENDING
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
          description: 승인 시도 접수+600초. 같은 시도의 재전송으로 연장하지 않는다. 준비 단계·거절로 PENDING에 돌아온
            동안·V105 이전 확인중 주문은 null.
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
        할인 합계, payableAmount는 결제 실부담액이다. 결제 시작 전 paymentId는 null. 0원 묶음의 결제 시작과 policy0
        환불 경로는 삭제됐다(#390. 새 주문서는 실결제액이 양수여야 하고, policy0 환불은 409). legacy 실행은 processingExpiresAt
        기한에 만료하고 선점을 해제한다(새 주문서는 처리 기한에 COMPENSATION_PENDING으로 바꾼다). V105 이전 확인중 주문은
        기한을 임의 역산하지 않아 processingExpiresAt이 null이다.
  responses:
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
  parameters:
    ItemId:
      name: itemId
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
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

입력 SHA-256: `db9ce1f2fccdf47d91cbe7c56fcf7e10a2917effa3475c7be1843465900e9874`
