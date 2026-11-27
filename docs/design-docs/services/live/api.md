# Live Service API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/live-service.yaml) · OpenAPI 3.1.0 · 계약 2.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

방송·프로모션·채팅·좋아요 API. 서비스가 JWT를 직접 검증한다.
JSON 성공·오류 응답은 ApiResponse 봉투이며 DELETE 성공은 204 무본문이다. SSE data는 이벤트별 JSON이다.
공개 방송 조회·입장은 비회원에게 허용한다. 방송·프로모션·상품 연결 관리에는 SELLER 역할이 필요하다.
방송별 판매자 소유권은 구분하지 않는다. 공개 응답에는 채널 ARN·등록 키·송출 자격증명을 포함하지 않는다.
Bearer가 있으면 Member 세션을 확인하며 유효하지 않은 토큰은 401, 세션 확인 장애는 SERVICE_UNAVAILABLE 503이다.
공개 경로의 무토큰 요청은 Member 세션을 확인하지 않는다.

## 공통 인증과 서버

```yaml
security:
- BearerAuth: []
securitySchemes:
  BearerAuth:
    $ref: ./common.yaml#/components/securitySchemes/BearerAuth
servers:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcast-thumbnails

판매자 방송 썸네일 업로드

PNG·JPEG만 허용하며 실제 디코딩·형식·2MB·최대 4096×4096 픽셀을 검증한다. 썸네일 ID는 업로드한 판매자만 방송에 연결할 수 있다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 본문

본문: 필수

**multipart/form-data**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| file | string | 필수 |  | {&quot;format&quot;: &quot;binary&quot;} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 업로드 완료 |
| 400 | 파일 누락·손상·형식·크기 초과 |
| 401 | 로그인 필요 |
| 403 | SELLER 권한 필요 |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| error | null | 필수 |  | — |
| data | object | 필수 |  | — |
| data.thumbnailId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.thumbnailUrl | string | 필수 |  | — |

### 계약 원문

```yaml
tags:
- Seller Broadcast
summary: 판매자 방송 썸네일 업로드
operationId: uploadBroadcastThumbnail
x-required-roles:
- SELLER
description: PNG·JPEG만 허용하며 실제 디코딩·형식·2MB·최대 4096×4096 픽셀을 검증한다. 썸네일 ID는 업로드한 판매자만
  방송에 연결할 수 있다.
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
  '201':
    description: 업로드 완료
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
              type: boolean
            error:
              type: 'null'
            data:
              type: object
              required:
              - thumbnailId
              - thumbnailUrl
              properties:
                thumbnailId:
                  type: string
                  format: uuid
                thumbnailUrl:
                  type: string
  '400':
    description: 파일 누락·손상·형식·크기 초과
  '401':
    description: 로그인 필요
  '403':
    description: SELLER 권한 필요
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcast-thumbnails/{id}

공개 방송 썸네일 조회



**인증:** `[]`
**태그:** Public Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 원본 이미지. nosniff와 하루 공개 캐시를 적용한다. |
| 404 | 존재하지 않는 썸네일 |

#### 200 · image/png

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

#### 200 · image/jpeg

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

### 계약 원문

```yaml
tags:
- Public Broadcast
summary: 공개 방송 썸네일 조회
operationId: getBroadcastThumbnail
security: []
parameters:
- name: id
  in: path
  required: true
  schema:
    type: string
    format: uuid
responses:
  '200':
    description: 원본 이미지. nosniff와 하루 공개 캐시를 적용한다.
    content:
      image/png:
        schema:
          type: string
          format: binary
      image/jpeg:
        schema:
          type: string
          format: binary
  '404':
    description: 존재하지 않는 썸네일
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts

방송 등록

PREPARING 으로 저장한다. 과거 예정 시각도 허용하며 AWS 송출 여부는 조회하지 않는다.
`Idempotency-Key` 는 방송 레코드 수명 동안 unique 하다. 같은 키·동일 입력은 같은 id 를
돌려주고, 같은 키·다른 입력은 409 다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| Idempotency-Key | header | 필수 | string | 1~128자의 등록 요청 식별자. 같은 키·같은 입력은 기존 리소스를 반환하고 다른 입력은 409이다. 방송과 프로모션의 키는 각각 구분한다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| title | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| scheduledAt | string | 필수 | offset 을 포함한 ISO-8601. 과거 시각도 허용한다. UI 는 KST 로 표시한다. | {&quot;format&quot;: &quot;date-time&quot;} |
| channelArn | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 255} |
| playbackUrl | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 2048} |
| hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |

```json
{
  "title": "가을 신상 라이브",
  "scheduledAt": "2026-10-01T20:00:00+09:00",
  "channelArn": "arn:aws:ivs:ap-northeast-2:123456789012:channel/abcDEF",
  "playbackUrl": "https://abcdef.live-video.net/api/video/v1/ap-northeast-2.playlist.m3u8"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 201 | 등록됨(같은 키·동일 입력의 재요청도 같은 방송) |
| 400 | 입력이 올바르지 않다. |
| 409 | 같은 등록 키에 다른 요청 본문 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.channelArn | string | 필수 |  | — |
| data.playbackUrl | string | 필수 |  | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "같은 등록 키에 다른 요청을 사용할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 등록
description: 'PREPARING 으로 저장한다. 과거 예정 시각도 허용하며 AWS 송출 여부는 조회하지 않는다.

  `Idempotency-Key` 는 방송 레코드 수명 동안 unique 하다. 같은 키·동일 입력은 같은 id 를

  돌려주고, 같은 키·다른 입력은 409 다.

  '
operationId: registerBroadcast
tags:
- Seller Broadcast
parameters:
- $ref: '#/components/parameters/IdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/BroadcastInput'
      example:
        title: 가을 신상 라이브
        scheduledAt: '2026-10-01T20:00:00+09:00'
        channelArn: arn:aws:ivs:ap-northeast-2:123456789012:channel/abcDEF
        playbackUrl: https://abcdef.live-video.net/api/video/v1/ap-northeast-2.playlist.m3u8
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '201':
    description: 등록됨(같은 키·동일 입력의 재요청도 같은 방송)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '409':
    description: 같은 등록 키에 다른 요청 본문
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: CONFLICT
            message: 같은 등록 키에 다른 요청을 사용할 수 없습니다.
            requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/seller/broadcasts

관리 방송 목록

상품·IVS 호출 없이 기본정보만 읽는다. 기본 정렬은 createdAt DESC, id DESC 이고
상태 필터는 준비 scheduledAt ASC, 진행 startedAt DESC, 종료 endedAt DESC 다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| status | query | 선택 | string |  |
| page | query | 선택 | integer |  |
| size | query | 선택 | integer | 페이지 크기. 100을 초과하면 100으로 제한한다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 목록 |
| 400 | 입력이 올바르지 않다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.content | array | 필수 |  | — |
| data.content&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.content&#91;&#93;.title | string | 필수 |  | — |
| data.content&#91;&#93;.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.channelArn | string | 필수 |  | — |
| data.content&#91;&#93;.playbackUrl | string | 필수 |  | — |
| data.content&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.content&#91;&#93;.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.content&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.content&#91;&#93;.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.content&#91;&#93;.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.content&#91;&#93;.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| data.number | integer | 필수 |  | — |
| data.size | integer | 필수 |  | — |
| data.totalElements | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.totalPages | integer | 필수 |  | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 관리 방송 목록
description: '상품·IVS 호출 없이 기본정보만 읽는다. 기본 정렬은 createdAt DESC, id DESC 이고

  상태 필터는 준비 scheduledAt ASC, 진행 startedAt DESC, 종료 endedAt DESC 다.

  '
operationId: listBroadcasts
tags:
- Seller Broadcast
parameters:
- name: status
  in: query
  required: false
  schema:
    $ref: '#/components/schemas/BroadcastStatus'
- $ref: '#/components/parameters/Page'
- $ref: '#/components/parameters/Size'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastPageEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/seller/broadcasts/{id}

관리 방송 상세



**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 상세 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.channelArn | string | 필수 |  | — |
| data.playbackUrl | string | 필수 |  | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 관리 방송 상세
operationId: getBroadcast
tags:
- Seller Broadcast
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 상세
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## PATCH /v1/seller/broadcasts/{id}

방송 기본정보 수정

PREPARING 에서만 허용한다. 생략한 필드는 기존 값을 유지하고 본문에 명시적으로 null 을
준 필드는 400 이다. `channelArn` 과 `playbackUrl` 은 함께 변경해야 한다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |
| version | query | 필수 | integer | 마지막으로 조회한 방송 version. 어긋나면 409 다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| title | string | 선택 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| scheduledAt | string | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| channelArn | string | 선택 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 255} |
| playbackUrl | string | 선택 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 2048} |
| hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| thumbnailId | string | 선택 | 썸네일 UUID. 생략 또는 기존 ID 재전송은 유지하고 빈 문자열은 제거한다. 새로 연결·교체하는 ID만 현재 판매자 소유여야 하며 타인 이미지는 403이다. | {&quot;maxLength&quot;: 36} |

