# Notification API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/notification-service.yaml) · OpenAPI 3.1.0 · 계약 1.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

회원 본인의 인앱 알림함 조회와 읽음 처리.

## 공통 인증과 서버

```yaml
security:
- BearerAuth: []
securitySchemes:
  BearerAuth:
    $ref: ./common.yaml#/components/securitySchemes/BearerAuth
servers:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/notifications

내 알림 목록

USER·SELLER 본인의 알림만 occurredAt 내림차순, 같은 시각은 id 내림차순으로 반환한다. 조회는 읽음 상태를 바꾸지 않는다. cursor가 없거나 공백이면 첫 구간이다. nextCursor는 해석하지 않고 다음 요청에 전달한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| cursor | query | 선택 | string |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 400 | 입력 형식·size·cursor 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.items&#91;&#93;.eventType | string | 필수 | 알림을 만들게 한 이벤트 이름. 화면이 아이콘을 고르는 근거다. 새 알림이 생기면 값이 늘어나므로 모르는 값을 받아도 동작해야 한다. | {&quot;maxLength&quot;: 64} |
| data.items&#91;&#93;.title | string | 필수 |  | {&quot;maxLength&quot;: 100} |
| data.items&#91;&#93;.body | string | 필수 |  | {&quot;maxLength&quot;: 500} |
| data.items&#91;&#93;.linkType | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | 눌렀을 때 보낼 곳의 종류. 보낼 곳이 없는 알림은 linkValue 와 함께 null 이다. | {&quot;enum&quot;: &#91;&quot;BROADCAST&quot;, &quot;PRODUCT&quot;, &quot;PAYMENT_GROUP&quot;, &quot;REFUND&quot;, &quot;COUPON&quot;, null&#93;} |
| data.items&#91;&#93;.linkValue | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | linkType 이 가리키는 대상 식별자. BROADCAST 는 방송 ID, PRODUCT 는 상품 ID, PAYMENT_GROUP 은 PG-UUID 또는 기존 PG-LEGACY-주문ID, REFUND 는 해당 묶음번호/환불요청ID, COUPON 은 쿠폰 ID(쿠폰함 이동). | {&quot;maxLength&quot;: 100} |
| data.items&#91;&#93;.occurredAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.items&#91;&#93;.readAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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

#### 500 · application/json

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
summary: 내 알림 목록
operationId: listNotifications
description: USER·SELLER 본인의 알림만 occurredAt 내림차순, 같은 시각은 id 내림차순으로 반환한다. 조회는 읽음 상태를
  바꾸지 않는다. cursor가 없거나 공백이면 첫 구간이다. nextCursor는 해석하지 않고 다음 요청에 전달한다.
parameters:
- name: cursor
  in: query
  required: false
  schema:
    type: string
- name: size
  in: query
  required: false
  schema:
    type: integer
    minimum: 1
    maximum: 50
    default: 20
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/NotificationListEnvelope'
  '400':
    description: '입력 형식·size·cursor 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/notifications/unread-count

내 안 읽은 알림 개수



**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.unreadCount | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
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

#### 500 · application/json

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
summary: 내 안 읽은 알림 개수
operationId: getUnreadNotificationCount
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/UnreadCountEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## POST /v1/notifications/read-all

내 미읽음 알림 전체 읽음 처리

토큰의 USER·SELLER 본인의 미읽음 알림 전체를 서버 시각으로 한 번에 갱신한다. 조회 여부·페이지·커서와 무관하며 요청 본문이나 알림 ID 목록은 필요하지 않다. 다른 회원의 알림과 기존 읽음 시각·수정 시각은 변경하지 않는다. 빈 목록과 반복·동시 요청도 성공한다. 갱신 이후 도착한 알림은 다음 요청의 대상이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 전체 읽음 처리 완료. 대상이 없거나 반복 호출이어도 성공. 본문 없음. |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

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

#### 500 · application/json

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
summary: 내 미읽음 알림 전체 읽음 처리
operationId: markAllNotificationsRead
description: 토큰의 USER·SELLER 본인의 미읽음 알림 전체를 서버 시각으로 한 번에 갱신한다. 조회 여부·페이지·커서와 무관하며
  요청 본문이나 알림 ID 목록은 필요하지 않다. 다른 회원의 알림과 기존 읽음 시각·수정 시각은 변경하지 않는다. 빈 목록과 반복·동시 요청도
  성공한다. 갱신 이후 도착한 알림은 다음 요청의 대상이다.
responses:
  '204':
    description: 전체 읽음 처리 완료. 대상이 없거나 반복 호출이어도 성공. 본문 없음.
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## POST /v1/notifications/{notificationId}/read

내 알림 읽음 처리

서버 시각으로 최초 읽음만 기록한다. 반복·동시 요청도 최초 성공한 쓰기의 시각을 유지한다. 다른 회원의 알림과 없는 ID는 모두 404. notificationId 숫자 형식 오류는 400이며 존재하지 않는 0·음수 ID는 404이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| notificationId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 읽음 처리 완료. 반복 호출도 최초 읽음 시각을 유지한다. 본문 없음. |
| 404 | 없는 알림과 다른 회원의 알림은 모두 NOT_FOUND |
| 400 | notificationId 숫자 형식 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 404 · application/json

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

#### 500 · application/json

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
summary: 내 알림 읽음 처리
operationId: markNotificationRead
description: 서버 시각으로 최초 읽음만 기록한다. 반복·동시 요청도 최초 성공한 쓰기의 시각을 유지한다. 다른 회원의 알림과 없는 ID는
  모두 404. notificationId 숫자 형식 오류는 400이며 존재하지 않는 0·음수 ID는 404이다.
parameters:
- name: notificationId
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '204':
    description: 읽음 처리 완료. 반복 호출도 최초 읽음 시각을 유지한다. 본문 없음.
  '404':
    description: 없는 알림과 다른 회원의 알림은 모두 NOT_FOUND
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '400':
    description: 'notificationId 숫자 형식 오류: INVALID_REQUEST'
    content: *id001
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## POST /v1/broadcast-alerts

방송 시작 알림 신청

신청자는 토큰의 회원이다. 이미 신청한 방송을 다시 신청해도 201이며 신청은 하나만 남는다. 방송 존재 여부는 알림이 Live 이벤트로 받아 둔 일정 사본으로 확인하므로, 아직 일정을 받지 못한 방송은 404이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| broadcastId | integer | 필수 | 알림을 받을 방송. Live 가 발행한 일정 이벤트로 알림이 받아 둔 방송이어야 한다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 신청 완료. 이미 신청했던 경우도 같다. 본문 없음. |
| 400 | broadcastId 누락·형식 오류 또는 0 이하: INVALID_REQUEST |
| 404 | 일정 사본에 없는 방송: NOT_FOUND |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

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

#### 500 · application/json

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
summary: 방송 시작 알림 신청
operationId: registerBroadcastAlert
description: 신청자는 토큰의 회원이다. 이미 신청한 방송을 다시 신청해도 201이며 신청은 하나만 남는다. 방송 존재 여부는 알림이 Live
  이벤트로 받아 둔 일정 사본으로 확인하므로, 아직 일정을 받지 못한 방송은 404이다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/BroadcastAlertRequest'
responses:
  '201':
    description: 신청 완료. 이미 신청했던 경우도 같다. 본문 없음.
  '400':
    description: 'broadcastId 누락·형식 오류 또는 0 이하: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '404':
    description: '일정 사본에 없는 방송: NOT_FOUND'
    content: *id001
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/broadcast-alerts

내 방송 시작 알림 신청 목록

USER·SELLER 토큰 본인 신청만 대상 ID 내림차순으로 반환한다. cursor는 이전 응답 nextCursor를 그대로 전달한다. 회원 ID 파라미터는 사용하지 않는다. 방송 신청은 발송 후에도 보존되며 현재 방송 진행 상태나 발송 대기 여부를 뜻하지 않는다. 삭제된 방송의 신청도 직접 해제할 때까지 남을 수 있다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| cursor | query | 선택 | string |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 400 | 입력 형식·size·cursor 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;.targetId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.items&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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

#### 500 · application/json

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
summary: 내 방송 시작 알림 신청 목록
operationId: listBroadcastAlerts
description: USER·SELLER 토큰 본인 신청만 대상 ID 내림차순으로 반환한다. cursor는 이전 응답 nextCursor를 그대로
  전달한다. 회원 ID 파라미터는 사용하지 않는다. 방송 신청은 발송 후에도 보존되며 현재 방송 진행 상태나 발송 대기 여부를 뜻하지 않는다. 삭제된
  방송의 신청도 직접 해제할 때까지 남을 수 있다.
parameters:
- name: cursor
  in: query
  required: false
  schema:
    type: string
- name: size
  in: query
  required: false
  schema:
    type: integer
    minimum: 1
    maximum: 50
    default: 20
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/AlertSubscriptionListEnvelope'
  '400':
    description: '입력 형식·size·cursor 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## DELETE /v1/broadcast-alerts/{broadcastId}

방송 시작 알림 해제

신청한 적이 없어도 204이다. 원한 상태가 이미 달성돼 있으므로 오류로 보지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 해제 완료. 신청이 없었던 경우도 같다. 본문 없음. |
| 400 | broadcastId 숫자 형식 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

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

#### 500 · application/json

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
summary: 방송 시작 알림 해제
operationId: unregisterBroadcastAlert
description: 신청한 적이 없어도 204이다. 원한 상태가 이미 달성돼 있으므로 오류로 보지 않는다.
parameters:
- name: broadcastId
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '204':
    description: 해제 완료. 신청이 없었던 경우도 같다. 본문 없음.
  '400':
    description: 'broadcastId 숫자 형식 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/broadcast-alerts/{broadcastId}

내 대상별 알림 신청 상태

토큰 본인의 신청 기록 유무만 반환한다. 다른 회원의 신청은 보이지 않으며 기록이 없거나 대상이 없는 경우 subscribed=false이다. 발송 완료나 대상의 현재 상태는 나타내지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 400 | broadcastId 숫자 형식 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.subscribed | boolean | 필수 |  | — |
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

#### 500 · application/json

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
summary: 내 대상별 알림 신청 상태
operationId: getBroadcastAlertState
description: 토큰 본인의 신청 기록 유무만 반환한다. 다른 회원의 신청은 보이지 않으며 기록이 없거나 대상이 없는 경우 subscribed=false이다.
  발송 완료나 대상의 현재 상태는 나타내지 않는다.
parameters:
- name: broadcastId
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/AlertSubscriptionStateEnvelope'
  '400':
    description: 'broadcastId 숫자 형식 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## POST /v1/restock-alerts

재입고 알림 신청

신청자는 토큰의 회원이다. 이미 신청한 상품을 다시 신청해도 201이며 신청은 하나만 남는다. 판매 상태는 알림이 Commerce 이벤트로 받아 둔 사본으로 확인하므로, 아직 판매정보를 받지 못한 상품은 404이고 판매 중인 상품은 409이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| productId | integer | 필수 | 재입고 알림을 받을 상품. 알림이 판매정보 이벤트로 받아 둔 상품이어야 하고 품절 상태여야 한다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 신청 완료. 이미 신청했던 경우도 같다. 본문 없음. |
| 400 | productId 누락, 형식 오류 또는 0 이하: INVALID_REQUEST |
| 404 | 판매정보 사본에 없는 상품: NOT_FOUND |
| 409 | 판매 중인 상품: CONFLICT. 재입고 알림은 품절을 전제한다 |
| 401 | 토큰 누락, 무효 또는 보안 폐기된 세션 |
| 403 | USER, SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

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

#### 500 · application/json

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
summary: 재입고 알림 신청
operationId: registerRestockAlert
description: 신청자는 토큰의 회원이다. 이미 신청한 상품을 다시 신청해도 201이며 신청은 하나만 남는다. 판매 상태는 알림이 Commerce
  이벤트로 받아 둔 사본으로 확인하므로, 아직 판매정보를 받지 못한 상품은 404이고 판매 중인 상품은 409이다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RestockAlertRequest'
responses:
  '201':
    description: 신청 완료. 이미 신청했던 경우도 같다. 본문 없음.
  '400':
    description: 'productId 누락, 형식 오류 또는 0 이하: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '404':
    description: '판매정보 사본에 없는 상품: NOT_FOUND'
    content: *id001
  '409':
    description: '판매 중인 상품: CONFLICT. 재입고 알림은 품절을 전제한다'
    content: *id001
  '401':
    description: 토큰 누락, 무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER, SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/restock-alerts

내 재입고 알림 신청 목록

USER·SELLER 토큰 본인 신청만 대상 ID 내림차순으로 반환한다. cursor는 이전 응답 nextCursor를 그대로 전달한다. 회원 ID 파라미터는 사용하지 않는다. 재입고 알림을 보낸 상품의 신청은 기존 1회 발송 정책에 따라 삭제되어 목록에서 빠진다. 대상 상품 삭제는 별도 이벤트가 없어 즉시 반영되지 않을 수 있다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| cursor | query | 선택 | string |  |
| size | query | 선택 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 400 | 입력 형식·size·cursor 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.items | array | 필수 |  | — |
| data.items&#91;&#93;.targetId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.items&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
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

#### 500 · application/json

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
summary: 내 재입고 알림 신청 목록
operationId: listRestockAlerts
description: USER·SELLER 토큰 본인 신청만 대상 ID 내림차순으로 반환한다. cursor는 이전 응답 nextCursor를 그대로
  전달한다. 회원 ID 파라미터는 사용하지 않는다. 재입고 알림을 보낸 상품의 신청은 기존 1회 발송 정책에 따라 삭제되어 목록에서 빠진다. 대상
  상품 삭제는 별도 이벤트가 없어 즉시 반영되지 않을 수 있다.
parameters:
- name: cursor
  in: query
  required: false
  schema:
    type: string
- name: size
  in: query
  required: false
  schema:
    type: integer
    minimum: 1
    maximum: 50
    default: 20
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/AlertSubscriptionListEnvelope'
  '400':
    description: '입력 형식·size·cursor 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## DELETE /v1/restock-alerts/{productId}

재입고 알림 해제

신청한 적이 없어도 204이다. 원한 상태가 이미 달성돼 있으므로 오류로 보지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 해제 완료. 신청이 없었던 경우도 같다. 본문 없음. |
| 400 | productId 숫자 형식 오류: INVALID_REQUEST |
| 401 | 토큰 누락, 무효 또는 보안 폐기된 세션 |
| 403 | USER, SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

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

#### 500 · application/json

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
summary: 재입고 알림 해제
operationId: unregisterRestockAlert
description: 신청한 적이 없어도 204이다. 원한 상태가 이미 달성돼 있으므로 오류로 보지 않는다.
parameters:
- name: productId
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '204':
    description: 해제 완료. 신청이 없었던 경우도 같다. 본문 없음.
  '400':
    description: 'productId 숫자 형식 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락, 무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER, SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
```

