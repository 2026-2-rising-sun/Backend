# 0001. 아키텍처 결정 기록 시작과 모듈 경계 강제

Date: 2026-09-15

## Status

Accepted

## Context

Backend는 5개 도메인 서비스(Member / Shopping / Commerce / Live / Notification)를 하나의 Gradle
멀티모듈 모노레포에 담는다. 레포를 나누지 않기로 한 이유는 초기 개발 속도와 리팩터링 비용 때문이지만,
하나의 레포에 있다는 이유만으로 서비스가 서로의 내부 클래스를 `project(":services:...")`로 당겨 쓰기
시작하면 "분산 모놀리스"가 된다. 그렇게 되면 서비스를 나눈 이유(독립 배포, 장애 격리)가 사라지고,
나중에 특정 서비스를 별도 레포/별도 배포 단위로 떼어내는 것도 불가능해진다.

특히 Notification 서비스는 설계상 "구매 성공에 동기적으로 의존하지 않는" 이벤트 소비 전용 서비스라,
결합이 생기면 안 된다는 요구가 가장 강하다.

또한 이런 구조적 결정이 대화나 PR 코멘트에만 남으면 몇 달 뒤 배경을 알 수 없게 된다.

## Decision

1. 아키텍처 결정은 `docs/decisions/`에 Michael Nygard 포맷 ADR로 기록한다. 번호는 순차 증가하며,
   기존 ADR은 수정하지 않고 새 ADR로 대체(Superseded)한다.
2. 서비스 간 컴파일 타임 의존은 **빌드 단계에서 금지**한다. `build-logic`의
   `shoppinglive.module-boundary-conventions` 플러그인이 각 서비스 프로젝트의 모든 configuration을
   검사해 `ProjectDependency`가 아래 화이트리스트 밖이면 빌드를 실패시킨다.
   - `:contracts:events`
   - `:libs:common-*`
3. 서비스 간 통신은 두 가지만 허용한다.
   - 비동기: Kafka 이벤트 (`contracts/events`의 타입)
   - 동기: REST 호출. 이때 `libs/common-resilience`의 circuit breaker / retry / timeout 기본값을
     적용해 downstream 지연이 caller를 무너뜨리지 않게 한다.

## Consequences

- 서비스 A가 서비스 B의 클래스를 재사용하고 싶으면 선택지는 세 가지뿐이다: 이벤트로 받기, REST로 호출하기,
  또는 범용 로직이라면 `libs/common-*`로 승격하기. 빌드가 강제하므로 리뷰어의 기억력에 의존하지 않는다.
- 규칙 위반 시 configuration 단계에서 즉시 실패하므로 피드백이 빠르다. 대신 정당한 예외를 두려면
  화이트리스트를 고쳐야 하고, 그 변경은 PR에 드러나 반드시 논의 대상이 된다.
- 결합도가 실제로 낮게 유지되면 Notification 등 일부 서비스를 별도 레포로 분리하는 비용이 낮아진다.
- 공유 지점이 `contracts/events`와 `libs/common-*`로 좁혀지는 대신, 이 둘의 변경은 여러 서비스에 동시에
  영향을 준다. `contracts/events`의 변경 규칙은 [ADR-0002](0002-event-schema-compatibility-policy.md) 참고.
