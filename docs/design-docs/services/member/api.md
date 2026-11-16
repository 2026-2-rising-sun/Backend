# Member Service API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/member-service.yaml) · OpenAPI 3.1.0 · 계약 0.3.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

회원가입·로그인·프로필·세션 관리 API. JSON 성공·오류 응답은 ApiResponse 봉투이며 204는 본문이 없다.
Access 수명은 15분, refresh family는 최초 로그인부터 절대 30일이며 rotation으로 연장되지 않는다.
일반 로그아웃은 refresh만 폐기하여 기존 access의 남은 수명을 허용한다. refresh 재사용·판매자 본인 세션 폐기·탈퇴는 이후 신규 인증 검사부터 access도 차단한다.
이미 인증 검사를 통과한 요청을 소급 취소하지 않는다. 쿠키를 사용하지 않는다.

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
    description: Shopping/Commerce/Live/Notification 방향별 opaque caller 자격증명. 사용자·SELLER
      JWT와 대체할 수 없다.
servers:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/auth/signup

이메일 회원가입



**인증:** `[]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| email | string | 필수 |  | {&quot;format&quot;: &quot;email&quot;, &quot;maxLength&quot;: 254} |
| password | string | 필수 | UTF-8 72바이트 이하도 검사. 로그·응답에 반환하지 않는다. | {&quot;format&quot;: &quot;password&quot;, &quot;minLength&quot;: 8, &quot;maxLength&quot;: 72, &quot;writeOnly&quot;: true} |
| displayName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 80} |

```json
{
  "email": "member@example.com",
  "password": "test-password-123",
  "displayName": "회원"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 201 | 성공 |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 409 | 정규화한 이메일 중복 (동시 가입 DB unique 충돌 포함) |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |
| 500 | 서버 내부 오류 |

#### 201 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.memberId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.email | string | 필수 |  | {&quot;format&quot;: &quot;email&quot;} |
| data.displayName | string | 필수 |  | — |
| data.roles | array | 필수 |  | — |
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
tags:
- Member
summary: 이메일 회원가입
operationId: signupMember
security: []
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/SignupRequest'
      example:
        email: member@example.com
        password: test-password-123
        displayName: 회원
responses:
  '201':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberEnvelope'
        examples:
          created:
            value:
              success: true
              data:
                memberId: 11111111-1111-4111-8111-111111111111
                email: member@example.com
                displayName: 회원
                roles:
                - USER
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '409':
    $ref: '#/components/responses/Conflict'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
  '500':
    $ref: '#/components/responses/InternalError'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/auth/login

이메일 로그인 및 토큰 발급

이메일 정규화 후 인증한다. 없는 계정·틀린 비밀번호·잠긴 계정은 모두 같은 401. 쿠키를 사용하지 않고 응답 JSON으로 토큰을 돌려준다. 연속 실패 제한과 잠금 기간은 서버 설정이다.

**인증:** `[]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| email | string | 필수 |  | {&quot;format&quot;: &quot;email&quot;, &quot;maxLength&quot;: 254} |
| password | string | 필수 | UTF-8 72바이트 이하. 공백만 있는 값 금지. | {&quot;format&quot;: &quot;password&quot;, &quot;minLength&quot;: 1, &quot;maxLength&quot;: 72, &quot;writeOnly&quot;: true} |

```json
{
  "email": "member@example.com",
  "password": "test-password-123"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | access JWT 및 초기 refresh token 발급. 예시 문자열은 인증에 사용할 수 없다. |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.accessToken | string | 필수 | RS256 JWT. canonical UUID sub/sid와 iss/aud/exp/iat/jti/roles를 포함한다. 실제 토큰을 예시에 저장하지 않는다. | {&quot;minLength&quot;: 1, &quot;readOnly&quot;: true} |
| data.tokenType | string | 필수 |  | — |
| data.expiresIn | integer | 필수 | access 잔여 수명 초수. 승인 운영 정책은 900초이며 JWT exp-iat와 같다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.refreshToken | string | 필수 | 회전하는 opaque credential. DB에는 해시만 저장하며 family 절대 만료는 최초 로그인 +30일에서 연장되지 않는다. | {&quot;minLength&quot;: 1, &quot;readOnly&quot;: true} |
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
tags:
- Member
summary: 이메일 로그인 및 토큰 발급
operationId: loginMember
security: []
description: 이메일 정규화 후 인증한다. 없는 계정·틀린 비밀번호·잠긴 계정은 모두 같은 401. 쿠키를 사용하지 않고 응답 JSON으로
  토큰을 돌려준다. 연속 실패 제한과 잠금 기간은 서버 설정이다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/LoginRequest'
      example:
        email: member@example.com
        password: test-password-123
responses:
  '200':
    description: access JWT 및 초기 refresh token 발급. 예시 문자열은 인증에 사용할 수 없다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/TokenEnvelope'
        examples:
          issued:
            value:
              success: true
              data:
                accessToken: contract-example-not-a-signed-jwt
                tokenType: Bearer
                expiresIn: 900
                refreshToken: contract-example-not-a-refresh-credential
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
  '500':
    $ref: '#/components/responses/InternalError'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## GET /v1/members/me

본인 회원정보 조회



**인증:** `[{"BearerAuth": []}]`
**태그:** Member

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 404 | 대상 회원 레코드가 없음 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.memberId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.email | string | 필수 |  | {&quot;format&quot;: &quot;email&quot;} |
| data.displayName | string | 필수 |  | — |
| data.roles | array | 필수 |  | — |
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
tags:
- Member
summary: 본인 회원정보 조회
operationId: getMyProfile
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberEnvelope'
        examples:
          normal:
            value:
              success: true
              data:
                memberId: 11111111-1111-4111-8111-111111111111
                email: member@example.com
                displayName: 회원
                roles:
                - USER
              error: null
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
  '500':
    $ref: '#/components/responses/InternalError'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## PATCH /v1/members/me

본인 표시 이름 수정



**인증:** `[{"BearerAuth": []}]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| displayName | string | 필수 |  | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 80} |

```json
{
  "displayName": "새 이름"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 성공 |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 404 | 대상 회원 레코드가 없음 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |
| 500 | 서버 내부 오류 |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.memberId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| data.email | string | 필수 |  | {&quot;format&quot;: &quot;email&quot;} |
| data.displayName | string | 필수 |  | — |
| data.roles | array | 필수 |  | — |
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
tags:
- Member
summary: 본인 표시 이름 수정
operationId: updateMyProfile
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/ProfileUpdateRequest'
      example:
        displayName: 새 이름
responses:
  '200':
    description: 성공
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/MemberEnvelope'
        examples:
          updated:
            value:
              success: true
              data:
                memberId: 11111111-1111-4111-8111-111111111111
                email: member@example.com
                displayName: 새 이름
                roles:
                - USER
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '404':
    $ref: '#/components/responses/NotFound'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
  '500':
    $ref: '#/components/responses/InternalError'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## DELETE /v1/members/me

비밀번호 확인 후 본인 탈퇴

회원 상태를 탈퇴로 바꾸고 모든 family를 보안 폐기한다. 이후 신규 인증 검사는 기존 access도 401로 거부한다. 물리 삭제·타 도메인 주문 삭제가 아니며 같은 이메일 재가입은 409로 거부한다. 비밀번호 불일치 401.

**인증:** `[{"BearerAuth": []}]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| password | string | 필수 | 공백만 있는 값 금지. UTF-8 72바이트 이하도 검사한다. | {&quot;format&quot;: &quot;password&quot;, &quot;minLength&quot;: 1, &quot;maxLength&quot;: 72, &quot;writeOnly&quot;: true} |

```json
{
  "password": "test-password-123"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 탈퇴 완료. 응답 본문 없음. |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 500 | 서버 내부 오류 |
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
tags:
- Member
summary: 비밀번호 확인 후 본인 탈퇴
operationId: withdrawMyMembership
description: 회원 상태를 탈퇴로 바꾸고 모든 family를 보안 폐기한다. 이후 신규 인증 검사는 기존 access도 401로 거부한다.
  물리 삭제·타 도메인 주문 삭제가 아니며 같은 이메일 재가입은 409로 거부한다. 비밀번호 불일치 401.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/WithdrawRequest'
      example:
        password: test-password-123
responses:
  '204':
    description: 탈퇴 완료. 응답 본문 없음.
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/auth/refresh

Refresh 교체 및 access 재발급

Access 없이 refresh JSON으로 호출한다. 사용한 refresh는 재사용할 수 없다. 알려진 사용 완료 토큰의 재사용은 해당 family를 보안 폐기한 뒤 401을 반환한다. 만료·미지·폐기된 토큰도 401. 교체해도 최초 로그인 +30일의 절대 만료를 유지한다.

**인증:** `[]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| refreshToken | string | 필수 | 공백만 있는 값 금지. opaque credential이며 실제 값은 문서·로그에 기록하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 512, &quot;writeOnly&quot;: true} |

```json
{
  "refreshToken": "contract-example-not-a-refresh-credential"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 새 access와 교체 refresh. 예제는 실제 인증에 사용할 수 없다. |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 500 | 서버 내부 오류 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.accessToken | string | 필수 | RS256 JWT. canonical UUID sub/sid와 iss/aud/exp/iat/jti/roles를 포함한다. 실제 토큰을 예시에 저장하지 않는다. | {&quot;minLength&quot;: 1, &quot;readOnly&quot;: true} |
| data.tokenType | string | 필수 |  | — |
| data.expiresIn | integer | 필수 | access 잔여 수명 초수. 승인 운영 정책은 900초이며 JWT exp-iat와 같다. | {&quot;format&quot;: &quot;int64&quot;, &quot;minimum&quot;: 1} |
| data.refreshToken | string | 필수 | 회전하는 opaque credential. DB에는 해시만 저장하며 family 절대 만료는 최초 로그인 +30일에서 연장되지 않는다. | {&quot;minLength&quot;: 1, &quot;readOnly&quot;: true} |
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
tags:
- Member
summary: Refresh 교체 및 access 재발급
operationId: refreshMemberSession
security: []
description: Access 없이 refresh JSON으로 호출한다. 사용한 refresh는 재사용할 수 없다. 알려진 사용 완료 토큰의
  재사용은 해당 family를 보안 폐기한 뒤 401을 반환한다. 만료·미지·폐기된 토큰도 401. 교체해도 최초 로그인 +30일의 절대 만료를
  유지한다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RefreshRequest'
      example:
        refreshToken: contract-example-not-a-refresh-credential
responses:
  '200':
    description: 새 access와 교체 refresh. 예제는 실제 인증에 사용할 수 없다.
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/TokenEnvelope'
        examples:
          rotated:
            value:
              success: true
              data:
                accessToken: contract-example-not-a-signed-jwt
                tokenType: Bearer
                expiresIn: 900
                refreshToken: contract-example-not-a-refresh-credential
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/auth/logout

현재 family refresh 로그아웃

Access 없이 refresh JSON으로 호출한다. 해당 family의 refresh만 폐기하고 기존 access는 만료까지 허용한다. 미지·이미 만료·폐기된 토큰도 같은 204로 처리한다. 알려진 refresh 재사용 보안 폐기와 구분한다.

**인증:** `[]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| refreshToken | string | 필수 | 공백만 있는 값 금지. opaque credential이며 실제 값은 문서·로그에 기록하지 않는다. | {&quot;minLength&quot;: 1, &quot;maxLength&quot;: 512, &quot;writeOnly&quot;: true} |

```json
{
  "refreshToken": "contract-example-not-a-refresh-credential"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 멱등 처리 완료. 응답 본문 없음. |
| 400 | 요청 값·형식 오류 |
| 500 | 서버 내부 오류 |
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
tags:
- Member
summary: 현재 family refresh 로그아웃
operationId: logoutMemberSession
security: []
description: Access 없이 refresh JSON으로 호출한다. 해당 family의 refresh만 폐기하고 기존 access는 만료까지
  허용한다. 미지·이미 만료·폐기된 토큰도 같은 204로 처리한다. 알려진 refresh 재사용 보안 폐기와 구분한다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/RefreshRequest'
      example:
        refreshToken: contract-example-not-a-refresh-credential
responses:
  '204':
    description: 멱등 처리 완료. 응답 본문 없음.
  '400':
    $ref: '#/components/responses/BadRequest'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/seller/members/{id}/sessions/revoke

판매자 본인의 모든 기존 세션 폐기

SELLER 본인의 모든 기존 family를 보안 폐기한다. 다른 회원 id는 403이며 존재 여부를 노출하지 않는다. 이후 새 로그인은 가능하다.

**인증:** `[{"BearerAuth": []}]`
**태그:** Member

### 요청 파라미터

| 이름 | 위치 | 필수 | 타입 | 설명 |
|---|---|---|---|---|
| id | path | 필수 | string |  |

### 응답

| 상태 코드 | 설명 |
|---|---|
| 204 | 폐기 완료. 응답 본문 없음. |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 인증된 caller 또는 회원의 역할이 이 작업에 허용되지 않음. |
| 404 | 대상 회원 레코드가 없음 |
| 500 | 서버 내부 오류 |
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
tags:
- Member
summary: 판매자 본인의 모든 기존 세션 폐기
operationId: revokeMemberSessions
x-required-roles:
- SELLER
description: SELLER 본인의 모든 기존 family를 보안 폐기한다. 다른 회원 id는 403이며 존재 여부를 노출하지 않는다. 이후
  새 로그인은 가능하다.
parameters:
- name: id
  in: path
  required: true
  schema:
    type: string
    format: uuid
responses:
  '204':
    description: 폐기 완료. 응답 본문 없음.
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '404':
    $ref: '#/components/responses/NotFound'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
```

## POST /v1/internal/auth/sessions/check

내부 세션 활성 상태 확인

Shopping/Commerce/Live/Notification caller만 허용한다. memberId와 sessionId가 일치하고 회원·세션이 유효하면 active=true. 미지·불일치·보안 폐기·탈퇴는 false. 일반 로그아웃은 기존 access를 즉시 차단하지 않는다. 이 응답은 캐시하지 않는다.

**인증:** `[{"ServiceToken": []}]`
**태그:** Member

### 요청 본문

본문: 필수

**application/json**

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| memberId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |
| sessionId | string | 필수 |  | {&quot;format&quot;: &quot;uuid&quot;} |

```json
{
  "memberId": "11111111-1111-4111-8111-111111111111",
  "sessionId": "22222222-2222-4222-8222-222222222222"
}
```

### 응답

| 상태 코드 | 설명 |
|---|---|
| 200 | 권위 저장소에서 확인한 세션 상태 |
| 400 | 요청 값·형식 오류 |
| 401 | 인증 누락·위조·만료 토큰. 사용자 JWT와 서비스 토큰은 대체되지 않는다. |
| 403 | 인증된 caller 또는 회원의 역할이 이 작업에 허용되지 않음. |
| 500 | 서버 내부 오류 |
| 503 | JWT 세션 상태를 확인할 Member 또는 회원 저장소가 일시적으로 불가하여 요청을 허용하지 않음. |

#### 200 · application/json

| 필드 | 타입 | 필수 | 설명 | 제약 |
|---|---|---|---|---|
| success | boolean | 필수 |  | — |
| data | object | 필수 |  | — |
| data.active | boolean | 필수 |  | — |
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
tags:
- Member
summary: 내부 세션 활성 상태 확인
operationId: checkMemberSession
security:
- ServiceToken: []
description: Shopping/Commerce/Live/Notification caller만 허용한다. memberId와 sessionId가
  일치하고 회원·세션이 유효하면 active=true. 미지·불일치·보안 폐기·탈퇴는 false. 일반 로그아웃은 기존 access를 즉시 차단하지
  않는다. 이 응답은 캐시하지 않는다.
requestBody:
  required: true
  content:
    application/json:
      schema:
        $ref: '#/components/schemas/SessionCheckRequest'
      example:
        memberId: 11111111-1111-4111-8111-111111111111
        sessionId: 22222222-2222-4222-8222-222222222222
responses:
  '200':
    description: 권위 저장소에서 확인한 세션 상태
    content:
      application/json:
        schema:
          $ref: '#/components/schemas/SessionStatusEnvelope'
        examples:
          active:
            value:
              success: true
              data:
                active: true
              error: null
          inactive:
            value:
              success: true
              data:
                active: false
              error: null
  '400':
    $ref: '#/components/responses/BadRequest'
  '401':
    $ref: '#/components/responses/Unauthorized'
  '403':
    $ref: '#/components/responses/Forbidden'
  '500':
    $ref: '#/components/responses/InternalError'
  '503':
    $ref: '#/components/responses/AuthUnavailable'
경로 공통 선언: {}
적용 서버:
- url: http://localhost:8081
  description: Member Service API 로컬 직접 호출
- url: http://dev-member-service:8080
  description: dev 네임스페이스 내부 Service 주소
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

### member-service.yaml

```yaml
components:
  schemas:
    SignupRequest:
      type: object
      additionalProperties: false
      required:
      - email
      - password
      - displayName
      properties:
        email:
          type: string
          format: email
          maxLength: 254
        password:
          type: string
          format: password
          minLength: 8
          maxLength: 72
          writeOnly: true
          description: UTF-8 72바이트 이하도 검사. 로그·응답에 반환하지 않는다.
        displayName:
          type: string
          minLength: 1
          maxLength: 80
      description: email/displayName 앞뒤 공백 제거, email 소문자 정규화. 역할 입력으로 SELLER를 부여할
        수 없다.
    MemberEnvelope:
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
          $ref: '#/components/schemas/MemberProfile'
        error:
          type: 'null'
    MemberProfile:
      type: object
      required:
      - memberId
      - email
      - displayName
      - roles
      properties:
        memberId:
          type: string
          format: uuid
        email:
          type: string
          format: email
        displayName:
          type: string
        roles:
          type: array
          uniqueItems: true
          items:
            type: string
            enum:
            - USER
            - SELLER
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
    LoginRequest:
      type: object
      additionalProperties: false
      required:
      - email
      - password
      properties:
        email:
          type: string
          format: email
          maxLength: 254
        password:
          type: string
          format: password
          minLength: 1
          maxLength: 72
          writeOnly: true
          description: UTF-8 72바이트 이하. 공백만 있는 값 금지.
    TokenEnvelope:
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
          $ref: '#/components/schemas/TokenPair'
        error:
          type: 'null'
    TokenPair:
      type: object
      required:
      - accessToken
      - tokenType
      - expiresIn
      - refreshToken
      properties:
        accessToken:
          type: string
          minLength: 1
          readOnly: true
          description: RS256 JWT. canonical UUID sub/sid와 iss/aud/exp/iat/jti/roles를
            포함한다. 실제 토큰을 예시에 저장하지 않는다.
        tokenType:
          type: string
          const: Bearer
        expiresIn:
          type: integer
          format: int64
          minimum: 1
          description: access 잔여 수명 초수. 승인 운영 정책은 900초이며 JWT exp-iat와 같다.
        refreshToken:
          type: string
          minLength: 1
          readOnly: true
          description: 회전하는 opaque credential. DB에는 해시만 저장하며 family 절대 만료는 최초 로그인
            +30일에서 연장되지 않는다.
    ProfileUpdateRequest:
      type: object
      additionalProperties: false
      required:
      - displayName
      properties:
        displayName:
          type: string
          minLength: 1
          maxLength: 80
    WithdrawRequest:
      type: object
      additionalProperties: false
      required:
      - password
      properties:
        password:
          type: string
          format: password
          minLength: 1
          maxLength: 72
          writeOnly: true
          description: 공백만 있는 값 금지. UTF-8 72바이트 이하도 검사한다.
    RefreshRequest:
      type: object
      additionalProperties: false
      required:
      - refreshToken
      properties:
        refreshToken:
          type: string
          minLength: 1
          maxLength: 512
          writeOnly: true
          description: 공백만 있는 값 금지. opaque credential이며 실제 값은 문서·로그에 기록하지 않는다.
    SessionCheckRequest:
      type: object
      additionalProperties: false
      required:
      - memberId
      - sessionId
      properties:
        memberId:
          type: string
          format: uuid
        sessionId:
          type: string
          format: uuid
    SessionStatusEnvelope:
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
          - active
          properties:
            active:
              type: boolean
        error:
          type: 'null'
  responses:
    BadRequest:
      description: 요청 값·형식 오류
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
    Conflict:
      description: 정규화한 이메일 중복 (동시 가입 DB unique 충돌 포함)
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorEnvelope'
          examples:
            duplicateEmail:
              value:
                success: false
                data: null
                error:
                  code: CONFLICT
                  message: 이미 사용 중인 이메일입니다.
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
    NotFound:
      description: 대상 회원 레코드가 없음
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
    Forbidden:
      description: 인증된 caller 또는 회원의 역할이 이 작업에 허용되지 않음.
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
  securitySchemes:
    BearerAuth:
      $ref: ./common.yaml#/components/securitySchemes/BearerAuth
    ServiceToken:
      type: apiKey
      in: header
      name: X-Service-Token
      description: Shopping/Commerce/Live/Notification 방향별 opaque caller 자격증명. 사용자·SELLER
        JWT와 대체할 수 없다.
```

입력 SHA-256: `25b3d4125cd6f795f4a11301e3e49bf63b6f5e6fce629d93e4661f30c0e9bd13`