## GET /v1/restock-alerts/{productId}

내 대상별 알림 신청 상태

토큰 본인의 신청 기록 유무만 반환한다. 다른 회원의 신청은 보이지 않으며 기록이 없거나 대상이 없는 경우 subscribed=false이다. 발송 완료나 대상의 현재 상태는 나타내지 않는다.

**인증:** `[{"BearerAuth": []}]`
**태그:** 없음

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| productId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 조회 성공 |
| 400 | productId 숫자 형식 오류: INVALID_REQUEST |
| 401 | 토큰 누락·무효 또는 보안 폐기된 세션 |
| 403 | USER·SELLER 권한이 없는 호출 |
| 503 | Member 세션 확인 불가: SERVICE_UNAVAILABLE |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.subscribed | boolean | 필수 |  | — |
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

#### 500 · application/json

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
summary: 내 대상별 알림 신청 상태
operationId: getRestockAlertState
description: 토큰 본인의 신청 기록 유무만 반환한다. 다른 회원의 신청은 보이지 않으며 기록이 없거나 대상이 없는 경우 subscribed=false이다.
  발송 완료나 대상의 현재 상태는 나타내지 않는다.
parameters:
- name: productId
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '200':
    description: 조회 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/AlertSubscriptionStateEnvelope'
  '400':
    description: 'productId 숫자 형식 오류: INVALID_REQUEST'
    content: &id001
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '401':
    description: 토큰 누락·무효 또는 보안 폐기된 세션
    content: *id001
  '403':
    description: USER·SELLER 권한이 없는 호출
    content: *id001
  '503':
    description: 'Member 세션 확인 불가: SERVICE_UNAVAILABLE'
    content: *id001
  '500':
    description: 서버 내부 오류
    content: *id001
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8085
- url: http://dev-notification-service:8080
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
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: Member RS256 JWT. sub와 sid는 canonical UUID, iss=shoppinglive-member,
        aud=shoppinglive-api, exp/iat/jti 필수; USER/SELLER. 로컬 서명 검증 후 Member 권위 저장소의
        세션 상태를 무캐시 확인한다. 확인 실패 503, 보안 폐기·탈퇴 401. 쿠키·임의 회원/역할 헤더는 인증 수단이 아니다.