```json
{
  "title": "가을 신상 라이브(수정)"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 수정됨 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 409 | PREPARING 이 아니거나 version 이 어긋남 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.channelArn | string | 필수 |  | — |
| data.playbackUrl | string | 필수 |  | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 기본정보 수정
description: 'PREPARING 에서만 허용한다. 생략한 필드는 기존 값을 유지하고 본문에 명시적으로 null 을

  준 필드는 400 이다. `channelArn` 과 `playbackUrl` 은 함께 변경해야 한다.

  '
operationId: editBroadcast
tags:
- Seller Broadcast
parameters:
- $ref: '#/components/parameters/Version'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/BroadcastPatchInput'
      example:
        title: 가을 신상 라이브(수정)
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
          $ref: '#/components/schemas/BroadcastEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: PREPARING 이 아니거나 version 이 어긋남
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{id}/start

방송 시작

IVS LIVE+HEALTHY 와 채널-시청 URL 동일성, 현재 ON_SALE/SOLD_OUT 연결 상품 최소 1개를
확인한 뒤 LIVE 로 전환한다. available 재고는 시작 판단에 쓰지 않는다.
이미 LIVE 면 외부 재확인 없이 최초 startedAt 을 보존해 성공하고 ENDED 재시작은 409 다.
같은 channelArn 으로 이미 진행 중인 방송이 있으면 409 다(DB partial unique index).


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |
| expectedVersion | query | 필수 | integer | 마지막으로 조회한 방송 version. 어긋나면 409 다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | LIVE |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 409 | IVS 미송출·URL 불일치·연결 상품 없음·version 충돌·종료 재시작·채널 중복 |
| 503 | 외부 서비스(Shopping/Commerce/IVS) 장애. 업무 조건 불충족(409)과 구분한다. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.channelArn | string | 필수 |  | — |
| data.playbackUrl | string | 필수 |  | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "IVS 채널이 아직 송출 중이 아닙니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "상품 정보를 조회할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 시작
description: 'IVS LIVE+HEALTHY 와 채널-시청 URL 동일성, 현재 ON_SALE/SOLD_OUT 연결 상품 최소 1개를

  확인한 뒤 LIVE 로 전환한다. available 재고는 시작 판단에 쓰지 않는다.

  이미 LIVE 면 외부 재확인 없이 최초 startedAt 을 보존해 성공하고 ENDED 재시작은 409 다.

  같은 channelArn 으로 이미 진행 중인 방송이 있으면 409 다(DB partial unique index).

  '
operationId: startBroadcast
tags:
- Seller Broadcast
parameters:
- $ref: '#/components/parameters/BroadcastId'
- $ref: '#/components/parameters/ExpectedVersion'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: LIVE
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: IVS 미송출·URL 불일치·연결 상품 없음·version 충돌·종료 재시작·채널 중복
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: CONFLICT
            message: IVS 채널이 아직 송출 중이 아닙니다.
            requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
  '503':
    $ref: '#/components/responses/ServiceUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{id}/end

방송 종료

LIVE 를 ENDED 로 바꾸고 최초 endedAt 을 보존한다. 반복 종료는 성공하고 준비 종료는 409 다.
결제·주문·판매 상태·재고에 영향을 주지 않으며 OBS 는 운영자가 수동으로 중단한다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | ENDED |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 409 | 준비 상태 방송의 종료 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.channelArn | string | 필수 |  | — |
| data.playbackUrl | string | 필수 |  | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;PREPARING&quot;, &quot;LIVE&quot;, &quot;ENDED&quot;&#93;} |
| data.startedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.endedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.updatedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailId | string | 선택 | 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다. | {&quot;maxLength&quot;: 36} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 종료
description: 'LIVE 를 ENDED 로 바꾸고 최초 endedAt 을 보존한다. 반복 종료는 성공하고 준비 종료는 409 다.

  결제·주문·판매 상태·재고에 영향을 주지 않으며 OBS 는 운영자가 수동으로 중단한다.

  '
operationId: endBroadcast
tags:
- Seller Broadcast
parameters:
- $ref: '#/components/parameters/BroadcastId'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: ENDED
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: 준비 상태 방송의 종료
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{id}/products

방송 상품 연결

Shopping 의 상품 존재와 Commerce 판매 상태를 확인한 뒤 ON_SALE/SOLD_OUT 만 연결한다.
PREPARING/LIVE 에서만 허용하고 방송당 최대 100개, 같은 productId 중복은 409 다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| productId | integer | 필수 | Shopping 상품 식별자. Commerce salesId 와 같은 값이라고 가정하지 않는다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| expectedVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

```json
{
  "productId": 1024,
  "expectedVersion": 3
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 201 | 연결됨 |
| 400 | 입력이 올바르지 않다. |
| 404 | 방송 또는 상품·판매정보 미존재 |
| 409 | 종료된 방송·version 충돌·중복 연결·최대 개수 초과·연결 불가 판매 상태 |
| 503 | 외부 서비스(Shopping/Commerce/IVS) 장애. 업무 조건 불충족(409)과 구분한다. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.linkId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.salesId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.position | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data.broadcastVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "상품 정보를 조회할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 상품 연결
description: 'Shopping 의 상품 존재와 Commerce 판매 상태를 확인한 뒤 ON_SALE/SOLD_OUT 만 연결한다.

  PREPARING/LIVE 에서만 허용하고 방송당 최대 100개, 같은 productId 중복은 409 다.

  '
operationId: linkBroadcastProduct
tags:
- Seller Broadcast Product
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/LinkProductInput'
      example:
        productId: 1024
        expectedVersion: 3
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '201':
    description: 연결됨
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastProductLinkEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    description: 방송 또는 상품·판매정보 미존재
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '409':
    description: 종료된 방송·version 충돌·중복 연결·최대 개수 초과·연결 불가 판매 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/ServiceUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/seller/broadcasts/{id}/products

관리 방송 상품 조회

연결 순서로 Shopping 기본정보와 Commerce 최신 판매정보를 결합한다.
정상 조회에서 사라진 연결도 linkId/position 과 `missing: true` 로 식별할 수 있다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 연결 상품 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 503 | 외부 서비스(Shopping/Commerce/IVS) 장애. 업무 조건 불충족(409)과 구분한다. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.linkId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.position | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.salesId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.name | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| data&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | null 은 기본 이미지 표시. 상대 URL 은 서비스 제공 이미지 경로이며 별도 배포 prefix를 유지한다. | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data&#91;&#93;.price | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | KRW 정수 | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.available | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 결제 대기 예약분을 제외한 주문 가능 재고. 관리 조회에서 상품 또는 판매정보 누락 시 null이다. 판매 상태와 별개이며 ON_SALE에서 0이어도 상태를 변경하지 않는다. | {&quot;minimum&quot;: 0} |
| data&#91;&#93;.status | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;enum&quot;: &#91;&quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;READY&quot;, &quot;PRIVATE&quot;, null&#93;} |
| data&#91;&#93;.purchasable | boolean | 필수 | ON_SALE이고 available이 0보다 클 때만 true. 최종 판정은 Commerce 가 다시 한다. | — |
| data&#91;&#93;.missing | boolean | 필수 | 정상 조회에서 사라진 연결. 관리 응답에서만 true 가 될 수 있다. | — |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": [
    {
      "linkId": 75,
      "position": 0,
      "productId": 1024,
      "salesId": 5501,
      "name": "가을 니트",
      "mainImageUrl": "/img/products/1024.png",
      "price": 39000,
      "available": 0,
      "status": "ON_SALE",
      "purchasable": false,
      "missing": false
    },
    {
      "linkId": 76,
      "position": 1,
      "productId": 1025,
      "salesId": 5502,
      "name": null,
      "mainImageUrl": null,
      "price": null,
      "available": null,
      "status": null,
      "purchasable": false,
      "missing": true
    }
  ]
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "상품 정보를 조회할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 관리 방송 상품 조회
description: '연결 순서로 Shopping 기본정보와 Commerce 최신 판매정보를 결합한다.

  정상 조회에서 사라진 연결도 linkId/position 과 `missing: true` 로 식별할 수 있다.

  '
operationId: listSellerBroadcastProducts
tags:
- Seller Broadcast Product
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 연결 상품
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastProductViewEnvelope'
        example:
          success: true
          error: null
          data:
          - linkId: 75
            position: 0
            productId: 1024
            salesId: 5501
            name: 가을 니트
            mainImageUrl: /img/products/1024.png
            price: 39000
            available: 0
            status: ON_SALE
            purchasable: false
            missing: false
          - linkId: 76
            position: 1
            productId: 1025
            salesId: 5502
            name: null
            mainImageUrl: null
            price: null
            available: null
            status: null
            purchasable: false
            missing: true
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/ServiceUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## PUT /v1/seller/broadcasts/{id}/products/order

방송 상품 노출 순서 변경

이 방송의 전체 linkId 순열을 받아 한 번에 순서를 바꾼다. 중복·누락·타 방송 ID 는 400 이고
성공·실패 모두 원자적이라 일부 순서만 남지 않는다. ENDED 는 변경할 수 없다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| linkIds | array | 필수 | 이 방송의 전체 연결 linkId 순열. | {&quot;minItems&quot;: 1, &quot;maxItems&quot;: 100} |
| expectedVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |

