# CommerceCore Service API / coupon

<!-- generated: OpenAPI -->

[계약 YAML](../../../../../contracts/api/commercecore-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

coupon 모듈에 속한 API를 분리해 표시합니다. 전체 계약의 공통 설명은 계약 YAML에서 확인합니다.

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

## GET /v1/seller/coupons

본인 쿠폰 목록

본인 쿠폰만 조회한다. page × size가 2147483647을 초과하면 400이다.

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
| 200 | 본인 쿠폰 페이지 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| error | null | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;&#91;allOf 1&#93;.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data.items&#91;&#93;&#91;allOf 1&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| data.items&#91;&#93;&#91;allOf 1&#93;.issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| data.items&#91;&#93;&#91;allOf 1&#93;.startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;&#91;allOf 1&#93;.endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;&#91;allOf 1&#93;.expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;&#91;allOf 1&#93;.productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |
| data.items&#91;&#93;&#91;allOf 2&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.items&#91;&#93;&#91;allOf 2&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.items&#91;&#93;&#91;allOf 2&#93;.issuedCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 10000} |
| data.items&#91;&#93;&#91;allOf 2&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.page | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data.size | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 100} |
| data.totalElements | integer | 필수 |  | {&quot;minimum&quot;: 0} |

```json
{
  "success": true,
  "data": {
    "items": [],
    "page": 0,
    "size": 20,
    "totalElements": 0
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
operationId: listSellerCoupons
summary: 본인 쿠폰 목록
description: 본인 쿠폰만 조회한다. page × size가 2147483647을 초과하면 400이다.
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
    description: 본인 쿠폰 페이지
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
            error:
              type: 'null'
            data:
              type: object
              required:
              - items
              - page
              - size
              - totalElements
              properties:
                items:
                  type: array
                  items:
                    $ref: '#/components/schemas/Coupon'
                page:
                  type: integer
                  minimum: 0
                size:
                  type: integer
                  minimum: 1
                  maximum: 100
                totalElements:
                  type: integer
                  minimum: 0
        example:
          success: true
          data:
            items: []
            page: 0
            size: 20
            totalElements: 0
          error: null
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

## POST /v1/seller/coupons

판매자 정액 쿠폰 생성

인증된 판매자 소유 상품만 대상으로 지정한다. 수량은 종류별 최대 10000장, 이벤트는 시작 포함·종료 제외 최대 720시간이다. 별도 만료 시각은 이벤트 종료 이상이다. 설정은 이벤트 시작 이후 변경할 수 없다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |

```json
{
  "name": "가을 쿠폰",
  "fixedDiscount": 1000,
  "issuanceLimit": 10000,
  "startsAt": "2026-10-10T00:00:00Z",
  "endsAt": "2026-11-09T00:00:00Z",
  "expiresAt": "2026-11-10T00:00:00Z",
  "productIds": [
    1
  ]
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 쿠폰과 대상 상품 저장 완료 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | allOf | 필수 |  | — |
| data&#91;allOf 1&#93;.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data&#91;allOf 1&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| data&#91;allOf 1&#93;.issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| data&#91;allOf 1&#93;.startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |
| data&#91;allOf 2&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.issuedCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 10000} |
| data&#91;allOf 2&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "data": {
    "id": "33333333-3333-4333-8333-333333333334",
    "sellerId": "33333333-3333-4333-8333-333333333333",
    "name": "가을 쿠폰",
    "fixedDiscount": 1000,
    "issuanceLimit": 10000,
    "issuedCount": 0,
    "startsAt": "2026-10-10T00:00:00Z",
    "endsAt": "2026-11-09T00:00:00Z",
    "expiresAt": "2026-11-10T00:00:00Z",
    "version": 0,
    "productIds": [
      1
    ]
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
operationId: createSellerCoupon
summary: 판매자 정액 쿠폰 생성
x-required-roles:
- SELLER
description: 인증된 판매자 소유 상품만 대상으로 지정한다. 수량은 종류별 최대 10000장, 이벤트는 시작 포함·종료 제외 최대 720시간이다.
  별도 만료 시각은 이벤트 종료 이상이다. 설정은 이벤트 시작 이후 변경할 수 없다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/CreateCoupon'
      example:
        name: 가을 쿠폰
        fixedDiscount: 1000
        issuanceLimit: 10000
        startsAt: '2026-10-10T00:00:00Z'
        endsAt: '2026-11-09T00:00:00Z'
        expiresAt: '2026-11-10T00:00:00Z'
        productIds:
        - 1
responses:
  '201':
    description: 쿠폰과 대상 상품 저장 완료
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CouponEnvelope'
        example:
          success: true
          data:
            id: 33333333-3333-4333-8333-333333333334
            sellerId: 33333333-3333-4333-8333-333333333333
            name: 가을 쿠폰
            fixedDiscount: 1000
            issuanceLimit: 10000
            issuedCount: 0
            startsAt: '2026-10-10T00:00:00Z'
            endsAt: '2026-11-09T00:00:00Z'
            expiresAt: '2026-11-10T00:00:00Z'
            version: 0
            productIds:
            - 1
          error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/ShoppingUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/seller/coupons/{id}

본인 쿠폰 상세



**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 쿠폰 상세 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | allOf | 필수 |  | — |
| data&#91;allOf 1&#93;.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data&#91;allOf 1&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| data&#91;allOf 1&#93;.issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| data&#91;allOf 1&#93;.startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |
| data&#91;allOf 2&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.issuedCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 10000} |
| data&#91;allOf 2&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
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
- Seller Sales
operationId: getSellerCoupon
summary: 본인 쿠폰 상세
x-required-roles:
- SELLER
responses:
  '200':
    description: 쿠폰 상세
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CouponEnvelope'
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
  - name: id
    in: path
    required: true
    schema:
      type: string
      format: uuid
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/seller/coupons/{id}

발급 이벤트 시작 전 쿠폰 설정 수정

현재 version과 전체 settings를 전달한다. 행 잠금 이후 서버 시각을 확인하며 기존 이벤트 시작 시각 이상이거나 version이 다르면 409이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Sales

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | string |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| settings | object | 필수 |  | — |
| settings.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| settings.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| settings.issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| settings.startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| settings.endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| settings.expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| settings.productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 변경된 쿠폰 설정 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |
| 409 | 중복·상태·동시 수정·멱등키 입력 충돌 |
| 503 | Shopping HTTP 오류·타임아웃·응답 계약 위반은 SHOPPING_UNAVAILABLE. Member 세션 인증 상태 확인 장애는 SERVICE_UNAVAILABLE. 확인된 상품 미존재 404와 구분한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | allOf | 필수 |  | — |
| data&#91;allOf 1&#93;.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data&#91;allOf 1&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1, &quot;maximum&quot;: 9223372036854775807} |
| data&#91;allOf 1&#93;.issuanceLimit | integer | 필수 |  | {&quot;minimum&quot;: 1, &quot;maximum&quot;: 10000} |
| data&#91;allOf 1&#93;.startsAt | string | 필수 | 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.endsAt | string | 필수 | 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.expiresAt | string | 필수 | 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답. | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;allOf 1&#93;.productIds | array | 필수 |  | {&quot;minItems&quot;: 1} |
| data&#91;allOf 2&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.sellerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;allOf 2&#93;.issuedCount | integer | 필수 |  | {&quot;minimum&quot;: 0, &quot;maximum&quot;: 10000} |
| data&#91;allOf 2&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
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
- Seller Sales
operationId: updateSellerCoupon
summary: 발급 이벤트 시작 전 쿠폰 설정 수정
x-required-roles:
- SELLER
description: 현재 version과 전체 settings를 전달한다. 행 잠금 이후 서버 시각을 확인하며 기존 이벤트 시작 시각 이상이거나
  version이 다르면 409이다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        type: object
        required:
        - version
        - settings
        properties:
          version:
            type: integer
            format: int64
            minimum: 0
          settings:
            $ref: '#/components/schemas/CreateCoupon'
responses:
  '200':
    description: 변경된 쿠폰 설정
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/CouponEnvelope'
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
경로 공통 선언:
  parameters:
  - name: id
    in: path
    required: true
    schema:
      type: string
      format: uuid
적용 서버:
- url: http://localhost:8083
  description: CommerceCore 서비스 로컬 직접 호출
- url: http://dev-commercecore-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/coupons

회원이 수령할 수 있는 쿠폰 조회

현재 이벤트 중이고 수량이 남은 쿠폰. 같은 종류를 이미 수령한 회원에게는 노출하지 않는다. 이벤트 종료와 쿠폰 사용 만료는 별도다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Coupons

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| page | query | 선택 | integer |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 수령 가능한 쿠폰 목록 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;&#93;.name | string | 필수 |  | — |
| data&#91;&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.remaining | integer | 필수 |  | {&quot;minimum&quot;: 1} |
| data&#91;&#93;.endsAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
- Coupons
operationId: listClaimableCoupons
summary: 회원이 수령할 수 있는 쿠폰 조회
x-required-roles:
- USER
- SELLER
description: 현재 이벤트 중이고 수량이 남은 쿠폰. 같은 종류를 이미 수령한 회원에게는 노출하지 않는다. 이벤트 종료와 쿠폰 사용 만료는
  별도다.
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
    description: 수령 가능한 쿠폰 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/AvailableCouponsEnvelope'
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

## POST /v1/coupons/{id}/claims

쿠폰 수령

같은 회원·쿠폰의 재요청은 기존 수령 결과를 반환하며 추가 발급하지 않는다. 회원은 같은 종류를 한 번만 수령할 수 있고 환불 후에도 재수령할 수 없다. 이벤트 시작은 포함, 종료는 제외한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Coupons

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 새 쿠폰 수령 완료 |
| 200 | 같은 회원의 기존 수령 결과 |
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
| data.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.couponId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.name | string | 필수 |  | — |
| data.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;AVAILABLE&quot;, &quot;RESERVED&quot;, &quot;USED&quot;, &quot;EXPIRED&quot;&#93;} |
| data.claimedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.couponId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.name | string | 필수 |  | — |
| data.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;AVAILABLE&quot;, &quot;RESERVED&quot;, &quot;USED&quot;, &quot;EXPIRED&quot;&#93;} |
| data.claimedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
- Coupons
operationId: claimCoupon
summary: 쿠폰 수령
x-required-roles:
- USER
- SELLER
description: 같은 회원·쿠폰의 재요청은 기존 수령 결과를 반환하며 추가 발급하지 않는다. 회원은 같은 종류를 한 번만 수령할 수 있고 환불
  후에도 재수령할 수 없다. 이벤트 시작은 포함, 종료는 제외한다.
parameters:
- name: id
  in: path
  required: true
  schema:
    type: string
    format: uuid
responses:
  '201':
    description: 새 쿠폰 수령 완료
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberCouponEnvelope'
  '200':
    description: 같은 회원의 기존 수령 결과
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberCouponEnvelope'
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

## GET /v1/me/coupons

내 쿠폰과 상태 조회



**인증:** `[{"BearerAuth": []}]`
**태그:** Coupons

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| page | query | 선택 | integer |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 수령 이력과 현재 상태 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;&#93;.couponId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data&#91;&#93;.name | string | 필수 |  | — |
| data&#91;&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;AVAILABLE&quot;, &quot;RESERVED&quot;, &quot;USED&quot;, &quot;EXPIRED&quot;&#93;} |
| data&#91;&#93;.claimedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.expiresAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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
- Coupons
operationId: listMyCoupons
summary: 내 쿠폰과 상태 조회
x-required-roles:
- USER
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
    description: 수령 이력과 현재 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberCouponsEnvelope'
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

## GET /v1/internal/coupon-snapshots

쿠폰 표시 사본 초기 적재·주기적 동기화

Shopping 전용. endsAt &gt; 서버 현재 시각인 미래·진행 쿠폰을 ID 오름차순으로 반환하며 소진 쿠폰도 포함한다. afterId는 마지막 ID를 그대로 전달한다. 페이지 전체는 단일 시점이 아니므로 이벤트 소비를 먼저 시작하고 API와 이벤트의 snapshotVersion 중 더 큰 값을 적용한다. 재동기화 중 누락될 수 있는 동시 생성은 이벤트 소비와 다음 전체 순회로 복구한다. 일반 상품 조회마다 호출하지 않는다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Coupons

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| afterId | query | 선택 | string | 미지정은 첫 페이지. 빈 문자열은 400. 반환한 nextCursor를 그대로 전달한다. |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | {&quot;maxItems&quot;: 100} |
| data.items&#91;&#93;.couponId | string | 필수 | 기존 ID를 유지하는 opaque 문자열. UUID 형식으로 한정하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 36, &quot;pattern&quot;: &quot;\\S&quot;} |
| data.items&#91;&#93;.snapshotVersion | integer | 필수 | 표시 스냅샷의 쿠폰별 버전. 판매자 설정 version과 독립적이다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.items&#91;&#93;.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data.items&#91;&#93;.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.items&#91;&#93;.productIds | array | 필수 | 정렬된 전체 대상 상품 목록. 이전 목록을 교체한다. 빈 목록은 기존 연결을 전부 제거한다. | — |
| data.items&#91;&#93;.startsAt | string | 필수 | 발급 시작 시각, 포함 경계. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;.endsAt | string | 필수 | 발급 종료 시각, 제외 경계. 별도 시간 경계 이벤트는 발행하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;.expiresAt | string | 필수 | 발급된 회원 쿠폰의 사용 만료 시각. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;.soldOut | boolean | 필수 | 발급 수량 소진 여부. 사용·환불로 발급 수량을 되돌리지 않는다. | — |
| data.nextCursor | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 다음 페이지가 있을 때 마지막 반환 쿠폰 ID, 없으면 null. | — |
| data.hasNext | boolean | 필수 |  | — |
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

### 계약 원문

```yaml
security:
- ServiceToken: []
x-allowed-callers:
- shopping
tags:
- Internal Coupons
operationId: listCouponDisplaySnapshots
summary: 쿠폰 표시 사본 초기 적재·주기적 동기화
description: Shopping 전용. endsAt > 서버 현재 시각인 미래·진행 쿠폰을 ID 오름차순으로 반환하며 소진 쿠폰도 포함한다.
  afterId는 마지막 ID를 그대로 전달한다. 페이지 전체는 단일 시점이 아니므로 이벤트 소비를 먼저 시작하고 API와 이벤트의 snapshotVersion
  중 더 큰 값을 적용한다. 재동기화 중 누락될 수 있는 동시 생성은 이벤트 소비와 다음 전체 순회로 복구한다. 일반 상품 조회마다 호출하지 않는다.
parameters:
- name: afterId
  in: query
  required: false
  schema:
    type: string
    minLength: 1
    maxLength: 36
    pattern: \S
    description: 기존 ID를 유지하는 opaque 문자열. UUID 형식으로 한정하지 않는다.
  description: 미지정은 첫 페이지. 빈 문자열은 400. 반환한 nextCursor를 그대로 전달한다.
- name: size
  in: query
  required: false
  schema:
    type: integer
    minimum: 1
    maximum: 100
    default: 100
responses:
  '200':
    description: 성공
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
              $ref: '#/components/schemas/CouponSnapshotPage'
            error:
              type: 'null'
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

## GET /v1/internal/coupon-snapshots/{couponId}

쿠폰 표시 사본 단건 복구

Shopping 전용. 종료된 쿠폰도 현재 전체 상태를 반환한다. 미존재 ID는 404이며 기존 opaque ID를 유지한다. 사용자 JWT로는 접근할 수 없다. 회원 쿠폰 상태는 반환하지 않는다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Internal Coupons

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| couponId | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 또는 금액 범위 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 검증된 주체의 권한 부족 또는 비활성 개발 API |
| 404 | 대상이 없거나 다른 회원 소유. SELLER도 동일한 본인 조건 적용. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 | 상품 표시용 전체 쿠폰 스냅샷. 회원 발급/사용 상태와 발급 잔여 수량은 포함하지 않는다. 실제 발급·사용 판정은 Commerce가 수행한다. | — |
| data.couponId | string | 필수 | 기존 ID를 유지하는 opaque 문자열. UUID 형식으로 한정하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 36, &quot;pattern&quot;: &quot;\\S&quot;} |
| data.snapshotVersion | integer | 필수 | 표시 스냅샷의 쿠폰별 버전. 판매자 설정 version과 독립적이다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.name | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| data.fixedDiscount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.productIds | array | 필수 | 정렬된 전체 대상 상품 목록. 이전 목록을 교체한다. 빈 목록은 기존 연결을 전부 제거한다. | — |
| data.startsAt | string | 필수 | 발급 시작 시각, 포함 경계. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endsAt | string | 필수 | 발급 종료 시각, 제외 경계. 별도 시간 경계 이벤트는 발행하지 않는다. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.expiresAt | string | 필수 | 발급된 회원 쿠폰의 사용 만료 시각. | {&quot;format&quot;: &quot;date-time&quot;} |
| data.soldOut | boolean | 필수 | 발급 수량 소진 여부. 사용·환불로 발급 수량을 되돌리지 않는다. | — |
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

### 계약 원문

```yaml
security:
- ServiceToken: []
x-allowed-callers:
- shopping
tags:
- Internal Coupons
operationId: getCouponDisplaySnapshot
summary: 쿠폰 표시 사본 단건 복구
description: Shopping 전용. 종료된 쿠폰도 현재 전체 상태를 반환한다. 미존재 ID는 404이며 기존 opaque ID를 유지한다.
  사용자 JWT로는 접근할 수 없다. 회원 쿠폰 상태는 반환하지 않는다.
parameters:
- name: couponId
  in: path
  required: true
  schema:
    type: string
    minLength: 1
    maxLength: 36
    pattern: \S
    description: 기존 ID를 유지하는 opaque 문자열. UUID 형식으로 한정하지 않는다.
responses:
  '200':
    description: 성공
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
              $ref: '#/components/schemas/CouponDisplaySnapshot'
            error:
              type: 'null'
  '400':
    $ref: '#/components/responses/BadRequest'
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

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### commercecore-service.yaml

```yaml
components:
  schemas:
    Coupon:
      allOf:
      - $ref: '#/components/schemas/CreateCoupon'
      - type: object
        required:
        - id
        - sellerId
        - issuedCount
        - version
        properties:
          id:
            type: string
            format: uuid
          sellerId:
            type: string
            format: uuid
          issuedCount:
            type: integer
            minimum: 0
            maximum: 10000
          version:
            type: integer
            format: int64
            minimum: 0
    CreateCoupon:
      type: object
      required:
      - name
      - fixedDiscount
      - issuanceLimit
      - startsAt
      - endsAt
      - expiresAt
      - productIds
      properties:
        name:
          type: string
          minLength: 1
          maxLength: 100
        fixedDiscount:
          type: integer
          format: int64
          minimum: 1
          maximum: 9223372036854775807
        issuanceLimit:
          type: integer
          minimum: 1
          maximum: 10000
        startsAt:
          type: string
          format: date-time
          description: 0001~9999년 범위. 원본 기간 검증 후 마이크로초로 절삭·재검증하여 저장과 응답에 사용.
        endsAt:
          type: string
          format: date-time
          description: 시작보다 늦고 원본·저장 시각 모두 최대 720시간. 정밀도 절삭 후 기간이 0이면 400.
        expiresAt:
          type: string
          format: date-time
          description: 종료 이상, 9999년 이하. 마이크로초 정밀도로 저장·응답.
        productIds:
          type: array
          minItems: 1
          uniqueItems: true
          items:
            type: integer
            format: int64
            minimum: 1
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
    CouponEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: '#/components/schemas/Coupon'
        error:
          type: 'null'
    AvailableCouponsEnvelope:
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
            $ref: '#/components/schemas/AvailableCoupon'
        error:
          type: 'null'
    AvailableCoupon:
      type: object
      required:
      - id
      - name
      - fixedDiscount
      - remaining
      - endsAt
      - expiresAt
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        fixedDiscount:
          type: integer
          format: int64
          minimum: 1
        remaining:
          type: integer
          minimum: 1
        endsAt:
          type: string
          format: date-time
        expiresAt:
          type: string
          format: date-time
    MemberCouponEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: '#/components/schemas/MemberCoupon'
        error:
          type: 'null'
    MemberCoupon:
      type: object
      required:
      - id
      - couponId
      - name
      - fixedDiscount
      - status
      - claimedAt
      - expiresAt
      properties:
        id:
          type: string
          format: uuid
        couponId:
          type: string
          format: uuid
        name:
          type: string
        fixedDiscount:
          type: integer
          format: int64
          minimum: 1
        status:
          type: string
          enum:
          - AVAILABLE
          - RESERVED
          - USED
          - EXPIRED
        claimedAt:
          type: string
          format: date-time
        expiresAt:
          type: string
          format: date-time
    MemberCouponsEnvelope:
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
            $ref: '#/components/schemas/MemberCoupon'
        error:
          type: 'null'
    CouponSnapshotPage:
      type: object
      required:
      - items
      - nextCursor
      - hasNext
      properties:
        items:
          type: array
          maxItems: 100
          items:
            $ref: '#/components/schemas/CouponDisplaySnapshot'
        nextCursor:
          type:
          - string
          - 'null'
          description: 다음 페이지가 있을 때 마지막 반환 쿠폰 ID, 없으면 null.
        hasNext:
          type: boolean
    CouponDisplaySnapshot:
      type: object
      required:
      - couponId
      - snapshotVersion
      - name
      - fixedDiscount
      - productIds
      - startsAt
      - endsAt
      - expiresAt
      - soldOut
      properties:
        couponId:
          type: string
          minLength: 1
          maxLength: 36
          pattern: \S
          description: 기존 ID를 유지하는 opaque 문자열. UUID 형식으로 한정하지 않는다.
        snapshotVersion:
          type: integer
          format: int64
          minimum: 1
          description: 표시 스냅샷의 쿠폰별 버전. 판매자 설정 version과 독립적이다.
        name:
          type: string
          minLength: 1
          maxLength: 100
        fixedDiscount:
          type: integer
          format: int64
          minimum: 1
        productIds:
          type: array
          uniqueItems: true
          items:
            type: integer
            format: int64
            minimum: 1
          description: 정렬된 전체 대상 상품 목록. 이전 목록을 교체한다. 빈 목록은 기존 연결을 전부 제거한다.
        startsAt:
          type: string
          format: date-time
          description: 발급 시작 시각, 포함 경계.
        endsAt:
          type: string
          format: date-time
          description: 발급 종료 시각, 제외 경계. 별도 시간 경계 이벤트는 발행하지 않는다.
        expiresAt:
          type: string
          format: date-time
          description: 발급된 회원 쿠폰의 사용 만료 시각.
        soldOut:
          type: boolean
          description: 발급 수량 소진 여부. 사용·환불로 발급 수량을 되돌리지 않는다.
      description: 상품 표시용 전체 쿠폰 스냅샷. 회원 발급/사용 상태와 발급 잔여 수량은 포함하지 않는다. 실제 발급·사용 판정은
        Commerce가 수행한다.
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

입력 SHA-256: `9f384ac228873bb3d7968c46f0649b19f635b35bb6fb0e9997bbc00b0bc4f847`
