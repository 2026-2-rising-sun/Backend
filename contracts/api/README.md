# contracts/api

서비스별 REST API 스펙을 OpenAPI 3.1 yaml 문서로 관리한다. 지금은 Gradle 모듈이 아니라 문서 폴더다.

- 파일명 규칙: `<service>-service.yaml` (예: `member-service.yaml`)
- spec-first 코드 생성(openapi-generator, openapi-typescript 등)은 스펙이 안정된 뒤에 도입한다.
- 비동기 통신(Kafka)의 계약은 여기가 아니라 `contracts/events` Gradle 모듈에 Java 타입으로 있다.