```json
{
  "linkIds": [
    77,
    75,
    76
  ],
  "expectedVersion": 8
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 변경된 전체 순서 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 409 | 종료된 방송 또는 version 충돌 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.linkId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.salesId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.position | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data&#91;&#93;.broadcastVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 상품 노출 순서 변경
description: '이 방송의 전체 linkId 순열을 받아 한 번에 순서를 바꾼다. 중복·누락·타 방송 ID 는 400 이고

  성공·실패 모두 원자적이라 일부 순서만 남지 않는다. ENDED 는 변경할 수 없다.

  '
operationId: reorderBroadcastProducts
tags:
- Seller Broadcast Product
parameters:
- $ref: '#/components/parameters/BroadcastId'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ReorderProductsInput'
      example:
        linkIds:
        - 77
        - 75
        - 76
        expectedVersion: 8
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 변경된 전체 순서
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastProductLinkListEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: 종료된 방송 또는 version 충돌
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## DELETE /v1/seller/broadcasts/{id}/products/{linkId}

방송 상품 연결 해제

방송 존재·상태를 먼저 검사한다. 같은 방송에서 이미 사라진 linkId 는 204 이고,
다른 방송 소속으로 확인되면 404 다. LIVE 의 마지막 연결 상품 해제는 409 다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |
| linkId | path | 필수 | integer |  |
| expectedVersion | query | 필수 | integer | 마지막으로 조회한 방송 version. 어긋나면 409 다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 204 | 해제됨. 본문이 없다. |
| 400 | 입력이 올바르지 않다. |
| 404 | 방송 미존재 또는 다른 방송의 linkId |
| 409 | 종료된 방송·version 충돌·LIVE 의 마지막 연결 상품 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "진행 중 방송의 마지막 연결 상품은 해제할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 상품 연결 해제
description: '방송 존재·상태를 먼저 검사한다. 같은 방송에서 이미 사라진 linkId 는 204 이고,

  다른 방송 소속으로 확인되면 404 다. LIVE 의 마지막 연결 상품 해제는 409 다.

  '
operationId: unlinkBroadcastProduct
tags:
- Seller Broadcast Product
parameters:
- $ref: '#/components/parameters/BroadcastId'
- name: linkId
  in: path
  required: true
  schema:
    type: integer
    format: int64
- $ref: '#/components/parameters/ExpectedVersion'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '204':
    description: 해제됨. 본문이 없다.
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    description: 방송 미존재 또는 다른 방송의 linkId
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '409':
    description: 종료된 방송·version 충돌·LIVE 의 마지막 연결 상품
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: CONFLICT
            message: 진행 중 방송의 마지막 연결 상품은 해제할 수 없습니다.
            requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts

공개 방송 목록

로그인 없이 조회한다. LIVE → PREPARING → ENDED 그룹 순서이고 각 상태의 정렬을 쓴다.
외부 호출 0회이므로 상품·영상 장애와 독립이다. 목록에는 videoStatus 를 채우지 않는다.


**인증:** `[]`
**태그:** Public Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| page | query | 선택 | integer |  |
| size | query | 선택 | integer | 페이지 크기. 100을 초과하면 100으로 제한한다. |
| status | query | 선택 | string | 생략하면 기존 전체 순서. 지정하면 해당 상태 전체에서 페이지를 계산한다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 목록 |
| 400 | 입력이 올바르지 않다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.content | array | 필수 |  | — |
| data.content&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.content&#91;&#93;.title | string | 필수 |  | — |
| data.content&#91;&#93;.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;예정&quot;, &quot;진행&quot;, &quot;종료&quot;&#93;} |
| data.content&#91;&#93;.playbackUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | LIVE 에서만 반환한다. | — |
| data.content&#91;&#93;.playbackAllowed | boolean | 필수 | 업무 상태다. 현재 영상 신호를 보장하지 않는다. | — |
| data.content&#91;&#93;.videoStatus | oneOf | 필수 | LIVE 상세에서만 채운다. 목록과 비 LIVE 상세에서는 null 이다. | — |
| data.content&#91;&#93;.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.content&#91;&#93;.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.content&#91;&#93;.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.content&#91;&#93;.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| data.number | integer | 필수 |  | — |
| data.size | integer | 필수 |  | — |
| data.totalElements | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.totalPages | integer | 필수 |  | — |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
security: []
summary: 공개 방송 목록
description: '로그인 없이 조회한다. LIVE → PREPARING → ENDED 그룹 순서이고 각 상태의 정렬을 쓴다.

  외부 호출 0회이므로 상품·영상 장애와 독립이다. 목록에는 videoStatus 를 채우지 않는다.

  '
operationId: listPublicBroadcasts
tags:
- Public Broadcast
parameters:
- $ref: '#/components/parameters/Page'
- $ref: '#/components/parameters/Size'
- name: status
  in: query
  description: 생략하면 기존 전체 순서. 지정하면 해당 상태 전체에서 페이지를 계산한다.
  schema:
    type: string
    enum:
    - PREPARING
    - LIVE
    - ENDED
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PublicBroadcastPageEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}

공개 방송 상세·시청 연결

`playbackUrl` 은 LIVE 에서만 반환한다. `playbackAllowed` 는 업무 상태이고
`videoStatus` 는 지금 이 순간의 영상 신호다(LIVE 에서만 채운다). 둘은 분리되어 있어
OBS 가 잠시 끊겨도 업무 상태 LIVE 는 유지된다. IVS 조회가 실패해도 이 응답은 200 이며
`videoStatus: UNAVAILABLE` 로 표시된다. 종료된 방송은 재생 진입을 제공하지 않는다.
시청은 IVS → FE 직접 재생이며 Live API 는 영상 프록시가 아니다.


**인증:** `[]`
**태그:** Public Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 상세 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 공개 응답. 채널 ARN·등록 키·fingerprint 를 포함하지 않는다. | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.title | string | 필수 |  | — |
| data.scheduledAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;예정&quot;, &quot;진행&quot;, &quot;종료&quot;&#93;} |
| data.playbackUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | LIVE 에서만 반환한다. | — |
| data.playbackAllowed | boolean | 필수 | 업무 상태다. 현재 영상 신호를 보장하지 않는다. | — |
| data.videoStatus | oneOf | 필수 | LIVE 상세에서만 채운다. 목록과 비 LIVE 상세에서는 null 이다. | — |
| data.createdAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.hostName | string | 선택 | 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 80} |
| data.description | string | 선택 | 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다. | {&quot;maxLength&quot;: 2000} |
| data.thumbnailUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 | 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다. | — |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": {
    "id": 12,
    "title": "가을 신상 라이브",
    "scheduledAt": "2026-10-01T11:00:00Z",
    "status": "진행",
    "playbackUrl": "https://abcdef.live-video.net/api/video/v1/ap-northeast-2.playlist.m3u8",
    "playbackAllowed": true,
    "videoStatus": "READY",
    "createdAt": "2026-09-22T05:00:00Z"
  }
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
security: []
summary: 공개 방송 상세·시청 연결
description: '`playbackUrl` 은 LIVE 에서만 반환한다. `playbackAllowed` 는 업무 상태이고

  `videoStatus` 는 지금 이 순간의 영상 신호다(LIVE 에서만 채운다). 둘은 분리되어 있어

  OBS 가 잠시 끊겨도 업무 상태 LIVE 는 유지된다. IVS 조회가 실패해도 이 응답은 200 이며

  `videoStatus: UNAVAILABLE` 로 표시된다. 종료된 방송은 재생 진입을 제공하지 않는다.

  시청은 IVS → FE 직접 재생이며 Live API 는 영상 프록시가 아니다.

  '
operationId: getPublicBroadcast
tags:
- Public Broadcast
parameters:
- $ref: '#/components/parameters/BroadcastId'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 상세
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PublicBroadcastEnvelope'
        example:
          success: true
          error: null
          data:
            id: 12
            title: 가을 신상 라이브
            scheduledAt: '2026-10-01T11:00:00Z'
            status: 진행
            playbackUrl: https://abcdef.live-video.net/api/video/v1/ap-northeast-2.playlist.m3u8
            playbackAllowed: true
            videoStatus: READY
            createdAt: '2026-09-22T05:00:00Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/products

공개 방송 상품 조회·구매 진입

ON_SALE/SOLD_OUT 만 노출하고 READY/PRIVATE·정상 조회에서 삭제된 상품은 제외한다.
품절은 구매 불가다. FE 는 productId/salesId 를 그대로 일반 주문 흐름에 넘기며
Live 전용 주문·결제 API 는 없다. 최종 가격·재고 판정은 Commerce 가 한다.
필수 외부 조회가 실패하면 빈 목록으로 숨기지 않고 503 을 반환한다.


**인증:** `[]`
**태그:** Public Broadcast Product

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 200 | 공개 상품 |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 503 | 외부 서비스(Shopping/Commerce/IVS) 장애. 업무 조건 불충족(409)과 구분한다. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.linkId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.position | integer | 필수 |  | {&quot;minimum&quot;: 0} |
| data&#91;&#93;.productId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.salesId | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.name | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| data&#91;&#93;.mainImageUrl | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 | null 은 기본 이미지 표시. 상대 URL 은 서비스 제공 이미지 경로이며 별도 배포 prefix를 유지한다. | {&quot;format&quot;: &quot;uri-reference&quot;} |
| data&#91;&#93;.price | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | KRW 정수 | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.available | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 필수 | 결제 대기 예약분을 제외한 주문 가능 재고. 관리 조회에서 상품 또는 판매정보 누락 시 null이다. 판매 상태와 별개이며 ON_SALE에서 0이어도 상태를 변경하지 않는다. | {&quot;minimum&quot;: 0} |
| data&#91;&#93;.status | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | {&quot;enum&quot;: &#91;&quot;ON_SALE&quot;, &quot;SOLD_OUT&quot;, &quot;READY&quot;, &quot;PRIVATE&quot;, null&#93;} |
| data&#91;&#93;.purchasable | boolean | 필수 | ON_SALE이고 available이 0보다 클 때만 true. 최종 판정은 Commerce 가 다시 한다. | — |
| data&#91;&#93;.missing | boolean | 필수 | 정상 조회에서 사라진 연결. 관리 응답에서만 true 가 될 수 있다. | — |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": [
    {
      "linkId": 75,
      "position": 0,
      "productId": 1024,
      "salesId": 5501,
      "name": "가을 니트",
      "mainImageUrl": "/img/products/1024.png",
      "price": 39000,
      "available": 5,
      "status": "ON_SALE",
      "purchasable": true,
      "missing": false
    },
    {
      "linkId": 76,
      "position": 1,
      "productId": 1025,
      "salesId": 5502,
      "name": "가을 코트",
      "mainImageUrl": null,
      "price": 129000,
      "available": 0,
      "status": "SOLD_OUT",
      "purchasable": false,
      "missing": false
    }
  ]
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "상품 정보를 조회할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
security: []
summary: 공개 방송 상품 조회·구매 진입
description: 'ON_SALE/SOLD_OUT 만 노출하고 READY/PRIVATE·정상 조회에서 삭제된 상품은 제외한다.

  품절은 구매 불가다. FE 는 productId/salesId 를 그대로 일반 주문 흐름에 넘기며

  Live 전용 주문·결제 API 는 없다. 최종 가격·재고 판정은 Commerce 가 한다.

  필수 외부 조회가 실패하면 빈 목록으로 숨기지 않고 503 을 반환한다.

  '
operationId: listPublicBroadcastProducts
tags:
- Public Broadcast Product
parameters:
- $ref: '#/components/parameters/BroadcastId'
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '200':
    description: 공개 상품
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/BroadcastProductViewEnvelope'
        example:
          success: true
          error: null
          data:
          - linkId: 75
            position: 0
            productId: 1024
            salesId: 5501
            name: 가을 니트
            mainImageUrl: /img/products/1024.png
            price: 39000
            available: 5
            status: ON_SALE
            purchasable: true
            missing: false
          - linkId: 76
            position: 1
            productId: 1025
            salesId: 5502
            name: 가을 코트
            mainImageUrl: null
            price: 129000
            available: 0
            status: SOLD_OUT
            purchasable: false
            missing: false
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/ServiceUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/chats

최근 채팅 조회

로그인 없이 조회한다. 최신 50건을 골라 오래된 순서로 반환한다. 작성 시각이 같으면
messageId 순서다. PREPARING 방송은 빈 배열이고 ENDED 방송도 조회할 수 있다.
페이지 이동은 제공하지 않는다. 재접속 복구 범위는 이 50건이다.


**인증:** `[]`
**태그:** Broadcast Chat

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 최근 채팅(오래된 순) |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 404 | 대상이 없다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | {&quot;maxItems&quot;: 50} |
| data&#91;&#93;.messageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.displayName | string | 필수 | 작성 당시의 표시 이름. 이후 이름을 바꿔도 변하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 80} |
| data&#91;&#93;.content | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 200} |
| data&#91;&#93;.createdAt | string | 필수 | 서버 작성 시각 | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": [
    {
      "messageId": 9001,
      "broadcastId": 12,
      "displayName": "회원",
      "content": "안녕하세요",
      "createdAt": "2026-10-03T11:00:01.123456Z"
    },
    {
      "messageId": 9002,
      "broadcastId": 12,
      "displayName": "다른회원",
      "content": "가격이 얼마예요?",
      "createdAt": "2026-10-03T11:00:02.500000Z"
    }
  ]
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
security: []
summary: 최근 채팅 조회
description: '로그인 없이 조회한다. 최신 50건을 골라 오래된 순서로 반환한다. 작성 시각이 같으면

  messageId 순서다. PREPARING 방송은 빈 배열이고 ENDED 방송도 조회할 수 있다.

  페이지 이동은 제공하지 않는다. 재접속 복구 범위는 이 50건이다.

  '
operationId: listBroadcastChats
tags:
- Broadcast Chat
responses:
  '200':
    description: 최근 채팅(오래된 순)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ChatMessageListEnvelope'
        example:
          success: true
          error: null
          data:
          - messageId: 9001
            broadcastId: 12
            displayName: 회원
            content: 안녕하세요
            createdAt: '2026-10-03T11:00:01.123456Z'
          - messageId: 9002
            broadcastId: 12
            displayName: 다른회원
            content: 가격이 얼마예요?
            createdAt: '2026-10-03T11:00:02.500000Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/broadcasts/{id}/chats

채팅 작성

LIVE 방송에만 쓸 수 있다. 작성자는 인증 정보에서 얻으며 요청 본문의 회원 ID·이름은 받지 않는다.
표시 이름은 작성 시점의 Member 값을 저장한다. `content` 는 앞뒤 공백을 제거한 뒤
Unicode code point 기준 1~200자다. 줄바꿈을 허용하며 HTML 로 해석하지 않는다.
멱등 키가 없으므로 재시도는 새 채팅을 만든다. 방송 종료와 동시에 도착한 요청은 저장될 수 있다.
저장 후 SSE `chat.created` 로 전달을 시도한다. 전달에 실패해도 응답은 201 이고 채팅은 최근 내역에서 조회된다.
방송별로 초당 5건(설정값)을 넘는 일반 채팅은 저장만 하고 실시간 전달을 생략한다. SELLER 역할의 채팅은 생략하지 않는다. 작성자는 이 응답으로 자기 채팅을 표시한다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Broadcast Chat

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| content | string | 필수 | 앞뒤 공백 제거 후 code point 기준 1~200자. 공백만 있으면 400 이다. | {&quot;minLength&quot;: 1} |

```json
{
  "content": "안녕하세요"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 저장된 채팅 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 404 | 대상이 없다. |
| 409 | 방송이 LIVE 가 아니다(PREPARING 또는 ENDED). |
| 503 | 인증 상태 또는 작성자 표시 이름을 Member 에서 확인할 수 없음. 채팅은 저장되지 않는다. |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 | 공개 채팅. 작성자 회원 ID 는 포함하지 않는다. | — |
| data.messageId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.displayName | string | 필수 | 작성 당시의 표시 이름. 이후 이름을 바꿔도 변하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 80} |
| data.content | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 200} |
| data.createdAt | string | 필수 | 서버 작성 시각 | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": {
    "messageId": 9001,
    "broadcastId": 12,
    "displayName": "회원",
    "content": "안녕하세요",
    "createdAt": "2026-10-03T11:00:01.123456Z"
  }
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "진행 중인 방송이 아닙니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "작성자 정보를 확인할 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- USER
- SELLER
summary: 채팅 작성
description: 'LIVE 방송에만 쓸 수 있다. 작성자는 인증 정보에서 얻으며 요청 본문의 회원 ID·이름은 받지 않는다.

  표시 이름은 작성 시점의 Member 값을 저장한다. `content` 는 앞뒤 공백을 제거한 뒤

  Unicode code point 기준 1~200자다. 줄바꿈을 허용하며 HTML 로 해석하지 않는다.

  멱등 키가 없으므로 재시도는 새 채팅을 만든다. 방송 종료와 동시에 도착한 요청은 저장될 수 있다.

  저장 후 SSE `chat.created` 로 전달을 시도한다. 전달에 실패해도 응답은 201 이고 채팅은 최근 내역에서 조회된다.

  방송별로 초당 5건(설정값)을 넘는 일반 채팅은 저장만 하고 실시간 전달을 생략한다. SELLER 역할의 채팅은 생략하지 않는다. 작성자는 이
  응답으로 자기 채팅을 표시한다.

  '