```

### notification-service.yaml

```yaml
components:
  schemas:
    NotificationListEnvelope:
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
          $ref: '#/components/schemas/NotificationPage'
        error:
          type: 'null'
    NotificationPage:
      type: object
      required:
      - items
      - nextCursor
      - hasNext
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/Notification'
        nextCursor:
          type:
          - string
          - 'null'
        hasNext:
          type: boolean
    Notification:
      type: object
      required:
      - id
      - eventType
      - title
      - body
      - linkType
      - linkValue
      - occurredAt
      - readAt
      properties:
        id:
          type: integer
          format: int64
          minimum: 1
        eventType:
          type: string
          maxLength: 64
          description: 알림을 만들게 한 이벤트 이름. 화면이 아이콘을 고르는 근거다. 새 알림이 생기면 값이 늘어나므로 모르는
            값을 받아도 동작해야 한다.
          example: LiveBroadcastStarted
        title:
          type: string
          maxLength: 100
        body:
          type: string
          maxLength: 500
        linkType:
          type:
          - string
          - 'null'
          enum:
          - BROADCAST
          - PRODUCT
          - PAYMENT_GROUP
          - REFUND
          - COUPON
          - null
          description: 눌렀을 때 보낼 곳의 종류. 보낼 곳이 없는 알림은 linkValue 와 함께 null 이다.
        linkValue:
          type:
          - string
          - 'null'
          maxLength: 100
          description: linkType 이 가리키는 대상 식별자. BROADCAST 는 방송 ID, PRODUCT 는 상품 ID,
            PAYMENT_GROUP 은 PG-UUID 또는 기존 PG-LEGACY-주문ID, REFUND 는 해당 묶음번호/환불요청ID,
            COUPON 은 쿠폰 ID(쿠폰함 이동).
          example: '42'
        occurredAt:
          type: string
          format: date-time
        readAt:
          type:
          - string
          - 'null'
          format: date-time
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
          $ref: ./common.yaml#/components/schemas/ApiError
    UnreadCountEnvelope:
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
          - unreadCount
          properties:
            unreadCount:
              type: integer
              format: int64
              minimum: 0
        error:
          type: 'null'
    BroadcastAlertRequest:
      type: object
      required:
      - broadcastId
      properties:
        broadcastId:
          type: integer
          format: int64
          minimum: 1
          description: 알림을 받을 방송. Live 가 발행한 일정 이벤트로 알림이 받아 둔 방송이어야 한다.
    AlertSubscriptionListEnvelope:
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
          $ref: '#/components/schemas/AlertSubscriptionPage'
        error:
          type: 'null'
    AlertSubscriptionPage:
      type: object
      required:
      - items
      - nextCursor
      - hasNext
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/AlertSubscription'
        nextCursor:
          type:
          - string
          - 'null'
        hasNext:
          type: boolean
    AlertSubscription:
      type: object
      required:
      - targetId
      - createdAt
      properties:
        targetId:
          type: integer
          format: int64
          minimum: 1
        createdAt:
          type: string
          format: date-time
    AlertSubscriptionStateEnvelope:
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
          - subscribed
          properties:
            subscribed:
              type: boolean
        error:
          type: 'null'
    RestockAlertRequest:
      type: object
      required:
      - productId
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
          description: 재입고 알림을 받을 상품. 알림이 판매정보 이벤트로 받아 둔 상품이어야 하고 품절 상태여야 한다.
  securitySchemes:
    BearerAuth:
      $ref: ./common.yaml#/components/securitySchemes/BearerAuth
```

입력 SHA-256: `303004242d7a59006dd5d293f3a11c776a301446e32ddfabd385d4bbaf1cd500`
