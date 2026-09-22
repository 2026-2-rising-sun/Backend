# Backend

2026-하반기프로젝트-a팀 BE 레포지토리입니다.

라이브 커머스 서비스의 5개 도메인 서비스를 담은 Gradle 멀티모듈 모노레포입니다.

## 요구 환경

- JDK 21 (Temurin 권장)
- Gradle은 설치할 필요 없습니다. 항상 `./gradlew`를 사용하세요.

## 빌드 / 실행

```bash
./gradlew build                                   # 전체 빌드 + 테스트
./gradlew :services:member-service:bootRun        # 개별 서비스 기동
```

## 모듈 구조

| 모듈 | 설명 | 포트 |
|---|---|---|
| `services/member-service` | 회원 | 8081 |
| `services/shopping-service` | 상품/장바구니 | 8082 |
| `services/commerce-service` | 주문/결제 | 8083 |
| `services/live-service` | 라이브 방송 | 8084 |
| `services/notification-service` | 알림 (이벤트 소비 전용) | 8085 |
| `libs/common-core` | 공통 예외, `ApiResponse` | |
| `libs/common-web` | `GlobalExceptionHandler`, correlation-id(`X-Request-Id`) 필터 | |
| `libs/common-security` | 토큰 검증 인터페이스 | |
| `libs/common-persistence` | `BaseEntity`, JPA Auditing | |
| `libs/common-kafka` | Kafka producer/consumer 공통 설정 | |
| `libs/common-resilience` | Resilience4j circuit breaker / retry / timeout 기본값 | |
| `contracts/events` | 서비스 간 공유 이벤트 타입 (순수 Java) | |
| `contracts/api` | OpenAPI 문서 (Gradle 모듈 아님) | |
| `build-logic` | Gradle 컨벤션 플러그인 (composite build) | |

## API 경로 규칙

컨트롤러 경로는 `/v1/...`부터 시작합니다. **`/api/<service>` prefix를 붙이지 마세요.**

Infra의 k8s Ingress(nginx `rewrite-target`)가 prefix를 벗겨낸 뒤 넘기기 때문입니다.
외부 요청 `/api/member/v1/users/1` → member-service가 실제로 받는 경로는 `/v1/users/1`입니다.
같은 이유로 `server.servlet.context-path`도 설정하지 않습니다.

## 모듈 경계 규칙

서비스는 다른 서비스 모듈을 `project()`로 의존할 수 없습니다. `:contracts:events`와 `:libs:common-*`만
참조 가능하며, 위반 시 `shoppinglive.module-boundary-conventions` 플러그인이 빌드를 실패시킵니다.

서비스 간 통신은 Kafka 이벤트(비동기) 또는 REST(동기, `libs/common-resilience` 적용)로만 합니다.
기능·API·ERD·팀 컨벤션·설계 결정은 [팀 문서 시작 페이지](https://app.notion.com/p/3e3226545d158160b249d823be7085e2)에서 관리합니다.
공유용 Markdown은 저장소에 추가하지 않으며, 실행 가능한 OpenAPI YAML과 이벤트 계약은 Git으로 관리합니다.