operationId: createBroadcastChat
tags:
- Broadcast Chat
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ChatInput'
      example:
        content: 안녕하세요
responses:
  '201':
    description: 저장된 채팅
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ChatMessageEnvelope'
        example:
          success: true
          error: null
          data:
            messageId: 9001
            broadcastId: 12
            displayName: 회원
            content: 안녕하세요
            createdAt: '2026-10-03T11:00:01.123456Z'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/BroadcastNotLive'
  '503':
    description: 인증 상태 또는 작성자 표시 이름을 Member 에서 확인할 수 없음. 채팅은 저장되지 않는다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: SERVICE_UNAVAILABLE
            message: 작성자 정보를 확인할 수 없습니다.
            requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/viewers

방송별 현재 시청 브라우저 수 조회

최근 60초 안에 heartbeat를 보낸 브라우저 UUID의 수다. 같은 브라우저 복수 탭은 하나로 집계한다.
비로그인도 포함하고 서로 다른 기기는 별도로 센다. 고유한 실제 사람 수나 영상 재생 수를 보장하지 않는다.
Redis 공유 집계이며 만료 정리 후 반환한다. 준비·종료 방송은 0, 미존재 방송은 404다.
조회만으로 시청자로 등록되지 않는다. 집계 장애를 0으로 숨기지 않고 503으로 응답한다.


**인증:** `[]`
**태그:** Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 시청 규모 추정치 |
| 400 | 잘못된 방송 ID |
| 404 | 방송 없음 |
| 503 | 집계 일시 불가 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.total | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.measuredAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.expiresAfterSeconds | integer | 필수 |  | — |
| error | null | 필수 |  | — |

### 계약 원문

```yaml
tags:
- Broadcast
summary: 방송별 현재 시청 브라우저 수 조회
operationId: getBroadcastViewers
description: '최근 60초 안에 heartbeat를 보낸 브라우저 UUID의 수다. 같은 브라우저 복수 탭은 하나로 집계한다.

  비로그인도 포함하고 서로 다른 기기는 별도로 센다. 고유한 실제 사람 수나 영상 재생 수를 보장하지 않는다.

  Redis 공유 집계이며 만료 정리 후 반환한다. 준비·종료 방송은 0, 미존재 방송은 404다.

  조회만으로 시청자로 등록되지 않는다. 집계 장애를 0으로 숨기지 않고 503으로 응답한다.

  '
security: []
parameters:
- name: id
  in: path
  required: true
  schema:
    type: integer
    format: int64
responses:
  '200':
    description: 시청 규모 추정치
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ViewerTotalEnvelope'
  '400':
    description: 잘못된 방송 ID
  '404':
    description: 방송 없음
  '503':
    description: 집계 일시 불가
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/broadcasts/{id}/viewers/heartbeat

방송 시청 브라우저 상태 갱신

공개 시청 화면에서 15초마다 브라우저에 보존한 UUID를 보낸다. 같은 UUID는 덮어쓰며 개인정보는 받지 않는다.
마지막 heartbeat 이후 60초가 지나면 집계에서 제외한다. 여러 서버가 같은 Redis 집계를 공유한다.
방송이 진행 중일 때만 갱신할 수 있으며 집계 장애는 503이다. 종료 요청 없이 TTL로 이탈을 반영한다.


**인증:** `[]`
**태그:** Broadcast

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| viewerId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 갱신 후 시청 규모 |
| 400 | 잘못된 ID 또는 UUID |
| 404 | 방송 없음 |
| 409 | 진행 중이 아닌 방송 |
| 503 | 집계 일시 불가 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | schema | 필수 |  | — |
| data | object | 필수 |  | — |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.total | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.measuredAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.expiresAfterSeconds | integer | 필수 |  | — |
| error | null | 필수 |  | — |

### 계약 원문

```yaml
tags:
- Broadcast
summary: 방송 시청 브라우저 상태 갱신
operationId: heartbeatBroadcastViewer
description: '공개 시청 화면에서 15초마다 브라우저에 보존한 UUID를 보낸다. 같은 UUID는 덮어쓰며 개인정보는 받지 않는다.

  마지막 heartbeat 이후 60초가 지나면 집계에서 제외한다. 여러 서버가 같은 Redis 집계를 공유한다.

  방송이 진행 중일 때만 갱신할 수 있으며 집계 장애는 503이다. 종료 요청 없이 TTL로 이탈을 반영한다.

  '
security: []
parameters:
- name: id
  in: path
  required: true
  schema:
    type: integer
    format: int64
requestBody:
  required: true
  content:
    application/json:
      schema:
        type: object
        required:
        - viewerId
        properties:
          viewerId:
            type: string
            format: uuid
responses:
  '200':
    description: 갱신 후 시청 규모
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ViewerTotalEnvelope'
  '400':
    description: 잘못된 ID 또는 UUID
  '404':
    description: 방송 없음
  '409':
    description: 진행 중이 아닌 방송
  '503':
    description: 집계 일시 불가
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/likes

좋아요 합계 조회

로그인 없이 PostgreSQL에 커밋된 회원별 좋아요 합계와 version을 조회한다.
좋아요가 없는 방송은 total=0, version=0이다. 종료된 방송도 조회한다.
취소로 total은 감소할 수 있으므로 클라이언트는 더 큰 version의 합계를 반영한다.


**인증:** `[]`
**태그:** Broadcast Like

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 커밋된 합계 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 404 | 대상이 없다. |
| 503 | 좋아요 PostgreSQL 저장소 또는 Member 인증 상태 저장소가 일시적으로 불가함. 같은 요청 키로 재시도한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.total | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": {
    "broadcastId": 12,
    "total": 1,
    "version": 3
  }
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "좋아요 저장소를 사용할 수 없습니다.",
    "requestId": "request-001"
  }
}
```

### 계약 원문

```yaml
security: []
summary: 좋아요 합계 조회
description: '로그인 없이 PostgreSQL에 커밋된 회원별 좋아요 합계와 version을 조회한다.

  좋아요가 없는 방송은 total=0, version=0이다. 종료된 방송도 조회한다.

  취소로 total은 감소할 수 있으므로 클라이언트는 더 큰 version의 합계를 반영한다.

  '
operationId: getBroadcastLikes
tags:
- Broadcast Like
responses:
  '200':
    description: 커밋된 합계
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/LikeTotalEnvelope'
        example:
          success: true
          error: null
          data:
            broadcastId: 12
            total: 1
            version: 3
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/LikeUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/likes/mine

내 좋아요 상태 조회

인증 회원의 liked, stateVersion과 방송 total, version을 함께 조회한다.
참여 전 상태는 liked=false, stateVersion=0이다. 종료된 방송도 조회한다.
stateVersion과 방송 version은 별도로 비교한다. 다른 회원의 변경은 내 stateVersion을 바꾸지 않는다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Broadcast Like

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 내 상태와 합계 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 404 | 대상이 없다. |
| 503 | 좋아요 PostgreSQL 저장소 또는 Member 인증 상태 저장소가 일시적으로 불가함. 같은 요청 키로 재시도한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.liked | boolean | 필수 |  | — |
| data.stateVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.total | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": {
    "broadcastId": 12,
    "liked": true,
    "stateVersion": 1,
    "total": 1,
    "version": 3
  }
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "좋아요 저장소를 사용할 수 없습니다.",
    "requestId": "request-001"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- USER
- SELLER
summary: 내 좋아요 상태 조회
description: '인증 회원의 liked, stateVersion과 방송 total, version을 함께 조회한다.

  참여 전 상태는 liked=false, stateVersion=0이다. 종료된 방송도 조회한다.

  stateVersion과 방송 version은 별도로 비교한다. 다른 회원의 변경은 내 stateVersion을 바꾸지 않는다.

  '
operationId: getMyBroadcastLike
tags:
- Broadcast Like
responses:
  '200':
    description: 내 상태와 합계
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberLikeEnvelope'
        example:
          success: true
          error: null
          data:
            broadcastId: 12
            liked: true
            stateVersion: 1
            total: 1
            version: 3
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/LikeUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## PUT /v1/broadcasts/{id}/likes/mine

내 좋아요 설정 또는 취소

