# CommerceCore Service API / product

<!-- generated: OpenAPI -->

[계약 YAML](../../../../../contracts/api/commercecore-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

product 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.

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

## POST /v1/seller/products

상품 기본정보 등록



**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| X-Idempotency-Key | header | 필수 | string |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| name | string | 필수 | 앞뒤 공백 제거 후 1~100자 | {&quot;minLength&quot;: 1} |
| description | string | 필수 | 앞뒤 공백 제거 후 1~2000자 | {&quot;minLength&quot;: 1} |
| mainImageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |

```json
{
  "name": "가을 니트",
  "description": "울 혼방 니트",
  "mainImageId": 10,
  "category": "FASHION"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 201 | 등록됨. 인증 주체가 등록 판매자로 저장된다. 같은 판매자의 멱등 키 재전송은 최초 상품을 반환하고 다른 판매자가 같은 키를 사용하면 409이다. |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 409 | 요청 version 불일치 또는 저장 시점의 낙관적 락 충돌 |
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

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.name | string | 필수 |  | — |
| data.description | string | 필수 |  | — |
| data.mainImageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.sellerId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 등록 판매자 회원 ID. 기존 소유자 미지정 상품은 null. | {&quot;maxLength&quot;: 36} |
| data.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| data.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
| data.deletedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "productId": 1,
    "name": "가을 니트",
    "description": "울 혼방 니트",
    "mainImageId": 10,
    "mainImageUrl": "/api/commercecore/v1/product-images/10",
    "version": 0,
    "createdAt": "2026-09-30T03:00:00Z",
    "sellerId": "00000000-0000-0000-0000-000000000003",
    "category": "FASHION",
    "saleEnabled": false,
    "deletedAt": null
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
x-required-roles:
- SELLER
tags:
- Seller Product
summary: 상품 기본정보 등록
operationId: registerProduct
parameters:
- $ref: '#/components/parameters/IdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RegisterProductRequest'
      example:
        name: 가을 니트
        description: 울 혼방 니트
        mainImageId: 10
        category: FASHION
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '201':
    description: 등록됨. 인증 주체가 등록 판매자로 저장된다. 같은 판매자의 멱등 키 재전송은 최초 상품을 반환하고 다른 판매자가 같은
      키를 사용하면 409이다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductEnvelope'
        example:
          success: true
          data:
            productId: 1
            name: 가을 니트
            description: 울 혼방 니트
            mainImageId: 10
            mainImageUrl: /api/commercecore/v1/product-images/10
            version: 0
            createdAt: '2026-09-30T03:00:00Z'
            sellerId: 00000000-0000-0000-0000-000000000003
            category: FASHION
            saleEnabled: false
            deletedAt: null
          error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '409':
    $ref: '#/components/responses/ProductConflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/seller/products

판매자 상품 목록

Product와 SKU 원본을 같은 Core DB에서 조회한다. SKU 존재·파생 상태 필터는 페이지 계산 전에 적용하고 total/offset은 Product 단위다. SKU가 여러 개면 기존 단일 판매 필드로 축약하지 않고409. 본인 미삭제 상품만 조회하며 Catalog/HTTP fallback은 없다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| status | query | 선택 | string | 생략하면 전체. NOT_REGISTERED는 원본 판매정보가 없는 상품이다. UNKNOWN은 조회 조건으로 받지 않는다. |
| page | query | 선택 | integer |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 200 | 원본 조회 결과 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 409 · application/json

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

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.items&#91;&#93;.name | string | 필수 |  | — |
| data.items&#91;&#93;.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.items&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;.salesStatus | string | 필수 | NOT_REGISTERED는 판매정보 미등록, UNKNOWN은 조회 실패다. 둘 다 price와 available이 null이다. | {&quot;enum&quot;: &#91;&quot;NOT_REGISTERED&quot;, &quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;, &quot;UNKNOWN&quot;&#93;} |
| data.items&#91;&#93;.price | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.items&#91;&#93;.available | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;minimum&quot;: 0} |
| data.items&#91;&#93;.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
| data.page | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data.size | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 100} |
| data.totalElements | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.totalPages | integer | 필수 |  | {&quot;minimum&quot;: 0} |
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
x-required-roles:
- SELLER
tags:
- Seller Product
summary: 판매자 상품 목록
operationId: listSellerProducts
description: Product와 SKU 원본을 같은 Core DB에서 조회한다. SKU 존재·파생 상태 필터는 페이지 계산 전에 적용하고 total/offset은
  Product 단위다. SKU가 여러 개면 기존 단일 판매 필드로 축약하지 않고409. 본인 미삭제 상품만 조회하며 Catalog/HTTP fallback은
  없다.
parameters:
- name: status
  in: query
  description: 생략하면 전체. NOT_REGISTERED는 원본 판매정보가 없는 상품이다. UNKNOWN은 조회 조건으로 받지 않는다.
  schema:
    type: string
    enum:
    - NOT_REGISTERED
    - READY
    - ON_SALE
    - SOLD_OUT
    - PRIVATE
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
  '409':
    $ref: '#/components/responses/Conflict'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 원본 조회 결과
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SellerProductPageEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                items:
                - productId: 1
                  name: 가을 니트
                  mainImageUrl: /api/commercecore/v1/product-images/10
                  createdAt: '2026-09-30T03:00:00Z'
                  salesStatus: ON_SALE
                  price: 39000
                  available: 5
                  saleEnabled: true
                page: 0
                size: 20
                totalElements: 1
                totalPages: 1
              error: null
          empty:
            value:
              success: true
              data:
                items: []
                page: 0
                size: 20
                totalElements: 0
                totalPages: 0
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/seller/products/{productId}

판매자 상품 상세

본인 미삭제 Product와 SKU 원본을 조회한다. 다른 판매자 또는 소유자 없는 상품404. 여러 SKU는 임의 대표값 없이409.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 200 | 원본 조회 결과 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 404 | 상품이 없거나 본인 상품이 아니다. 다른 판매자 상품의 존재 여부를 드러내지 않는다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 409 · application/json

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

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.name | string | 필수 |  | — |
| data.description | string | 필수 |  | — |
| data.mainImageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.salesStatus | string | 필수 | NOT_REGISTERED는 판매정보 미등록, UNKNOWN은 조회 실패다. 둘 다 price와 available이 null이다. | {&quot;enum&quot;: &#91;&quot;NOT_REGISTERED&quot;, &quot;READY&quot;, &quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;PRIVATE&quot;, &quot;UNKNOWN&quot;&#93;} |
| data.price | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.available | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;minimum&quot;: 0} |
| data.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| data.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
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
x-required-roles:
- SELLER
tags:
- Seller Product
summary: 판매자 상품 상세
operationId: getSellerProduct
responses:
  '409':
    $ref: '#/components/responses/Conflict'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 원본 조회 결과
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SellerProductDetailEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                productId: 1
                name: 가을 니트
                description: 울 혼방 니트
                mainImageId: 10
                mainImageUrl: /api/commercecore/v1/product-images/10
                version: 0
                createdAt: '2026-09-30T03:00:00Z'
                updatedAt: '2026-09-30T03:00:00Z'
                salesStatus: ON_SALE
                price: 39000
                available: 5
                category: FASHION
                saleEnabled: true
              error: null
          notRegistered:
            value:
              success: true
              data:
                productId: 1
                name: 가을 니트
                description: 울 혼방 니트
                mainImageId: 10
                mainImageUrl: /api/commercecore/v1/product-images/10
                version: 0
                createdAt: '2026-09-30T03:00:00Z'
                updatedAt: '2026-09-30T03:00:00Z'
                salesStatus: NOT_REGISTERED
                price: null
                available: null
                category: null
                saleEnabled: false
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    description: 상품이 없거나 본인 상품이 아니다. 다른 판매자 상품의 존재 여부를 드러내지 않는다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
description: 본인 미삭제 Product와 SKU 원본을 조회한다. 다른 판매자 또는 소유자 없는 상품404. 여러 SKU는 임의 대표값
  없이409.
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/seller/products/{productId}

상품 기본정보 부분 수정



**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| name | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| description | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| mainImageId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

```json
{
  "name": "가을 니트 수정",
  "version": 0
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 200 | 수정됨 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 요청 version 불일치 또는 저장 시점의 낙관적 락 충돌 |
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

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.name | string | 필수 |  | — |
| data.description | string | 필수 |  | — |
| data.mainImageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.sellerId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 등록 판매자 회원 ID. 기존 소유자 미지정 상품은 null. | {&quot;maxLength&quot;: 36} |
| data.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| data.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
| data.deletedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "productId": 1,
    "name": "가을 니트 수정",
    "description": "울 혼방 니트",
    "mainImageId": 10,
    "mainImageUrl": "/api/commercecore/v1/product-images/10",
    "version": 1,
    "createdAt": "2026-09-30T03:00:00Z",
    "sellerId": "00000000-0000-0000-0000-000000000003",
    "category": "FASHION",
    "saleEnabled": false,
    "deletedAt": null
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
x-required-roles:
- SELLER
tags:
- Seller Product
summary: 상품 기본정보 부분 수정
operationId: updateProduct
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/UpdateProductRequest'
      example:
        name: 가을 니트 수정
        version: 0
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 수정됨
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductEnvelope'
        example:
          success: true
          data:
            productId: 1
            name: 가을 니트 수정
            description: 울 혼방 니트
            mainImageId: 10
            mainImageUrl: /api/commercecore/v1/product-images/10
            version: 1
            createdAt: '2026-09-30T03:00:00Z'
            sellerId: 00000000-0000-0000-0000-000000000003
            category: FASHION
            saleEnabled: false
            deletedAt: null
          error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/ProductConflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## DELETE /v1/seller/products/{productId}

상품 소프트 삭제

version은 필수 쿼리이며 첫 삭제에서 낙관적 버전을 검사한다. 본인 상품의 완료된 삭제 재전송은 원래 version으로도 204이며 추가 변경·이벤트가 없다. 상품/SKU/이미지 행과 기존 주문 snapshot을 물리 삭제하지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |
| version | query | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 삭제 완료 또는 본인의 완료된 삭제 재요청 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 요청 version 불일치 또는 저장 시점의 낙관적 락 충돌 |
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

#### 400 · application/json

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
summary: 상품 소프트 삭제
description: version은 필수 쿼리이며 첫 삭제에서 낙관적 버전을 검사한다. 본인 상품의 완료된 삭제 재전송은 원래 version으로도
  204이며 추가 변경·이벤트가 없다. 상품/SKU/이미지 행과 기존 주문 snapshot을 물리 삭제하지 않는다.
operationId: deleteProduct
parameters:
- name: version
  in: query
  required: true
  schema:
    type: integer
    format: int64
    minimum: 0
responses:
  '204':
    description: 삭제 완료 또는 본인의 완료된 삭제 재요청
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/ProductConflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
x-required-roles:
- SELLER
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/seller/product-images

상품 이미지 업로드

JPEG 또는 PNG 파일을 받고 최대 5MB, 가로와 세로는 각각 최대 8000px이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Product Image

### 요청 본문

본문: 필수

**multipart/form-data**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| file | string | 필수 |  | {&quot;format&quot;: &quot;binary&quot;} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 201 | 업로드됨 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 500 | 서버 내부 오류 |
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

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.imageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.url | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.contentType | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;image/jpeg&quot;, &quot;image/png&quot;&#93;} |
| data.sizeBytes | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.width | integer | 필수 |  | — |
| data.height | integer | 필수 |  | — |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "imageId": 10,
    "url": "/api/commercecore/v1/product-images/10",
    "contentType": "image/png",
    "sizeBytes": 12345,
    "width": 800,
    "height": 600
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

#### 500 · application/json

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
x-required-roles:
- SELLER
tags:
- Product Image
summary: 상품 이미지 업로드
operationId: uploadProductImage
description: JPEG 또는 PNG 파일을 받고 최대 5MB, 가로와 세로는 각각 최대 8000px이다.
requestBody:
  required: true
  content:
    multipart/form-data:
      schema:
        type: object
        required:
        - file
        properties:
          file:
            type: string
            format: binary
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '201':
    description: 업로드됨
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ImageUploadEnvelope'
        example:
          success: true
          data:
            imageId: 10
            url: /api/commercecore/v1/product-images/10
            contentType: image/png
            sizeBytes: 12345
            width: 800
            height: 600
          error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/product-images/{imageId}

공개 이미지 파일 조회



**인증:** `[]`
**태그:** Product Image

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| imageId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 200 | 저장된 원본 파일. 1년 immutable cache와 nosniff 헤더를 포함한다. |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
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

#### 200 · image/png

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

#### 200 · image/jpeg

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

#### 400 · application/json

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
security: []
tags:
- Product Image
summary: 공개 이미지 파일 조회
operationId: getProductImage
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 저장된 원본 파일. 1년 immutable cache와 nosniff 헤더를 포함한다.
    content:
      image/png:
        schema:
          type: string
          format: binary
      image/jpeg:
        schema:
          type: string
          format: binary
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - name: imageId
    in: path
    required: true
    schema:
      type: integer
      format: int64
      minimum: 1
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/internal/products

내부 상품 스냅샷 벌크 조회

Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| ids | query | 필수 | array | 표준 전송은 ids=1&amp;ids=2 (form, explode=true)다. 기존 ids=1,2도 호환 수용한다.<br>중복 제거 후 1~100개여야 하며 없는 상품은 결과에서 제외한다. #104 통합 기준 계약이다.<br> |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 200 | 판매 상태와 무관한 기본정보 스냅샷 목록 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |

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

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.name | string | 필수 |  | — |
| data&#91;&#93;.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data&#91;&#93;.sellerId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 등록 판매자 회원 ID. 기존 상품은 null이며 이전 소비자 계약과 호환되는 추가 필드. | {&quot;maxLength&quot;: 36} |
| data&#91;&#93;.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
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

### 계약 원문

```yaml
security:
- ServiceToken: []
x-allowed-callers:
- live
tags:
- Internal Product
summary: 내부 상품 스냅샷 벌크 조회
operationId: getInternalProducts
parameters:
- name: ids
  in: query
  required: true
  description: '표준 전송은 ids=1&ids=2 (form, explode=true)다. 기존 ids=1,2도 호환 수용한다.

    중복 제거 후 1~100개여야 하며 없는 상품은 결과에서 제외한다. #104 통합 기준 계약이다.

    '
  schema:
    type: array
    minItems: 1
    items:
      type: integer
      format: int64
  style: form
  explode: true
  example:
  - 1
  - 2
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 판매 상태와 무관한 기본정보 스냅샷 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductSnapshotListEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
              - id: 1
                name: 가을 니트
                mainImageUrl: /api/commercecore/v1/product-images/10
                saleEnabled: true
              error: null
          missing:
            value:
              success: true
              data: []
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
description: Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/internal/products/{productId}

내부 상품 스냅샷 단건 조회

Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 200 | 판매 상태와 무관한 기본정보 스냅샷 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |

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

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.name | string | 필수 |  | — |
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.sellerId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 등록 판매자 회원 ID. 기존 상품은 null이며 이전 소비자 계약과 호환되는 추가 필드. | {&quot;maxLength&quot;: 36} |
| data.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "가을 니트",
    "mainImageUrl": "/api/commercecore/v1/product-images/10",
    "saleEnabled": true
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
security:
- ServiceToken: []
x-allowed-callers:
- live
tags:
- Internal Product
summary: 내부 상품 스냅샷 단건 조회
operationId: getInternalProduct
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 판매 상태와 무관한 기본정보 스냅샷
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductSnapshotEnvelope'
        example:
          success: true
          data:
            id: 1
            name: 가을 니트
            mainImageUrl: /api/commercecore/v1/product-images/10
            saleEnabled: true
          error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
description: Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## DELETE /v1/dev/product-images

미참조 상품 이미지 정리 (로컬 테스트 전용)

local 또는 test 프로필 AND dev/prod 비활성 AND commercecore.dev-api.enabled=true에서만 제공. 외부 dev/prod는 금지한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Product Image

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 삭제 수. 참조 중인 이미지는 유지한다. |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.deletedCount | integer | 필수 |  | {&quot;minimum&quot;: 0} |
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
- Product Image
summary: 미참조 상품 이미지 정리 (로컬 테스트 전용)
operationId: deleteLocalUnreferencedImages
description: local 또는 test 프로필 AND dev/prod 비활성 AND commercecore.dev-api.enabled=true에서만
  제공. 외부 dev/prod는 금지한다.
x-required-roles:
- SELLER
x-availability: opt-in local/test only
responses:
  '200':
    description: 삭제 수. 참조 중인 이미지는 유지한다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/DevImageResetEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                deletedCount: 2
              error: null
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

## GET /v1/seller/products/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
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
operationId: missingSellerProductIdOnGet
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Seller Product
x-required-roles:
- SELLER
responses:
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

## PATCH /v1/seller/products/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
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
operationId: missingSellerProductIdOnPatch
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Seller Product
x-required-roles:
- SELLER
responses:
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

## DELETE /v1/seller/products/

삭제 대상 상품 ID 누락

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
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
operationId: missingProductIdOnDelete
summary: 삭제 대상 상품 ID 누락
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Seller Product
x-required-roles:
- SELLER
responses:
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

## GET /v1/internal/products/

누락된 ID 요청 거절

Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Product

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |

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
operationId: missingInternalProductId
summary: 누락된 ID 요청 거절
description: Live 서비스 토큰으로 상품 원본을 조회한다. 판매·공개 상태와 무관하며 SERVICE_live만 허용한다.
tags:
- Internal Product
security:
- ServiceToken: []
x-allowed-callers:
- live
responses:
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

## GET /v1/product-images/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[]`
**태그:** Product Image

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
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
operationId: missingProductImageId
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Product Image
security: []
responses:
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/seller/products/{productId}/sale-state

상품 전체 판매허용 변경



**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| saleEnabled | boolean | 필수 |  | — |
| version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

```json
{
  "saleEnabled": true,
  "version": 0
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 상품 전체 판매허용 변경 결과 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 요청 version 불일치 또는 저장 시점의 낙관적 락 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.name | string | 필수 |  | — |
| data.description | string | 필수 |  | — |
| data.mainImageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.sellerId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 등록 판매자 회원 ID. 기존 소유자 미지정 상품은 null. | {&quot;maxLength&quot;: 36} |
| data.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| data.saleEnabled | boolean | 필수 | Product 전체 판매허용. SKU 개별 상태·재고와 독립이다. | — |
| data.deletedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "productId": 1,
    "name": "가을 니트",
    "description": "울 혼방 니트",
    "mainImageId": 10,
    "mainImageUrl": "/api/commercecore/v1/product-images/10",
    "version": 1,
    "createdAt": "2026-09-30T03:00:00Z",
    "sellerId": "00000000-0000-0000-0000-000000000003",
    "category": "FASHION",
    "saleEnabled": true,
    "deletedAt": null
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

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | object | 필수 |  | — |
| error.code | string | 필수 |  | — |
| error.message | string | 필수 |  | — |
| error.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |

#### 400 · application/json

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
summary: 상품 전체 판매허용 변경
operationId: changeProductSaleState
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ChangeProductSaleStateRequest'
      example:
        saleEnabled: true
        version: 0
responses:
  '200':
    description: 상품 전체 판매허용 변경 결과
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductEnvelope'
        example:
          success: true
          data:
            productId: 1
            name: 가을 니트
            description: 울 혼방 니트
            mainImageId: 10
            mainImageUrl: /api/commercecore/v1/product-images/10
            version: 1
            createdAt: '2026-09-30T03:00:00Z'
            sellerId: 00000000-0000-0000-0000-000000000003
            category: FASHION
            saleEnabled: true
            deletedAt: null
          error: null
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/ProductConflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
x-required-roles:
- SELLER
tags:
- Seller Product
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
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
  parameters:
    IdempotencyKey:
      name: X-Idempotency-Key
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 64
    ProductId:
      name: productId
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
  schemas:
    RegisterProductRequest:
      type: object
      required:
      - name
      - description
      - mainImageId
      properties:
        name:
          type: string
          minLength: 1
          description: 앞뒤 공백 제거 후 1~100자
        description:
          type: string
          minLength: 1
          description: 앞뒤 공백 제거 후 1~2000자
        mainImageId:
          type: integer
          format: int64
          minimum: 1
        category:
          $ref: '#/components/schemas/ProductCategory'
    ProductCategory:
      type:
      - string
      - 'null'
      enum:
      - FASHION
      - BEAUTY
      - FOOD
      - LIVING
      - DIGITAL
      - KIDS
      - ETC
      - null
      description: 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST.
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
    ProductEnvelope:
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
          $ref: '#/components/schemas/Product'
        error:
          type: 'null'
    Product:
      type: object
      required:
      - productId
      - name
      - description
      - mainImageId
      - mainImageUrl
      - version
      - createdAt
      - sellerId
      - category
      - saleEnabled
      - deletedAt
      properties:
        productId:
          type: integer
          format: int64
        name:
          type: string
        description:
          type: string
        mainImageId:
          type: integer
          format: int64
        mainImageUrl:
          type: string
          format: uri-reference
        version:
          type: integer
          format: int64
        createdAt:
          type: string
          format: date-time
        sellerId:
          type:
          - string
          - 'null'
          description: 등록 판매자 회원 ID. 기존 소유자 미지정 상품은 null.
          maxLength: 36
        category:
          $ref: '#/components/schemas/ProductCategory'
        saleEnabled:
          type: boolean
          description: Product 전체 판매허용. SKU 개별 상태·재고와 독립이다.
        deletedAt:
          type:
          - string
          - 'null'
          format: date-time
    SellerProductPageEnvelope:
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
          $ref: '#/components/schemas/SellerProductPage'
        error:
          type: 'null'
    SellerProductPage:
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
            $ref: '#/components/schemas/SellerProductSummary'
        page:
          type: integer
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
          minimum: 0
    SellerProductSummary:
      type: object
      required:
      - productId
      - name
      - mainImageUrl
      - createdAt
      - salesStatus
      - price
      - available
      - saleEnabled
      properties:
        productId:
          type: integer
          format: int64
        name:
          type: string
        mainImageUrl:
          type: string
          format: uri-reference
        createdAt:
          type: string
          format: date-time
        salesStatus:
          $ref: '#/components/schemas/SellerSalesStatus'
        price:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 0
        available:
          type:
          - integer
          - 'null'
          minimum: 0
        saleEnabled:
          type: boolean
          description: Product 전체 판매허용. SKU 개별 상태·재고와 독립이다.
    SellerSalesStatus:
      type: string
      enum:
      - NOT_REGISTERED
      - READY
      - ON_SALE
      - SOLD_OUT
      - PRIVATE
      - UNKNOWN
      description: NOT_REGISTERED는 판매정보 미등록, UNKNOWN은 조회 실패다. 둘 다 price와 available이
        null이다.
    SellerProductDetailEnvelope:
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
          $ref: '#/components/schemas/SellerProductDetail'
        error:
          type: 'null'
    SellerProductDetail:
      type: object
      required:
      - productId
      - name
      - description
      - mainImageId
      - mainImageUrl
      - version
      - createdAt
      - updatedAt
      - salesStatus
      - price
      - available
      - category
      - saleEnabled
      properties:
        productId:
          type: integer
          format: int64
        name:
          type: string
        description:
          type: string
        mainImageId:
          type: integer
          format: int64
        mainImageUrl:
          type: string
          format: uri-reference
        version:
          type: integer
          format: int64
          minimum: 0
        createdAt:
          type: string
          format: date-time
        updatedAt:
          type: string
          format: date-time
        salesStatus:
          $ref: '#/components/schemas/SellerSalesStatus'
        price:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 0
        available:
          type:
          - integer
          - 'null'
          minimum: 0
        category:
          $ref: '#/components/schemas/ProductCategory'
        saleEnabled:
          type: boolean
          description: Product 전체 판매허용. SKU 개별 상태·재고와 독립이다.
    UpdateProductRequest:
      type: object
      required:
      - version
      description: 생략하거나 null인 기본정보 필드는 유지한다. category도 null이면 기존 값을 유지하므로 미분류로 되돌릴
        수 없다. 빈 문자열은 허용하지 않는다.
      properties:
        name:
          type:
          - string
          - 'null'
        description:
          type:
          - string
          - 'null'
        mainImageId:
          type:
          - integer
          - 'null'
          format: int64
          minimum: 1
        category:
          $ref: '#/components/schemas/ProductCategory'
        version:
          type: integer
          format: int64
          minimum: 0
    ImageUploadEnvelope:
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
          $ref: '#/components/schemas/ImageUpload'
        error:
          type: 'null'
    ImageUpload:
      type: object
      required:
      - imageId
      - url
      - contentType
      - sizeBytes
      - width
      - height
      properties:
        imageId:
          type: integer
          format: int64
        url:
          type: string
          format: uri-reference
        contentType:
          type: string
          enum:
          - image/jpeg
          - image/png
        sizeBytes:
          type: integer
          format: int64
        width:
          type: integer
        height:
          type: integer
    ProductSnapshotListEnvelope:
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
          type: array
          items:
            $ref: '#/components/schemas/ProductSnapshot'
        error:
          type: 'null'
    ProductSnapshot:
      type: object
      required:
      - id
      - name
      - mainImageUrl
      - saleEnabled
      properties:
        id:
          type: integer
          format: int64
        name:
          type: string
        mainImageUrl:
          type: string
          format: uri-reference
        sellerId:
          type:
          - string
          - 'null'
          maxLength: 36
          description: 등록 판매자 회원 ID. 기존 상품은 null이며 이전 소비자 계약과 호환되는 추가 필드.
        saleEnabled:
          type: boolean
          description: Product 전체 판매허용. SKU 개별 상태·재고와 독립이다.
    ProductSnapshotEnvelope:
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
          $ref: '#/components/schemas/ProductSnapshot'
        error:
          type: 'null'
    DevImageResetEnvelope:
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
          type: object
          required:
          - deletedCount
          properties:
            deletedCount:
              type: integer
              minimum: 0
        error:
          type: 'null'
    ChangeProductSaleStateRequest:
      type: object
      required:
      - saleEnabled
      - version
      properties:
        saleEnabled:
          type: boolean
        version:
          type: integer
          format: int64
          minimum: 0
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
    ProductConflict:
      description: 요청 version 불일치 또는 저장 시점의 낙관적 락 충돌
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
                  message: 다른 수정이 먼저 반영되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.
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
    InternalError:
      description: 서버 내부 오류
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            internalError:
              value:
                success: false
                data: null
                error:
                  code: INTERNAL_ERROR
                  message: 서버 내부 오류가 발생했습니다.
                  requestId: request-001
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

입력 SHA-256: `f2452041ad0b1d6a2542d17cdda6a000be5da6e0446fcd8f168173b110172075`
