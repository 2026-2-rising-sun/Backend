# CommerceCore Service API / sku

<!-- generated: OpenAPI -->

[계약 YAML](../../../../../contracts/api/commercecore-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

sku 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.

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

## GET /v1/sales

서비스용 판매정보 배치 조회

Shopping/Live 전용 봉투 없는 단일 SKU 배열이다. 미등록 제외·순서 미보장·중복 제거 후 최대100개. SKU가 여러 개인 상품은409이며 임의 대표가·합산재고를 만들지 않는다. 누락400/빈 값 빈 배열, 반복 목록 또는 단일 쉼표 목록 지원. 반복·쉼표 혼합은 보장하지 않는다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productIds | query | 필수 | array | 정규 형식 productIds=1&amp;productIds=2. 단일 productIds=1,2도 지원. uniqueItems/maxItems는 중복 제거 전 요청 배열에 적용하지 않는다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |

#### 409 · application/json

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
| &#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.salesId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| &#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;&#93;} |
| &#91;&#93;.available | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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

### 계약 원문

```yaml
tags:
- Internal Sales
summary: 서비스용 판매정보 배치 조회
operationId: getSalesForServices
description: Shopping/Live 전용 봉투 없는 단일 SKU 배열이다. 미등록 제외·순서 미보장·중복 제거 후 최대100개. SKU가
  여러 개인 상품은409이며 임의 대표가·합산재고를 만들지 않는다. 누락400/빈 값 빈 배열, 반복 목록 또는 단일 쉼표 목록 지원. 반복·쉼표
  혼합은 보장하지 않는다.
security:
- ServiceToken: []
parameters:
- name: productIds
  in: query
  required: true
  schema:
    type: array
    items:
      type: integer
      format: int64
  style: form
  explode: true
  description: 정규 형식 productIds=1&productIds=2. 단일 productIds=1,2도 지원. uniqueItems/maxItems는
    중복 제거 전 요청 배열에 적용하지 않는다.
x-allowed-callers:
- shopping
- live
responses:
  '409':
    $ref: '#/components/responses/Conflict'
  '200':
    description: 성공
    content:
      application/json:
        schema:
          type: array
          items:
            $ref: '#/components/schemas/SalesLookup'
        examples:
          normal:
            value:
            - productId: 1
              salesId: 1
              price: 15000
              status: ON_SALE
              available: 10
          empty:
            value: []
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/sales

판매·초기 재고 등록

SELLER 본인의 미삭제 Product를 잠금·검증하고 SKU를 READY/예약0으로 생성한다. 전체 판매 중지 중에도 준비 가능하다. id는 SKU ID이며 신규 Sales 이력 행은 만들지 않는다. 타인403, 없음·삭제404, 이미 SKU가 있으면409.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| initialStock | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: 0, &quot;maximum&quot;: 2147483647} |

```json
{
  "productId": 1,
  "price": 15000,
  "initialStock": 10
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
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;&#93;} |

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
- Seller Sales
summary: 판매·초기 재고 등록
operationId: registerSales
description: SELLER 본인의 미삭제 Product를 잠금·검증하고 SKU를 READY/예약0으로 생성한다. 전체 판매 중지 중에도 준비
  가능하다. id는 SKU ID이며 신규 Sales 이력 행은 만들지 않는다. 타인403, 없음·삭제404, 이미 SKU가 있으면409.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RegisterSalesRequest'
      example:
        productId: 1
        price: 15000
        initialStock: 10
x-required-roles:
- SELLER
responses:
  '201':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Sales'
        examples:
          created:
            value:
              id: 1
              productId: 1
              price: 15000
              status: READY
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
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/sales/{id}/price

판매가 변경

본인·미삭제 검사 뒤 SKU 행 잠금으로 가격 변경을 직렬화한다. 양의 KRW 가격이며 클라이언트 expected version은 없다. 기존 주문 단가 유지. 타인403, 없음·삭제404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

```json
{
  "price": 16000
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
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;&#93;} |

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
- Seller Sales
summary: 판매가 변경
operationId: changeSalesPrice
description: 본인·미삭제 검사 뒤 SKU 행 잠금으로 가격 변경을 직렬화한다. 양의 KRW 가격이며 클라이언트 expected version은
  없다. 기존 주문 단가 유지. 타인403, 없음·삭제404.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ChangePriceRequest'
      example:
        price: 16000
x-required-roles:
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Sales'
        examples:
          updated:
            value:
              id: 1
              productId: 1
              price: 16000
              status: READY
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
  - $ref: '#/components/parameters/SalesId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/sales/{id}/stock

판매 재고 조회

SELLER 본인의 미삭제 상품 SKU 재고를 조회한다. salesInfoId는 SKU ID의 호환 필드명이다. 타인403, 상품 없음·삭제 또는 SKU 없음404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
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
| salesInfoId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| available | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| reserved | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
summary: 판매 재고 조회
operationId: getSalesStock
description: SELLER 본인의 미삭제 상품 SKU 재고를 조회한다. salesInfoId는 SKU ID의 호환 필드명이다. 타인403,
  상품 없음·삭제 또는 SKU 없음404.
x-required-roles:
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SalesStock'
        examples:
          normal:
            value:
              salesInfoId: 1
              available: 10
              reserved: 0
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
  - $ref: '#/components/parameters/SalesId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/sales/{id}/stock

판매 가능 재고 증감

본인·미삭제 검사와 SKU 행 잠금 뒤 available만 증감하고 reserved는 유지한다. 결과 음수·INTEGER 초과409, 타인403, 없음·삭제404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| delta | integer | 필수 |  | {&quot;format&quot;: &quot;int32&quot;, &quot;minimum&quot;: -2147483648, &quot;maximum&quot;: 2147483647} |

```json
{
  "delta": 5
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
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| salesInfoId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| available | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| reserved | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

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
- Seller Sales
summary: 판매 가능 재고 증감
operationId: adjustSalesStock
description: 본인·미삭제 검사와 SKU 행 잠금 뒤 available만 증감하고 reserved는 유지한다. 결과 음수·INTEGER 초과409,
  타인403, 없음·삭제404.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/AdjustStockRequest'
      example:
        delta: 5
x-required-roles:
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SalesStock'
        examples:
          updated:
            value:
              salesInfoId: 1
              available: 15
              reserved: 0
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
  - $ref: '#/components/parameters/SalesId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/sales/{id}/status

판매 상태 변경

본인·미삭제 검사 뒤 ON_SALE/PRIVATE만 수동 지정한다. READY 복귀·SOLD_OUT 직접 요청400. READY의0재고도 판매 시작 가능하며 SOLD_OUT 응답을 파생한다. 같은 의도는 no-op, 타인403, 없음·삭제404.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;ON_SALE&quot;, &quot;PRIVATE&quot;&#93;} |

```json
{
  "status": "ON_SALE"
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
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;&#93;} |

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
- Seller Sales
summary: 판매 상태 변경
operationId: changeSalesStatus
description: 본인·미삭제 검사 뒤 ON_SALE/PRIVATE만 수동 지정한다. READY 복귀·SOLD_OUT 직접 요청400. READY의0재고도
  판매 시작 가능하며 SOLD_OUT 응답을 파생한다. 같은 의도는 no-op, 타인403, 없음·삭제404.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ChangeStatusRequest'
      example:
        status: ON_SALE
x-required-roles:
- SELLER
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/Sales'
        examples:
          updated:
            value:
              id: 1
              productId: 1
              price: 15000
              status: ON_SALE
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
  - $ref: '#/components/parameters/SalesId'
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
  responses:
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
  schemas:
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
    SalesLookup:
      type: object
      required:
      - productId
      - salesId
      - price
      - status
      - available
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
        salesId:
          type: integer
          format: int64
          minimum: 1
        price:
          type: integer
          format: int64
          minimum: 1
        status:
          $ref: '#/components/schemas/SalesStatus'
        available:
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
    RegisterSalesRequest:
      type: object
      required:
      - productId
      - price
      - initialStock
      properties:
        productId:
          type: integer
          format: int64
        price:
          type: integer
          format: int64
          minimum: 1
        initialStock:
          type: integer
          format: int32
          minimum: 0
          maximum: 2147483647
    Sales:
      type: object
      required:
      - id
      - productId
      - price
      - status
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        productId:
          type: integer
          format: int64
          minimum: 1
        price:
          type: integer
          format: int64
          minimum: 1
        status:
          $ref: '#/components/schemas/SalesStatus'
    ChangePriceRequest:
      type: object
      required:
      - price
      properties:
        price:
          type: integer
          format: int64
          minimum: 1
    SalesStock:
      type: object
      required:
      - salesInfoId
      - available
      - reserved
      properties:
        salesInfoId:
          type: integer
          format: int64
          minimum: 1
        available:
          type: integer
          format: int64
          minimum: 0
        reserved:
          type: integer
          format: int64
          minimum: 0
    AdjustStockRequest:
      type: object
      required:
      - delta
      properties:
        delta:
          type: integer
          format: int32
          minimum: -2147483648
          maximum: 2147483647
      description: 양수 추가·음수 감소·0 허용. 결과 available이 음수가 되면 409.
    ChangeStatusRequest:
      type: object
      required:
      - status
      properties:
        status:
          type: string
          enum:
          - ON_SALE
          - PRIVATE
      description: READY/SOLD_OUT은 판매자 입력 불가(400); 재고에 따른 SOLD_OUT은 시스템이 결정한다.
  parameters:
    SalesId:
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

입력 SHA-256: `17d854aa831f40ad71964f68a2e76c70affec04383837853b655a98fe04e8ce5`