LIVE 방송에 회원당 하나의 좋아요를 설정하거나 취소한다. 현재 상태와 같으면 합계와 version은 바뀌지 않는다.
Idempotency-Key는 UUID이고 방송·회원 범위다. 새 조작은 새 키, 동일 요청의 재시도는 같은 키를 쓴다.
같은 키와 같은 liked는 최초 응답을 재생한다. 다른 liked는 409이다.
요청 결과는 방송 수명 동안 만료시키지 않는다. 종료 이후에도 기존 요청은 재생하고 신규 요청은 409이다.
응답 유실·503은 같은 키로 재시도한다. 재생 응답이 오래되었으면 stateVersion과 version을 각각 비교한다.
이전 POST 누적 API는 지원하지 않는다. local/dev 전환 시 기존 익명 누적값은 새 회원 합계에 포함하지 않는다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Broadcast Like

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |
| Idempotency-Key | header | 필수 | string |  |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| liked | boolean | 필수 |  | — |

```json
{
  "liked": true
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 설정 결과 또는 최초 결과 재생 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 404 | 대상이 없다. |
| 409 | LIVE가 아닌 방송의 신규 요청 또는 같은 키의 다른 liked |
| 503 | 좋아요 PostgreSQL 저장소 또는 Member 인증 상태 저장소가 일시적으로 불가함. 같은 요청 키로 재시도한다. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.liked | boolean | 필수 |  | — |
| data.stateVersion | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.total | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 0} |
| error | null | 필수 |  | — |

```json
{
  "success": true,
  "error": null,
  "data": {
    "broadcastId": 12,
    "liked": false,
    "stateVersion": 2,
    "total": 0,
    "version": 4
  }
}
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "진행 중인 방송이 아니거나 같은 요청 키의 입력이 다릅니다.",
    "requestId": "request-001"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "좋아요 저장소를 사용할 수 없습니다.",
    "requestId": "request-001"
  }
}
```

### 계약 원문

```yaml
x-required-roles:
- USER
- SELLER
summary: 내 좋아요 설정 또는 취소
description: 'LIVE 방송에 회원당 하나의 좋아요를 설정하거나 취소한다. 현재 상태와 같으면 합계와 version은 바뀌지 않는다.

  Idempotency-Key는 UUID이고 방송·회원 범위다. 새 조작은 새 키, 동일 요청의 재시도는 같은 키를 쓴다.

  같은 키와 같은 liked는 최초 응답을 재생한다. 다른 liked는 409이다.

  요청 결과는 방송 수명 동안 만료시키지 않는다. 종료 이후에도 기존 요청은 재생하고 신규 요청은 409이다.

  응답 유실·503은 같은 키로 재시도한다. 재생 응답이 오래되었으면 stateVersion과 version을 각각 비교한다.

  이전 POST 누적 API는 지원하지 않는다. local/dev 전환 시 기존 익명 누적값은 새 회원 합계에 포함하지 않는다.

  '
operationId: setMyBroadcastLike
tags:
- Broadcast Like
parameters:
- name: Idempotency-Key
  in: header
  required: true
  schema:
    type: string
    format: uuid
requestBody:
  required: true
  content:
    application/json:
      schema:
        type: object
        required:
        - liked
        properties:
          liked:
            type: boolean
      example:
        liked: true
responses:
  '200':
    description: 설정 결과 또는 최초 결과 재생
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberLikeEnvelope'
        example:
          success: true
          error: null
          data:
            broadcastId: 12
            liked: false
            stateVersion: 2
            total: 0
            version: 4
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: LIVE가 아닌 방송의 신규 요청 또는 같은 키의 다른 liked
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
        example:
          success: false
          data: null
          error:
            code: CONFLICT
            message: 진행 중인 방송이 아니거나 같은 요청 키의 입력이 다릅니다.
            requestId: request-001
  '503':
    $ref: '#/components/responses/LikeUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/BroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{id}/events

방송 실시간 이벤트 SSE

로그인 없이 연결한다. LIVE 방송에만 연결할 수 있고 그 외 상태는 409 다.
연결 전 오류는 `Accept` 헤더와 무관하게 JSON 오류 봉투이고, 연결 후에는 오류 본문 없이 연결이 닫힌다.

이벤트(`event:` 이름과 `data:` JSON. data 는 `ApiResponse` 봉투가 아니다):

| event | data | 시점 |
|---|---|---|
| `stream.ready` | `StreamReadyEvent` | 연결 직후 한 번 |
| `chat.created` | `ChatMessage` (`id:` 는 messageId) | 채팅 저장 후. 방송별 초당 예산을 넘은 일반 채팅은 생략 |
| `likes.updated` | `LikeTotal` | 커밋 version이 바뀌면 약 1초 간격 |
| `promotion.opened` | `PromotionStreamEvent` | 프로모션이 OPEN으로 전이한 뒤 |
| `promotion.closed` | `PromotionStreamEvent` | 열렸던 프로모션의 수동 종료·시간 만료 뒤 |
| `broadcast.ended` | `BroadcastEndedEvent` | 방송 종료 시. 이후 서버가 연결을 닫는다 |

클라이언트 규칙:
- `stream.ready`를 받은 뒤 최근 채팅·좋아요 합계·열린 프로모션을 GET으로 조회한다. 프로모션 조회 경로는 `/v1/broadcasts/{broadcastId}/promotions`이며 채팅은 messageId로 중복을 제거한다.
- `broadcast.ended`를 받으면 열린 프로모션 표시를 종료하고 재연결하지 않는다. 마지막 좋아요 합계를 GET으로 조회한다. 받지 못하고 끊겼다면 재연결이 409 일 때 방송 상태와 합계를 조회한다.
- 전달은 보장하지 않는다. 재연결 요청의 `Last-Event-ID`(마지막으로 받은 messageId)가 최근 2분 안의 채팅이면 `stream.ready` 뒤에 그 채팅 시각 5초 전부터의 채팅을 `chat.created`로 다시 보낸다(최대 256건). 겹치는 채팅은 messageId로 거른다. 채팅 외 이벤트는 다시 보내지 않는다. 재연결 후 채팅은 최근 50건, 좋아요와 프로모션은 현재 상태 조회로도 복구한다.
- 약 15초마다 comment 줄(`:heartbeat`)이 온다. 수신이 느려 대기량을 넘으면 서버가 연결을 닫는다.
- 서버는 연결을 최대 30분 유지한다. 닫히면 다시 연결한다. 재연결이 409 이면 방송 상태를 조회한다.


**인증:** `[]`
**태그:** Broadcast Event Stream

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | integer |  |
| Last-Event-ID | header | 선택 | string | 브라우저 EventSource 가 재연결 때 마지막으로 받은 이벤트 id(messageId)를 담아 보낸다. 숫자가 아니거나 최근 2분 밖이면 무시한다. |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 이벤트 스트림 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 404 | 대상이 없다. |
| 409 | 방송이 LIVE 가 아니다(PREPARING 또는 ENDED). |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · text/event-stream

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|

```json
"event: stream.ready\ndata: {\"broadcastId\":12}\n\nid: 9001\nevent: chat.created\ndata: {\"messageId\":9001,\"broadcastId\":12,\"displayName\":\"회원\",\"content\":\"안녕하세요\",\"createdAt\":\"2026-10-03T11:00:01.123456Z\"}\n\nevent: likes.updated\ndata: {\"broadcastId\":12,\"total\":1,\"version\":3}\n\n: heartbeat\n\nevent: broadcast.ended\ndata: {\"broadcastId\":12,\"endedAt\":\"2026-10-03T12:00:00.000000Z\"}\n"
```

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CONFLICT",
    "message": "진행 중인 방송이 아닙니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
security: []
summary: 방송 실시간 이벤트 SSE
description: '로그인 없이 연결한다. LIVE 방송에만 연결할 수 있고 그 외 상태는 409 다.

  연결 전 오류는 `Accept` 헤더와 무관하게 JSON 오류 봉투이고, 연결 후에는 오류 본문 없이 연결이 닫힌다.


  이벤트(`event:` 이름과 `data:` JSON. data 는 `ApiResponse` 봉투가 아니다):


  | event | data | 시점 |

  |---|---|---|

  | `stream.ready` | `StreamReadyEvent` | 연결 직후 한 번 |

  | `chat.created` | `ChatMessage` (`id:` 는 messageId) | 채팅 저장 후. 방송별 초당 예산을 넘은 일반
  채팅은 생략 |

  | `likes.updated` | `LikeTotal` | 커밋 version이 바뀌면 약 1초 간격 |

  | `promotion.opened` | `PromotionStreamEvent` | 프로모션이 OPEN으로 전이한 뒤 |

  | `promotion.closed` | `PromotionStreamEvent` | 열렸던 프로모션의 수동 종료·시간 만료 뒤 |

  | `broadcast.ended` | `BroadcastEndedEvent` | 방송 종료 시. 이후 서버가 연결을 닫는다 |


  클라이언트 규칙:

  - `stream.ready`를 받은 뒤 최근 채팅·좋아요 합계·열린 프로모션을 GET으로 조회한다. 프로모션 조회 경로는 `/v1/broadcasts/{broadcastId}/promotions`이며
  채팅은 messageId로 중복을 제거한다.

  - `broadcast.ended`를 받으면 열린 프로모션 표시를 종료하고 재연결하지 않는다. 마지막 좋아요 합계를 GET으로 조회한다. 받지
  못하고 끊겼다면 재연결이 409 일 때 방송 상태와 합계를 조회한다.

  - 전달은 보장하지 않는다. 재연결 요청의 `Last-Event-ID`(마지막으로 받은 messageId)가 최근 2분 안의 채팅이면 `stream.ready`
  뒤에 그 채팅 시각 5초 전부터의 채팅을 `chat.created`로 다시 보낸다(최대 256건). 겹치는 채팅은 messageId로 거른다.
  채팅 외 이벤트는 다시 보내지 않는다. 재연결 후 채팅은 최근 50건, 좋아요와 프로모션은 현재 상태 조회로도 복구한다.

  - 약 15초마다 comment 줄(`:heartbeat`)이 온다. 수신이 느려 대기량을 넘으면 서버가 연결을 닫는다.

  - 서버는 연결을 최대 30분 유지한다. 닫히면 다시 연결한다. 재연결이 409 이면 방송 상태를 조회한다.

  '
operationId: streamBroadcastEvents
tags:
- Broadcast Event Stream
parameters:
- $ref: '#/components/parameters/BroadcastId'
- name: Last-Event-ID
  in: header
  required: false
  description: 브라우저 EventSource 가 재연결 때 마지막으로 받은 이벤트 id(messageId)를 담아 보낸다. 숫자가 아니거나
    최근 2분 밖이면 무시한다.
  schema:
    type: string
  example: '9001'
responses:
  '200':
    description: 이벤트 스트림
    content:
      text/event-stream:
        schema:
          type: string
        example: 'event: stream.ready

          data: {"broadcastId":12}


          id: 9001

          event: chat.created

          data: {"messageId":9001,"broadcastId":12,"displayName":"회원","content":"안녕하세요","createdAt":"2026-10-03T11:00:01.123456Z"}


          event: likes.updated

          data: {"broadcastId":12,"total":1,"version":3}


          : heartbeat


          event: broadcast.ended

          data: {"broadcastId":12,"endedAt":"2026-10-03T12:00:00.000000Z"}

          '
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    $ref: '#/components/responses/BroadcastNotLive'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/seller/broadcasts/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
operationId: missingSellerBroadcastIdOnGet
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Seller Broadcast
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
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## PATCH /v1/seller/broadcasts/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Broadcast

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
operationId: missingSellerBroadcastIdOnPatch
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Seller Broadcast
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
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/

