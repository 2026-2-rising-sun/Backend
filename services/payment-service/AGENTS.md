# Payment 작업 주의점

스펙은 [결제](../../docs/requirements/payment.md)·[원장](../../docs/requirements/ledger.md)·[환불](../../docs/requirements/refund.md), 할 일은 [실행 계획](../../docs/exec-plans/active/337-payment-redesign/README.md)이 연결한 단계 문서에서 해당 PR 블록만 읽는다. 테스트 `@DisplayName`에 요구사항 ID를 넣는다.

- 루트 패키지의 기존 결제 코드는 교체 대상이다. 패턴을 따라 하지 않는다. (기존 코드 삭제 PR이 병합되면 이 줄을 지운다)
- 토스 호출은 DB 트랜잭션 밖에서 한다. 호출 전에 APPROVING과 pg_call을 먼저 커밋한다.
- 상태 변경은 이전 상태 조건부 UPDATE다. 0행이면 다시 읽어 판단한다.
- 중복 감지는 `ON CONFLICT DO NOTHING` + 영향 행 수로 한다. UNIQUE 예외를 잡지 않는다(PostgreSQL 트랜잭션이 중단된다).
- 애매한 PG 실패는 UNKNOWN이다. 실패나 재결제로 바꾸지 않는다.

줄은 같은 실수가 두 번 나올 때만 추가하고, 테스트·CI로 근본 원인을 막으면 지운다. 10줄을 넘기지 않는다.
