# Catalog 공개 상품 조회 API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/catalog-service.yaml) · OpenAPI 3.1.0 · 계약 2.1.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

Catalog는 Core 상품 원본 snapshot과 기존 판매·쿠폰 사본으로 공개 상품 목록·상세·구매 사전확인·쿠폰 조회를 제공한다. 상품 쓰기·판매자 관리·이미지 파일과 내부 상품 조회는 CommerceCore가 소유한다. 사본 반영은 비동기이며 가격·재고의 최종 검증은 Core 주문 생성 시 수행한다. 기존 /v1 응답 DTO와 사본 조회 기본값을 유지한다.

## 공통 인증과 서버

```yaml
security:
- BearerAuth: []
securitySchemes: {}
servers:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/products

공개 상품 목록

검색 인덱스(Elasticsearch)에서 조건에 맞는 상품을 정렬된 순서로 찾고, 가격, 재고, 판매 상태는 응답 때 product_sales 이벤트 사본에서 붙인다. Commerce가 응답하지 않아도 200을 반환한다. 검색 인덱스는 사본 변경 뒤 몇 초 늦게 반영되며, 그 사이 삭제되거나 공개 대상이 아니게 된 상품은 응답에서 뺀다. 상세·주문 가격 확정은 기존 경로를 유지한다.
q는 상품명을 한국어 형태소 단위로 검색한다. 조사가 붙은 검색어도 찾으며 검색어의 단어 중 하나라도 들어간 상품을 반환한다. 대소문자를 구분하지 않고 특수문자는 무시한다. 가격 동률은 ID로 정렬한다. cursor는 같은 q/status/category/sort 조건에서만 재사용하며 조건 변경 시 생략한다. 조회 도중 가격·상태 변경 시 실시간 목록 특성상 페이지 간 위치가 바뀔 수 있다.
category는 해당 분류 상품만 반환하며 생략하면 미분류(null)를 포함한 전체다. 미분류만 고르는 값은 없다.
q/status/category/sort를 모두 생략하면 sort=LATEST와 같은 결과를 반환한다. 이전 형식의 cursor는 400 INVALID_REQUEST다. SKU가 여러 개인 상품은 한 줄로 반환한다. 판매 중 SKU가 하나라도 있으면 ON_SALE이고 price는 판매 중 SKU 최저가, available은 판매 중 SKU 재고의 합이다. 모두 품절이면 SOLD_OUT이고 품절 SKU의 최저가와 재고 합이다. 가격 정렬과 가격 기준도 이 대표 가격이다. 비공개 SKU는 계산에서 뺀다. 검색 인덱스에 연결할 수 없으면 사본으로 대신 검색하지 않고 503 SERVICE_UNAVAILABLE이다.

**인증:** `[]`
**태그:** Public Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| q | query | 선택 | string |  |
| status | query | 선택 | string |  |
| category | query | 선택 | string | 목록에 없는 값은 400 INVALID_REQUEST. |
| sort | query | 선택 | string |  |
| cursor | query | 선택 | string | 이전 응답의 nextCursor. 형식은 내부 구현이므로 변경 가능하다. |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다. 실제 가격·판매 가능량은 Core 주문 생성에서 검증한다. |
| 400 | 요청 형식 또는 값이 올바르지 않음 |
| 503 | 상품 검색 인덱스에 연결할 수 없음 |

#### 401 · application/json

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
| data | object | 필수 | 종료 여부는 hasNext로 판단한다. 비공개 상품을 건너뛰면 items가 비어도 hasNext=true일 수 있다. | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.items&#91;&#93;.name | string | 필수 |  | — |
| data.items&#91;&#93;.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.items&#91;&#93;.price | integer | 필수 | 대표 가격. SKU가 여러 개면 판매 중 SKU 최저가, 모두 품절이면 품절 SKU 최저가다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.items&#91;&#93;.available | integer | 필수 | 결제 대기 예약분을 제외한 주문 가능 재고. SKU가 여러 개면 salesStatus와 같은 상태인 SKU 재고의 합이다. 판매 상태와 별개이며 ON_SALE에서 0이어도 상태를 변경하지 않는다. | {&quot;minimum&quot;: 0} |
| data.items&#91;&#93;.salesStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;&#93;} |
| data.items&#91;&#93;.purchasable | boolean | 필수 | 공개 판매 상태와 상품 전체 판매 허용에 따른 Core 확인 진입 가능 표시. 재고 사본을 사용한 구매 확정이 아니다. | — |
| data.items&#91;&#93;.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
| data.nextCursor | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
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
- Public Product
summary: 공개 상품 목록
operationId: listPublicProducts
description: '검색 인덱스(Elasticsearch)에서 조건에 맞는 상품을 정렬된 순서로 찾고, 가격, 재고, 판매 상태는 응답 때 product_sales
  이벤트 사본에서 붙인다. Commerce가 응답하지 않아도 200을 반환한다. 검색 인덱스는 사본 변경 뒤 몇 초 늦게 반영되며, 그 사이 삭제되거나
  공개 대상이 아니게 된 상품은 응답에서 뺀다. 상세·주문 가격 확정은 기존 경로를 유지한다.

  q는 상품명을 한국어 형태소 단위로 검색한다. 조사가 붙은 검색어도 찾으며 검색어의 단어 중 하나라도 들어간 상품을 반환한다. 대소문자를 구분하지
  않고 특수문자는 무시한다. 가격 동률은 ID로 정렬한다. cursor는 같은 q/status/category/sort 조건에서만 재사용하며 조건
  변경 시 생략한다. 조회 도중 가격·상태 변경 시 실시간 목록 특성상 페이지 간 위치가 바뀔 수 있다.

  category는 해당 분류 상품만 반환하며 생략하면 미분류(null)를 포함한 전체다. 미분류만 고르는 값은 없다.

  q/status/category/sort를 모두 생략하면 sort=LATEST와 같은 결과를 반환한다. 이전 형식의 cursor는 400 INVALID_REQUEST다.
  SKU가 여러 개인 상품은 한 줄로 반환한다. 판매 중 SKU가 하나라도 있으면 ON_SALE이고 price는 판매 중 SKU 최저가, available은
  판매 중 SKU 재고의 합이다. 모두 품절이면 SOLD_OUT이고 품절 SKU의 최저가와 재고 합이다. 가격 정렬과 가격 기준도 이 대표 가격이다.
  비공개 SKU는 계산에서 뺀다. 검색 인덱스에 연결할 수 없으면 사본으로 대신 검색하지 않고 503 SERVICE_UNAVAILABLE이다.'
parameters:
- name: q
  in: query
  schema:
    type: string
    maxLength: 100
- name: status
  in: query
  schema:
    type: string
    enum:
    - ON_SALE
    - SOLD_OUT
- name: category
  in: query
  description: 목록에 없는 값은 400 INVALID_REQUEST.
  schema:
    type: string
    enum:
    - FASHION
    - BEAUTY
    - FOOD
    - LIVING
    - DIGITAL
    - KIDS
    - ETC
- name: sort
  in: query
  schema:
    type: string
    enum:
    - LATEST
    - PRICE_ASC
    - PRICE_DESC
    default: LATEST
- name: cursor
  in: query
  description: 이전 응답의 nextCursor. 형식은 내부 구현이므로 변경 가능하다.
  schema:
    type: string
- name: size
  in: query
  schema:
    type: integer
    minimum: 1
    maximum: 50
    default: 20
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다.
      실제 가격·판매 가능량은 Core 주문 생성에서 검증한다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PublicProductListEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                items:
                - productId: 1
                  name: 가을 니트
                  mainImageUrl: /api/commercecore/v1/product-images/10
                  price: 39000
                  available: 5
                  salesStatus: ON_SALE
                  purchasable: true
                  category: FASHION
                nextCursor: null
                hasNext: false
              error: null
          zeroStock:
            value:
              success: true
              data:
                items:
                - productId: 1
                  name: 가을 니트
                  mainImageUrl: /api/commercecore/v1/product-images/10
                  price: 39000
                  available: 0
                  salesStatus: ON_SALE
                  purchasable: true
                  category: null
                nextCursor: null
                hasNext: false
              error: null
          empty:
            value:
              success: true
              data:
                items: []
                nextCursor: null
                hasNext: false
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '503':
    $ref: '#/components/responses/SearchUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/products/{productId}

공개 상품 상세



**인증:** `[]`
**태그:** Public Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다. 실제 가격·판매 가능량은 Core 주문 생성에서 검증한다. |
| 400 | 요청 형식 또는 값이 올바르지 않음 |
| 404 | 대상이 없거나 공개 대상이 아님 |
| 503 | 판매정보를 조회할 수 없음 |

#### 401 · application/json

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
| data.mainImageUrl | string | 필수 |  | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data.price | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.salesStatus | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;&#93;} |
| data.available | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data.purchasable | boolean | 필수 | 공개 판매 상태와 상품 전체 판매 허용에 따른 Core 확인 진입 가능 표시. 재고 사본을 사용한 구매 확정이 아니다. | — |
| data.maxQuantity | integer | 필수 | 수량 입력 형식 상한 999999999. 상품 전체 판매 중지이면 0이며 실제 구매 가능량은 Core에서 검증한다. | {&quot;minimum&quot;: 0} |
| data.category | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 1단계 상품 분류. null은 미분류(기존 상품 포함)다. 요청에서 목록에 없는 값은 400 INVALID_REQUEST. | {&quot;enum&quot;: &#91;&quot;FASHION&quot;, &quot;BEAUTY&quot;, &quot;FOOD&quot;, &quot;LIVING&quot;, &quot;DIGITAL&quot;, &quot;KIDS&quot;, &quot;ETC&quot;, null&#93;} |
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
security: []
tags:
- Public Product
summary: 공개 상품 상세
operationId: getPublicProduct
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다.
      실제 가격·판매 가능량은 Core 주문 생성에서 검증한다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PublicProductDetailEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                productId: 1
                name: 가을 니트
                description: 울 혼방 니트
                mainImageUrl: /api/commercecore/v1/product-images/10
                price: 39000
                salesStatus: ON_SALE
                available: 5
                purchasable: true
                maxQuantity: 999999999
                category: FASHION
              error: null
          soldOut:
            value:
              success: true
              data:
                productId: 1
                name: 가을 니트
                description: 울 혼방 니트
                mainImageUrl: /api/commercecore/v1/product-images/10
                price: 39000
                salesStatus: SOLD_OUT
                available: 0
                purchasable: true
                maxQuantity: 999999999
                category: null
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/SalesUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/products/{productId}/purchase-check

주문 전 구매 가능 수량 확인



**인증:** `[]`
**태그:** Public Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |
| quantity | query | 필수 | integer | 부호·선행 0·소수점 없이 1~999999999를 전송한다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다. 실제 가격·판매 가능량은 Core 주문 생성에서 검증한다. |
| 400 | 요청 형식 또는 값이 올바르지 않음 |
| 404 | 대상이 없거나 공개 대상이 아님 |
| 503 | 판매정보를 조회할 수 없음 |

#### 401 · application/json

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
| data.quantity | integer | 필수 |  | — |
| data.orderable | boolean | 필수 | 상품 전체 판매가 허용된 공개 상품이면 true. 사본 수량과 품절만으로 거절하지 않으며 실제 주문 승인은 Core가 판단한다. | — |
| data.reason | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 상품 전체 중지는 SALE_SUSPENDED. SOLD_OUT/EXCEEDS_STOCK은 구 응답 호환 enum이며 새 사전 확인에서는 사본 재고를 이유로 반환하지 않는다. | {&quot;enum&quot;: &#91;&quot;SALE_SUSPENDED&quot;, &quot;SOLD_OUT&quot;, &quot;EXCEEDS_STOCK&quot;, null&#93;} |
| data.maxQuantity | integer | 필수 | 수량 입력 형식 상한 999999999. 상품 전체 판매 중지이면 0이며 실제 구매 가능량은 Core에서 검증한다. | — |
| data.unitPrice | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
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
security: []
tags:
- Public Product
summary: 주문 전 구매 가능 수량 확인
operationId: checkPurchase
parameters:
- name: quantity
  in: query
  required: true
  description: 부호·선행 0·소수점 없이 1~999999999를 전송한다.
  schema:
    type: integer
    minimum: 1
    maximum: 999999999
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 지연된 재고 사본은 표시 정보다. 공개 판매 상품의 주문 화면 진입을 허용하며 재고를 예약하거나 구매를 승인하지 않는다.
      실제 가격·판매 가능량은 Core 주문 생성에서 검증한다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PurchaseCheckEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                productId: 1
                quantity: 1
                orderable: true
                reason: null
                maxQuantity: 999999999
                unitPrice: 39000
              error: null
          soldOut:
            value:
              success: true
              data:
                productId: 1
                quantity: 1
                orderable: true
                reason: null
                maxQuantity: 999999999
                unitPrice: 39000
              error: null
          exceedsStock:
            value:
              success: true
              data:
                productId: 1
                quantity: 6
                orderable: true
                reason: null
                maxQuantity: 999999999
                unitPrice: 39000
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/SalesUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/products/{productId}/coupons

상품에 적용 가능한 쿠폰

Commerce 쿠폰 이벤트로 채운 replica를 읽는다. 요청마다 Commerce를 호출하지 않는다. startsAt &lt;= 지금 &lt; endsAt인 쿠폰만 endsAt 오름차순으로 준다. 소진된 쿠폰도 soldOut=true로 준다. 회원별 보유·사용 여부는 주지 않는다. 받기와 최종 적용 판정은 Commerce가 한다. 쿠폰이 없거나 모르는 상품이면 빈 목록이다. 쿠폰 적용 예상가는 클라이언트가 판매가 - fixedDiscount(0 미만은 0)로 계산한다. 표시정보는 Commerce보다 늦을 수 있다. 보통 이벤트 전달 시간만큼 늦고, 이벤트를 놓치면 다음 주기 동기화(기본 10분)에 맞춰진다. 이 API가 실패해도 상품 상세 API에는 영향이 없다. 화면은 쿠폰 영역만 숨기고 상품은 그대로 보여 준다.

**인증:** `[]`
**태그:** Public Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 형식 또는 값이 올바르지 않음 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.couponId | string | 필수 | Commerce 쿠폰 ID. UUID로 한정하지 않는 opaque 문자열 | {&quot;maxLength&quot;: 36} |
| data&#91;&#93;.name | string | 필수 |  | {&quot;maxLength&quot;: 100} |
| data&#91;&#93;.fixedDiscount | integer | 필수 | 정액 할인 금액(원) | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data&#91;&#93;.startsAt | string | 필수 | 발급 시작 | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.endsAt | string | 필수 | 발급 종료 | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.expiresAt | string | 필수 | 발급받은 쿠폰의 사용 만료 | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.soldOut | boolean | 필수 | 발급 수량 소진 여부 | — |
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
security: []
tags:
- Public Product
summary: 상품에 적용 가능한 쿠폰
operationId: listProductCoupons
description: Commerce 쿠폰 이벤트로 채운 replica를 읽는다. 요청마다 Commerce를 호출하지 않는다. startsAt <=
  지금 < endsAt인 쿠폰만 endsAt 오름차순으로 준다. 소진된 쿠폰도 soldOut=true로 준다. 회원별 보유·사용 여부는 주지 않는다.
  받기와 최종 적용 판정은 Commerce가 한다. 쿠폰이 없거나 모르는 상품이면 빈 목록이다. 쿠폰 적용 예상가는 클라이언트가 판매가 - fixedDiscount(0
  미만은 0)로 계산한다. 표시정보는 Commerce보다 늦을 수 있다. 보통 이벤트 전달 시간만큼 늦고, 이벤트를 놓치면 다음 주기 동기화(기본
  10분)에 맞춰진다. 이 API가 실패해도 상품 상세 API에는 영향이 없다. 화면은 쿠폰 영역만 숨기고 상품은 그대로 보여 준다.
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ProductCouponListEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
              - couponId: coupon-opaque-id
                name: 상품 할인 쿠폰
                fixedDiscount: 1000
                startsAt: '2026-10-15T09:00:00Z'
                endsAt: '2026-10-16T09:00:00Z'
                expiresAt: '2026-10-17T09:00:00Z'
                soldOut: false
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/ProductId'
적용 서버:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/products/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[]`
**태그:** Public Product

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 요청 형식 또는 값이 올바르지 않음 |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 503 | 판매정보를 조회할 수 없음 |

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
operationId: missingPublicProductId
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Public Product
security: []
responses:
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '503':
    $ref: '#/components/responses/SalesUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8082
  description: Shopping 서비스 로컬 실행 주소 (port 8082)