누락된 ID 요청 거절

끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.

**인증:** `[]`
**태그:** Public Broadcast

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
operationId: missingPublicBroadcastId
summary: 누락된 ID 요청 거절
description: 끝의 /는 목록 경로와 별도 매핑이다. ID가 비어 있는 요청에 400 INVALID_REQUEST를 반환한다.
tags:
- Public Broadcast
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
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{broadcastId}/promotions

방송 중 이벤트(프로모션) 생성

READY 상태로 저장한다. COUPON_DROP은 attributes.couponPolicyId의 쿠폰 UUID를 검증하고 요청 판매자의 JWT로 Commerce에서 본인 쿠폰인지 확인한다. 발급 기간 전 준비도 허용한다. 다른 유형은 메타데이터를 저장·전달하며 TIME_DEAL의 자동 가격 변경은 실행하지 않는다.
같은 `Idempotency-Key` 재요청은 fingerprint(type·title·attributes·durationSeconds)가 같으면
같은 리소스를 돌려주고, 다르면 409 다. 종료된 방송에는 만들 수 없다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |
| Idempotency-Key | header | 필수 | string | 1~128자의 등록 요청 식별자. 같은 키·같은 입력은 기존 리소스를 반환하고 다른 입력은 409이다. 방송과 프로모션의 키는 각각 구분한다. |

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| type | string | 필수 | 대문자로 시작하고 대문자·숫자·언더스코어로 구성된 2~64자 이름(예 COUPON_DROP). | {&quot;pattern&quot;: &quot;^&#91;A-Z&#93;&#91;A-Z0-9_&#93;{1,63}$&quot;} |
| title | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 100} |
| attributes | oneOf | 선택 | 생략하거나 null이면 빈 객체로 저장한다. | — |
| durationSeconds | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 | 자동 만료 시간. 비우면 수동 close 때까지 열려 있다. | {&quot;minimum&quot;: 10, &quot;maximum&quot;: 3600} |

```json
{
  "type": "COUPON_DROP",
  "title": "방송 한정 3,000원 할인",
  "attributes": {
    "couponPolicyId": "55555555-5555-4555-8555-555555555555"
  },
  "durationSeconds": 300
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 201 | 생성됨(같은 키·동일 입력은 같은 id) |
| 400 | 입력이 올바르지 않다. |
| 404 | 대상이 없다. |
| 409 | 같은 Idempotency-Key 에 다른 요청 본문이거나 방송이 종료 상태 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.type | string | 필수 |  | — |
| data.title | string | 필수 |  | — |
| data.attributes | object | 필수 | COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의 기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만 한다. | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;OPEN&quot;, &quot;CLOSED&quot;&#93;} |
| data.durationSeconds | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| data.openedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closesAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closeReason | oneOf | 선택 |  | — |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송 중 이벤트(프로모션) 생성
description: 'READY 상태로 저장한다. COUPON_DROP은 attributes.couponPolicyId의 쿠폰 UUID를 검증하고
  요청 판매자의 JWT로 Commerce에서 본인 쿠폰인지 확인한다. 발급 기간 전 준비도 허용한다. 다른 유형은 메타데이터를 저장·전달하며 TIME_DEAL의
  자동 가격 변경은 실행하지 않는다.

  같은 `Idempotency-Key` 재요청은 fingerprint(type·title·attributes·durationSeconds)가 같으면

  같은 리소스를 돌려주고, 다르면 409 다. 종료된 방송에는 만들 수 없다.

  '
operationId: createPromotion
tags:
- Seller Promotion
parameters:
- $ref: '#/components/parameters/IdempotencyKey'
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/PromotionInput'
      example:
        type: COUPON_DROP
        title: 방송 한정 3,000원 할인
        attributes:
          couponPolicyId: 55555555-5555-4555-8555-555555555555
        durationSeconds: 300
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '201':
    description: 생성됨(같은 키·동일 입력은 같은 id)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: 같은 Idempotency-Key 에 다른 요청 본문이거나 방송이 종료 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/seller/broadcasts/{broadcastId}/promotions

방송의 프로모션 전체 목록

상태와 관계없이 해당 방송의 프로모션을 id 오름차순으로 반환한다. 방송이 없거나 프로모션이 없으면 빈 목록이다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 목록 |
| 400 | 입력이 올바르지 않다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.type | string | 필수 |  | — |
| data&#91;&#93;.title | string | 필수 |  | — |
| data&#91;&#93;.attributes | object | 필수 | COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의 기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만 한다. | — |
| data&#91;&#93;.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;OPEN&quot;, &quot;CLOSED&quot;&#93;} |
| data&#91;&#93;.durationSeconds | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| data&#91;&#93;.openedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.closesAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.closedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.closeReason | oneOf | 선택 |  | — |
| data&#91;&#93;.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 방송의 프로모션 전체 목록
description: 상태와 관계없이 해당 방송의 프로모션을 id 오름차순으로 반환한다. 방송이 없거나 프로모션이 없으면 빈 목록이다.
operationId: listPromotions
tags:
- Seller Promotion
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionListEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{broadcastId}/promotions/{promotionId}/open

프로모션 열기

READY → OPEN 전이. LIVE 방송에서만 성공한다(그 외는 409). 이미 OPEN 이면 같은 응답을
돌려주고 멱등으로 성공한다. COUPON_DROP의 READY 이벤트는 열기 전에 본인 쿠폰의 발급 기간·잔여 수량을 확인한다. 이미 OPEN인 재요청은 쿠폰 재검증 없이 기존 전이를 유지한다. 닫혀 있던 프로모션을 다시 열면 409 다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |
| promotionId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 열림(멱등 응답 포함) |
| 404 | 대상이 없다. |
| 409 | 방송이 LIVE 가 아니거나 프로모션이 CLOSED |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.type | string | 필수 |  | — |
| data.title | string | 필수 |  | — |
| data.attributes | object | 필수 | COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의 기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만 한다. | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;OPEN&quot;, &quot;CLOSED&quot;&#93;} |
| data.durationSeconds | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| data.openedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closesAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closeReason | oneOf | 선택 |  | — |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 409 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 프로모션 열기
description: 'READY → OPEN 전이. LIVE 방송에서만 성공한다(그 외는 409). 이미 OPEN 이면 같은 응답을

  돌려주고 멱등으로 성공한다. COUPON_DROP의 READY 이벤트는 열기 전에 본인 쿠폰의 발급 기간·잔여 수량을 확인한다. 이미 OPEN인
  재요청은 쿠폰 재검증 없이 기존 전이를 유지한다. 닫혀 있던 프로모션을 다시 열면 409 다.

  '
operationId: openPromotion
tags:
- Seller Promotion
responses:
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 열림(멱등 응답 포함)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionEnvelope'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: 방송이 LIVE 가 아니거나 프로모션이 CLOSED
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/ErrorEnvelope'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
  - $ref: '#/components/parameters/PromotionId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/seller/broadcasts/{broadcastId}/promotions/{promotionId}/close

프로모션 닫기

READY · OPEN → CLOSED 전이. 이미 CLOSED 면 같은 응답으로 멱등 성공. READY 에서 호출하면
reason 이 CANCELED 로 저장된다.


**인증:** `[{"BearerAuth": []}]`
**태그:** Seller Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |
| promotionId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 200 | 닫힘(멱등 응답 포함) |
| 404 | 대상이 없다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data.type | string | 필수 |  | — |
| data.title | string | 필수 |  | — |
| data.attributes | object | 필수 | COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의 기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만 한다. | — |
| data.status | string | 필수 |  | {&quot;enum&quot;: &#91;&quot;READY&quot;, &quot;OPEN&quot;, &quot;CLOSED&quot;&#93;} |
| data.durationSeconds | &#91;&#x27;integer&#x27;, &#x27;null&#x27;&#93; | 선택 |  | — |
| data.openedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closesAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closedAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data.closeReason | oneOf | 선택 |  | — |
| data.version | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| error | null | 필수 |  | — |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
x-required-roles:
- SELLER
summary: 프로모션 닫기
description: 'READY · OPEN → CLOSED 전이. 이미 CLOSED 면 같은 응답으로 멱등 성공. READY 에서 호출하면

  reason 이 CANCELED 로 저장된다.

  '
operationId: closePromotion
tags:
- Seller Promotion
responses:
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '200':
    description: 닫힘(멱등 응답 포함)
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionEnvelope'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
  - $ref: '#/components/parameters/PromotionId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## POST /v1/broadcasts/{broadcastId}/promotions/{promotionId}/claims

방송 쿠폰 이벤트의 쿠폰 발급

접수 시 방송 LIVE, 이벤트 OPEN, 이벤트 자동 종료 시각 이전, COUPON_DROP 유형과 쿠폰 UUID를 확인한다. 이후 동일 사용자 Bearer로 Commerce 쿠폰 발급을 호출한다. 쿠폰 기간·중복 발급·전체 발급 한도는 Commerce에서 원자적으로 처리한다. 라이브 상태 확인과 Commerce 발급은 분산 트랜잭션이 아니므로 확인 직후 방송·이벤트가 종료되어도 이미 접수된 발급이 완료될 수 있다. 동일 회원의 재발급 결과는 기존 쿠폰을 반환하되, 종료된 이벤트 경로에서는 다시 발급할 수 없다. 이미 받은 쿠폰은 내 쿠폰에서 조회한다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Public Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |
| promotionId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 새 쿠폰 발급 |
| 200 | 동일 회원의 기존 발급 결과 |
| 400 | 입력이 올바르지 않다. |
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 403 | 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음 |
| 404 | 대상이 없다. |
| 409 | 방송·이벤트 종료 또는 쿠폰 기간·수량 정책 충돌 |
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
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 403 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 404 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "방송을 찾을 수 없습니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

### 계약 원문

```yaml
operationId: claimPromotionCoupon
tags:
- Public Promotion
x-required-roles:
- USER
- SELLER
summary: 방송 쿠폰 이벤트의 쿠폰 발급
description: 접수 시 방송 LIVE, 이벤트 OPEN, 이벤트 자동 종료 시각 이전, COUPON_DROP 유형과 쿠폰 UUID를 확인한다.
  이후 동일 사용자 Bearer로 Commerce 쿠폰 발급을 호출한다. 쿠폰 기간·중복 발급·전체 발급 한도는 Commerce에서 원자적으로 처리한다.
  라이브 상태 확인과 Commerce 발급은 분산 트랜잭션이 아니므로 확인 직후 방송·이벤트가 종료되어도 이미 접수된 발급이 완료될 수 있다. 동일
  회원의 재발급 결과는 기존 쿠폰을 반환하되, 종료된 이벤트 경로에서는 다시 발급할 수 없다. 이미 받은 쿠폰은 내 쿠폰에서 조회한다.
