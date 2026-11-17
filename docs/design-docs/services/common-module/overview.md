# common-module

공통 응답·오류, 인증/세션 확인, 요청 추적·관측, JPA 감사와 Outbox 구현을 제공한다. 업무 정책과 서비스별 데이터 소유권은 각 서비스에 있다.

- [Outbox 설명](outbox.md) · [발행 흐름](outbox.html)
- [공통 구현](../../../../common-module/src/main/java/com/shoppinglive/common)
- [모듈 등록과 의존 방향](../../../../ARCHITECTURE.md)

독립 HTTP API·DB가 없어 서비스용 api.html·erd.html을 형식적으로 만들지 않는다. Outbox 테이블은 사용하는 서비스의 ERD에서 확인한다. 공통 코드 변경은 소비 서비스의 검사 영향을 포함한다.