- url: http://dev-catalog-service:8080
  description: Shopping 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### catalog-service.yaml

```yaml
components:
  responses:
    Unauthorized:
      description: 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401.
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
    BadRequest:
      description: 요청 형식 또는 값이 올바르지 않음
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
    SearchUnavailable:
      description: 상품 검색 인덱스에 연결할 수 없음
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            searchUnavailable:
              value:
                success: false
                data: null
                error:
                  code: SERVICE_UNAVAILABLE
                  message: 상품 검색을 잠시 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.
                  requestId: request-001
    NotFound:
      description: 대상이 없거나 공개 대상이 아님
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
    SalesUnavailable:
      description: 판매정보를 조회할 수 없음
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            salesUnavailable:
              value:
                success: false
                data: null
                error:
                  code: SALES_INFO_UNAVAILABLE
                  message: 판매정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
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
    PublicProductListEnvelope:
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
          $ref: '#/components/schemas/PublicProductList'
        error:
          type: 'null'
    PublicProductList:
      type: object
      required:
      - items
      - nextCursor
      - hasNext
      description: 종료 여부는 hasNext로 판단한다. 비공개 상품을 건너뛰면 items가 비어도 hasNext=true일 수 있다.
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/PublicProductItem'
        nextCursor:
          type:
          - string
          - 'null'
        hasNext:
          type: boolean
    PublicProductItem:
      type: object
      required:
      - productId
      - name
      - mainImageUrl
      - price
      - available
      - salesStatus
      - purchasable
      - category
      properties:
        productId:
          type: integer
          format: int64
        name:
          type: string
        mainImageUrl:
          type: string
          format: uri-reference
        price:
          type: integer
          format: int64
          minimum: 0
          description: 대표 가격. SKU가 여러 개면 판매 중 SKU 최저가, 모두 품절이면 품절 SKU 최저가다.
        available:
          type: integer
          minimum: 0
          description: 결제 대기 예약분을 제외한 주문 가능 재고. SKU가 여러 개면 salesStatus와 같은 상태인 SKU
            재고의 합이다. 판매 상태와 별개이며 ON_SALE에서 0이어도 상태를 변경하지 않는다.
        salesStatus:
          type: string
          enum:
          - ON_SALE
          - SOLD_OUT
        purchasable:
          type: boolean
          description: 공개 판매 상태와 상품 전체 판매 허용에 따른 Core 확인 진입 가능 표시. 재고 사본을 사용한 구매 확정이
            아니다.
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
    PublicProductDetailEnvelope:
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
          $ref: '#/components/schemas/PublicProductDetail'
        error:
          type: 'null'
    PublicProductDetail:
      type: object
      required:
      - productId
      - name
      - description
      - mainImageUrl
      - price
      - salesStatus
      - available
      - purchasable
      - maxQuantity
      - category
      properties:
        productId:
          type: integer
          format: int64
        name:
          type: string
        description:
          type: string
        mainImageUrl:
          type: string
          format: uri-reference
        price:
          type: integer
          format: int64
          minimum: 0
        salesStatus:
          type: string
          enum:
          - ON_SALE
          - SOLD_OUT
        available:
          type: integer
          minimum: 0
        purchasable:
          type: boolean
          description: 공개 판매 상태와 상품 전체 판매 허용에 따른 Core 확인 진입 가능 표시. 재고 사본을 사용한 구매 확정이
            아니다.
        maxQuantity:
          type: integer
          minimum: 0
          description: 수량 입력 형식 상한 999999999. 상품 전체 판매 중지이면 0이며 실제 구매 가능량은 Core에서
            검증한다.
        category:
          $ref: '#/components/schemas/ProductCategory'
    PurchaseCheckEnvelope:
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
          $ref: '#/components/schemas/PurchaseCheck'
        error:
          type: 'null'
    PurchaseCheck:
      type: object
      required:
      - productId
      - quantity
      - orderable
      - reason
      - maxQuantity
      - unitPrice
      properties:
        productId:
          type: integer
          format: int64
        quantity:
          type: integer
        orderable:
          type: boolean
          description: 상품 전체 판매가 허용된 공개 상품이면 true. 사본 수량과 품절만으로 거절하지 않으며 실제 주문 승인은
            Core가 판단한다.
        reason:
          type:
          - string
          - 'null'
          enum:
          - SALE_SUSPENDED
          - SOLD_OUT
          - EXCEEDS_STOCK
          - null
          description: 상품 전체 중지는 SALE_SUSPENDED. SOLD_OUT/EXCEEDS_STOCK은 구 응답 호환 enum이며
            새 사전 확인에서는 사본 재고를 이유로 반환하지 않는다.
        maxQuantity:
          type: integer
          description: 수량 입력 형식 상한 999999999. 상품 전체 판매 중지이면 0이며 실제 구매 가능량은 Core에서
            검증한다.
        unitPrice:
          type: integer
          format: int64
    ProductCouponListEnvelope:
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
            $ref: '#/components/schemas/ProductCoupon'
        error:
          type: 'null'
    ProductCoupon:
      type: object
      required:
      - couponId
      - name
      - fixedDiscount
      - startsAt
      - endsAt
      - expiresAt
      - soldOut
      properties:
        couponId:
          type: string
          maxLength: 36
          description: Commerce 쿠폰 ID. UUID로 한정하지 않는 opaque 문자열
        name:
          type: string
          maxLength: 100
        fixedDiscount:
          type: integer
          format: int64
          minimum: 1
          description: 정액 할인 금액(원)
        startsAt:
          type: string
          format: date-time
          description: 발급 시작
          포함 경계: null
        endsAt:
          type: string
          format: date-time
          description: 발급 종료
          제외 경계: null
        expiresAt:
          type: string
          format: date-time
          description: 발급받은 쿠폰의 사용 만료
        soldOut:
          type: boolean
          description: 발급 수량 소진 여부
  parameters:
    ProductId:
      name: productId
      in: path
      required: true
      schema:
        type: integer
        format: int64
        minimum: 1
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
```

입력 SHA-256: `f1cd9338b8090301d4187c73856173c2ac80304c59b1da5c900904391d39a1b3`