responses:
  '201':
    description: 새 쿠폰 발급
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionCouponClaimEnvelope'
  '200':
    description: 동일 회원의 기존 발급 결과
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PromotionCouponClaimEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '409':
    description: 방송·이벤트 종료 또는 쿠폰 기간·수량 정책 충돌
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
  - $ref: '#/components/parameters/PromotionId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## GET /v1/broadcasts/{broadcastId}/promotions

공개 — 지금 열린 방송 중 이벤트 목록

비회원도 호출할 수 있다. 현재 OPEN인 프로모션을 id 오름차순으로 반환한다. 해당 항목이 없으면 빈 목록이다.

**인증:** `[]`
**태그:** Public Promotion

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| broadcastId | path | 필수 | integer |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 401 | 인증 누락 또는 위조·만료 토큰. 공개 경로라도 잘못된 Bearer를 보내면 401. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |
| 200 | 열린 프로모션 목록 |
| 400 | 입력이 올바르지 않다. |

#### 401 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 503 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | array | 필수 |  | — |
| data&#91;&#93;.id | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.broadcastId | integer | 필수 |  | {&quot;format&quot;: &quot;int64&quot;} |
| data&#91;&#93;.type | string | 필수 |  | — |
| data&#91;&#93;.title | string | 필수 |  | — |
| data&#91;&#93;.attributes | object | 필수 | COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의 기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만 한다. | — |
| data&#91;&#93;.openedAt | string | 필수 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| data&#91;&#93;.closesAt | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 선택 |  | {&quot;format&quot;: &quot;date-time&quot;} |
| error | null | 필수 |  | — |

#### 400 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | null | 필수 |  | — |
| error | allOf | 필수 |  | — |
| error&#91;allOf 1&#93;.code | string | 필수 |  | — |
| error&#91;allOf 1&#93;.message | string | 필수 |  | — |
| error&#91;allOf 1&#93;.requestId | &#91;&#x27;string&#x27;, &#x27;null&#x27;&#93; | 필수 |  | — |
| error&#91;allOf 2&#93;.code | string | 선택 |  | {&quot;enum&quot;: &#91;&quot;INVALID_REQUEST&quot;, &quot;UNAUTHORIZED&quot;, &quot;FORBIDDEN&quot;, &quot;NOT_FOUND&quot;, &quot;CONFLICT&quot;, &quot;SERVICE_UNAVAILABLE&quot;, &quot;INTERNAL_ERROR&quot;&#93;} |

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVALID_REQUEST",
    "message": "제목은 1~100자여야 합니다.",
    "requestId": "6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d"
  }
}
```

### 계약 원문

```yaml
security: []
summary: 공개 — 지금 열린 방송 중 이벤트 목록
description: 비회원도 호출할 수 있다. 현재 OPEN인 프로모션을 id 오름차순으로 반환한다. 해당 항목이 없으면 빈 목록이다.
operationId: listPublicPromotions
tags:
- Public Promotion
responses:
  '401':
    $ref: '#/components/responses/Unauthorized'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
  '200':
    description: 열린 프로모션 목록
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/PublicPromotionListEnvelope'
  '400':
    $ref: '#/components/responses/BadRequest'
경로 공통 선언:
  parameters:
  - $ref: '#/components/parameters/PromotionBroadcastId'
적용 서버:
- url: http://localhost:8084
  description: Live 서비스 로컬 실행 주소 (port 8084)
- url: http://dev-live-service:8080
  description: Live 서비스 dev 네임스페이스 Service 주소 (컨테이너 port 8080)
```

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### commercecore-service.yaml

```yaml
components:
  schemas:
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

### live-service.yaml

```yaml
components:
  parameters:
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      description: 1~128자의 등록 요청 식별자. 같은 키·같은 입력은 기존 리소스를 반환하고 다른 입력은 409이다. 방송과 프로모션의
        키는 각각 구분한다.
      schema:
        type: string
        minLength: 1
        maxLength: 128
      example: broadcast-2026-10-01-001
    Page:
      name: page
      in: query
      required: false
      schema:
        type: integer
        minimum: 0
        default: 0
    Size:
      name: size
      in: query
      required: false
      description: 페이지 크기. 100을 초과하면 100으로 제한한다.
      schema:
        type: integer
        format: int32
        minimum: 1
        default: 20
    BroadcastId:
      name: id
      in: path
      required: true
      schema:
        type: integer
        format: int64
    Version:
      name: version
      in: query
      required: true
      description: 마지막으로 조회한 방송 version. 어긋나면 409 다.
      schema:
        type: integer
        format: int64
        minimum: 0
    ExpectedVersion:
      name: expectedVersion
      in: query
      required: true
      description: 마지막으로 조회한 방송 version. 어긋나면 409 다.
      schema:
        type: integer
        format: int64
        minimum: 0
    PromotionBroadcastId:
      name: broadcastId
      in: path
      required: true
      schema:
        type: integer
        format: int64
    PromotionId:
      name: promotionId
      in: path
      required: true
      schema:
        type: integer
        format: int64
  schemas:
    BroadcastInput:
      type: object
      required:
      - title
      - scheduledAt
      - channelArn
      - playbackUrl
      properties:
        title:
          type: string
          minLength: 1
          maxLength: 100
        scheduledAt:
          type: string
          format: date-time
          description: offset 을 포함한 ISO-8601. 과거 시각도 허용한다. UI 는 KST 로 표시한다.
        channelArn:
          type: string
          minLength: 1
          maxLength: 255
        playbackUrl:
          type: string
          minLength: 1
          maxLength: 2048
        hostName:
          type: string
          maxLength: 80
          description: 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다.
        description:
          type: string
          maxLength: 2000
          description: 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다.
        thumbnailId:
          type: string
          maxLength: 36
          description: 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다.
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
      allOf:
      - $ref: ./common.yaml#/components/schemas/ApiError
      - type: object
        properties:
          code:
            type: string
            enum:
            - INVALID_REQUEST
            - UNAUTHORIZED
            - FORBIDDEN
            - NOT_FOUND
            - CONFLICT
            - SERVICE_UNAVAILABLE
            - INTERNAL_ERROR
    BroadcastEnvelope:
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
          $ref: '#/components/schemas/Broadcast'
        error:
          type: 'null'
    Broadcast:
      type: object
      description: 관리 응답. 운영자가 채널·시청 URL 일치를 확인해야 하므로 IVS 필드를 포함한다.
      required:
      - id
      - title
      - scheduledAt
      - channelArn
      - playbackUrl
      - status
      - startedAt
      - endedAt
      - version
      - createdAt
      - updatedAt
      properties:
        id:
          type: integer
          format: int64
        title:
          type: string
        scheduledAt:
          type: string
          format: date-time
        channelArn:
          type: string
        playbackUrl:
          type: string
        status:
          $ref: '#/components/schemas/BroadcastStatus'
        startedAt:
          type:
          - string
          - 'null'
          format: date-time
        endedAt:
          type:
          - string
          - 'null'
          format: date-time
        version:
          type: integer
          format: int64
        createdAt:
          type: string
          format: date-time
        updatedAt:
          type: string
          format: date-time
        hostName:
          type: string
          maxLength: 80
          description: 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다.
        description:
          type: string
          maxLength: 2000
          description: 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다.
        thumbnailId:
          type: string
          maxLength: 36
          description: 업로드한 썸네일 UUID. 생략 시 기존 값 유지, 빈 문자열은 제거, 다른 판매자 썸네일은 403이다.
        thumbnailUrl:
          type:
          - string
          - 'null'
          description: 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다.
    BroadcastStatus:
      type: string
      enum:
      - PREPARING
      - LIVE
      - ENDED
    BroadcastPageEnvelope:
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
          - content
          - number
          - size
          - totalElements
          - totalPages
          properties:
            content:
              type: array
              items:
                $ref: '#/components/schemas/Broadcast'
            number:
              type: integer
            size:
              type: integer
            totalElements:
              type: integer
              format: int64
            totalPages:
              type: integer
        error:
          type: 'null'
    BroadcastPatchInput:
      type: object
      description: '생략한 필드는 기존 값을 유지한다. 명시적 null 은 400 이다.

        channelArn 과 playbackUrl 은 함께 변경해야 한다.

        '
      properties:
        title:
          type: string
          minLength: 1
          maxLength: 100
        scheduledAt:
          type: string
          format: date-time
        channelArn:
          type: string
          minLength: 1
          maxLength: 255
        playbackUrl:
          type: string
          minLength: 1
          maxLength: 2048
        hostName:
          type: string
          maxLength: 80
          description: 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다.
        description:
          type: string
          maxLength: 2000
          description: 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다.
        thumbnailId:
          type: string
          maxLength: 36
          description: 썸네일 UUID. 생략 또는 기존 ID 재전송은 유지하고 빈 문자열은 제거한다. 새로 연결·교체하는 ID만
            현재 판매자 소유여야 하며 타인 이미지는 403이다.
      additionalProperties: false
    LinkProductInput:
      type: object
      required:
      - productId
      - expectedVersion
      properties:
        productId:
          type: integer
          format: int64
          minimum: 1
          description: Shopping 상품 식별자. Commerce salesId 와 같은 값이라고 가정하지 않는다.
        expectedVersion:
          type: integer
          format: int64
          minimum: 0
    BroadcastProductLinkEnvelope:
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
          $ref: '#/components/schemas/BroadcastProductLink'
        error:
          type: 'null'
    BroadcastProductLink:
      type: object
      required:
      - linkId
      - productId
      - salesId
      - position
      - broadcastVersion
      properties:
        linkId:
          type: integer
          format: int64
        productId:
          type: integer
          format: int64
        salesId:
          type: integer
          format: int64
        position:
          type: integer
          minimum: 0
        broadcastVersion:
          type: integer
          format: int64
    BroadcastProductViewEnvelope:
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
            $ref: '#/components/schemas/BroadcastProductView'
        error:
          type: 'null'
    BroadcastProductView:
      type: object
      required:
      - linkId
      - position
      - productId
      - salesId
      - name
      - mainImageUrl
      - price
      - available
      - status
      - purchasable
      - missing
      properties:
        linkId:
          type: integer
          format: int64
        position:
          type: integer
          minimum: 0
        productId:
          type: integer
          format: int64
        salesId:
          type:
          - integer
          - 'null'
          format: int64
        name:
          type:
          - string
          - 'null'
        mainImageUrl:
          type:
          - string
          - 'null'
          format: uri-reference
          description: null 은 기본 이미지 표시. 상대 URL 은 서비스 제공 이미지 경로이며 별도 배포 prefix를 유지한다.
        price:
          type:
          - integer
          - 'null'
          format: int64
          description: KRW 정수
        available:
          type:
          - integer
          - 'null'
          minimum: 0
          description: 결제 대기 예약분을 제외한 주문 가능 재고. 관리 조회에서 상품 또는 판매정보 누락 시 null이다. 판매
            상태와 별개이며 ON_SALE에서 0이어도 상태를 변경하지 않는다.
        status:
          type:
          - string
          - 'null'
          enum:
          - ON_SALE
          - SOLD_OUT
          - READY
          - PRIVATE
          - null
        purchasable:
          type: boolean
          description: ON_SALE이고 available이 0보다 클 때만 true. 최종 판정은 Commerce 가 다시 한다.
        missing:
          type: boolean
          description: 정상 조회에서 사라진 연결. 관리 응답에서만 true 가 될 수 있다.
    ReorderProductsInput:
      type: object
      required:
      - linkIds
      - expectedVersion
      properties:
        linkIds:
          type: array
          minItems: 1
          maxItems: 100
          items:
            type: integer
            format: int64
          description: 이 방송의 전체 연결 linkId 순열.
        expectedVersion:
          type: integer
          format: int64
          minimum: 0
    BroadcastProductLinkListEnvelope:
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
            $ref: '#/components/schemas/BroadcastProductLink'
        error:
          type: 'null'
    PublicBroadcastPageEnvelope:
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
          - content
          - number
          - size
          - totalElements
          - totalPages
          properties:
            content:
              type: array
              items:
                $ref: '#/components/schemas/PublicBroadcast'
            number:
              type: integer
            size:
              type: integer
            totalElements:
              type: integer
              format: int64
            totalPages:
              type: integer
        error:
          type: 'null'
    PublicBroadcast:
      type: object
      description: 공개 응답. 채널 ARN·등록 키·fingerprint 를 포함하지 않는다.
      required:
      - id
      - title
      - scheduledAt
      - status
      - playbackUrl
      - playbackAllowed
      - videoStatus
      - createdAt
      properties:
        id:
          type: integer
          format: int64
        title:
          type: string
        scheduledAt:
          type: string
          format: date-time
        status:
          type: string
          enum:
          - 예정
          - 진행
          - 종료
        playbackUrl:
          type:
          - string
          - 'null'
          description: LIVE 에서만 반환한다.
        playbackAllowed:
          type: boolean
          description: 업무 상태다. 현재 영상 신호를 보장하지 않는다.
        videoStatus:
          oneOf:
          - $ref: '#/components/schemas/IvsReadiness'
          - type: 'null'
          description: LIVE 상세에서만 채운다. 목록과 비 LIVE 상세에서는 null 이다.
        createdAt:
          type: string
          format: date-time
        hostName:
          type: string
          maxLength: 80
          description: 판매자가 입력하는 공개 진행자 이름. 생략 시 기존 값 또는 빈 문자열이다.
        description:
          type: string
          maxLength: 2000
          description: 공개 방송 소개. 생략 시 기존 값 또는 빈 문자열이다.
        thumbnailUrl:
          type:
          - string
          - 'null'
          description: 공개 썸네일 주소. 기존 방송 또는 미등록은 null이다.
    IvsReadiness:
      type: string
      description: 영상 신호 상태. 업무 상태와 분리된다.
      enum:
      - READY
      - NOT_READY
      - UNAVAILABLE
    PublicBroadcastEnvelope:
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
          $ref: '#/components/schemas/PublicBroadcast'
        error:
          type: 'null'
    ChatMessageListEnvelope:
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
          maxItems: 50
          items:
            $ref: '#/components/schemas/ChatMessage'
        error:
          type: 'null'
    ChatMessage:
      type: object
      description: 공개 채팅. 작성자 회원 ID 는 포함하지 않는다.
      required:
      - messageId
      - broadcastId
      - displayName
      - content
      - createdAt
      properties:
        messageId:
          type: integer
          format: int64
        broadcastId:
          type: integer
          format: int64
        displayName:
          type: string
          minLength: 1
          maxLength: 80
          description: 작성 당시의 표시 이름. 이후 이름을 바꿔도 변하지 않는다.
        content:
          type: string
          minLength: 1
          maxLength: 200
        createdAt:
          type: string
          format: date-time
          description: 서버 작성 시각
    ChatInput:
      type: object
      required:
      - content
      properties:
        content:
          type: string
          minLength: 1
          description: 앞뒤 공백 제거 후 code point 기준 1~200자. 공백만 있으면 400 이다.
    ChatMessageEnvelope:
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
          $ref: '#/components/schemas/ChatMessage'
        error:
          type: 'null'
    ViewerTotalEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: '#/components/schemas/ViewerTotal'
        error:
          type: 'null'
    ViewerTotal:
      type: object
      required:
      - broadcastId
      - total
      - measuredAt
      - expiresAfterSeconds
      properties:
        broadcastId:
          type: integer
          format: int64
        total:
          type: integer
          format: int64
          minimum: 0
        measuredAt:
          type: string
          format: date-time
        expiresAfterSeconds:
          type: integer
          const: 60
    LikeTotalEnvelope:
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
          $ref: '#/components/schemas/LikeTotal'
        error:
          type: 'null'
    LikeTotal:
      type: object
      required:
      - broadcastId
      - total
      - version
      properties:
        broadcastId:
          type: integer
          format: int64
        total:
          type: integer
          format: int64
          minimum: 0
        version:
          type: integer
          format: int64
          minimum: 0
    MemberLikeEnvelope:
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
          $ref: '#/components/schemas/MemberLike'
        error:
          type: 'null'
    MemberLike:
      type: object
      required:
      - broadcastId
      - liked
      - stateVersion
      - total
      - version
      properties:
        broadcastId:
          type: integer
          format: int64
        liked:
          type: boolean
        stateVersion:
          type: integer
          format: int64
          minimum: 0
        total:
          type: integer
          format: int64
          minimum: 0
        version:
          type: integer
          format: int64
          minimum: 0
    PromotionInput:
      type: object
      allOf:
      - if:
          properties:
            type:
              const: COUPON_DROP
          required:
          - type
        then:
          required:
          - attributes
          properties:
            attributes:
              type: object
              required:
              - couponPolicyId
              properties:
                couponPolicyId:
                  type: string
                  format: uuid
      required:
      - type
      - title
      properties:
        type:
          type: string
          pattern: ^[A-Z][A-Z0-9_]{1,63}$
          description: 대문자로 시작하고 대문자·숫자·언더스코어로 구성된 2~64자 이름(예 COUPON_DROP).
        title:
          type: string
          minLength: 1
          maxLength: 100
        attributes:
          description: 생략하거나 null이면 빈 객체로 저장한다.
          oneOf:
          - $ref: '#/components/schemas/PromotionAttributes'
          - type: 'null'
        durationSeconds:
          type:
          - integer
          - 'null'
          minimum: 10
          maximum: 3600
          description: 자동 만료 시간. 비우면 수동 close 때까지 열려 있다.
    PromotionAttributes:
      type: object
      description: COUPON_DROP은 couponPolicyId에 기존 쿠폰 UUID가 필요하다. 쿠폰 기간·발급 수량은 Commerce의
        기존 정책을 사용한다. claimLimit 등 임의 메타데이터는 별도 한도로 집행하지 않는다. 다른 종류의 메타데이터는 저장·전달만
        한다.
      additionalProperties: true
    PromotionEnvelope:
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
          $ref: '#/components/schemas/Promotion'
        error:
          type: 'null'
    Promotion:
      type: object
      required:
      - id
      - broadcastId
      - type
      - title
      - attributes
      - status
      - version
      properties:
        id:
          type: integer
          format: int64
        broadcastId:
          type: integer
          format: int64
        type:
          type: string
        title:
          type: string
        attributes:
          $ref: '#/components/schemas/PromotionAttributes'
        status:
          $ref: '#/components/schemas/PromotionStatus'
        durationSeconds:
          type:
          - integer
          - 'null'
        openedAt:
          type:
          - string
          - 'null'
          format: date-time
        closesAt:
          type:
          - string
          - 'null'
          format: date-time
        closedAt:
          type:
          - string
          - 'null'
          format: date-time
        closeReason:
          oneOf:
          - $ref: '#/components/schemas/PromotionCloseReason'
          - type: 'null'
        version:
          type: integer
          format: int64
    PromotionStatus:
      type: string
      enum:
      - READY
      - OPEN
      - CLOSED
    PromotionCloseReason:
      type: string
      enum:
      - SELLER_CLOSED
      - EXPIRED
      - BROADCAST_ENDED
      - CANCELED
    PromotionListEnvelope:
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
            $ref: '#/components/schemas/Promotion'
        error:
          type: 'null'
    PromotionCouponClaimEnvelope:
      type: object
      required:
      - success
      - data
      - error
      properties:
        success:
          const: true
        data:
          $ref: ./commercecore-service.yaml#/components/schemas/MemberCoupon
        error:
          type: 'null'
    PublicPromotionListEnvelope:
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
            $ref: '#/components/schemas/PublicPromotion'
        error:
          type: 'null'
    PublicPromotion:
      type: object
      description: 시청자에게 노출하는 열린 프로모션 요약.
      required:
      - id
      - broadcastId
      - type
      - title
      - attributes
      - openedAt
      properties:
        id:
          type: integer
          format: int64
        broadcastId:
          type: integer
          format: int64
        type:
          type: string
        title:
          type: string
        attributes:
          $ref: '#/components/schemas/PromotionAttributes'
        openedAt:
          type: string
          format: date-time
        closesAt:
          type:
          - string
          - 'null'
          format: date-time
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
    Forbidden:
      description: 검증된 사용자 또는 서비스 호출자에게 필요한 권한이 없음
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
      description: 입력이 올바르지 않다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          example:
            success: false
            data: null
            error:
              code: INVALID_REQUEST
              message: 제목은 1~100자여야 합니다.
              requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
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
      description: 대상이 없다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          example:
            success: false
            data: null
            error:
              code: NOT_FOUND
              message: 방송을 찾을 수 없습니다.
              requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
    ServiceUnavailable:
      description: 외부 서비스(Shopping/Commerce/IVS) 장애. 업무 조건 불충족(409)과 구분한다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          example:
            success: false
            data: null
            error:
              code: SERVICE_UNAVAILABLE
              message: 상품 정보를 조회할 수 없습니다.
              requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
    BroadcastNotLive:
      description: 방송이 LIVE 가 아니다(PREPARING 또는 ENDED).
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          example:
            success: false
            data: null
            error:
              code: CONFLICT
              message: 진행 중인 방송이 아닙니다.
              requestId: 6f1c1f0e-1d2a-4a3b-9c8d-0e1f2a3b4c5d
    LikeUnavailable:
      description: 좋아요 PostgreSQL 저장소 또는 Member 인증 상태 저장소가 일시적으로 불가함. 같은 요청 키로 재시도한다.
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          example:
            success: false
            data: null
            error:
              code: SERVICE_UNAVAILABLE
              message: 좋아요 저장소를 사용할 수 없습니다.
              requestId: request-001
  securitySchemes:
    BearerAuth:
      $ref: ./common.yaml#/components/securitySchemes/BearerAuth
```

입력 SHA-256: `24ef15b0f6e5a49ed457c7c2b17762f229559880a95fb4b0564c161f750a18cb`
