# 0002. contracts/events 스키마 호환성 정책

Date: 2026-09-15

## Status

Accepted

## Context

`contracts/events`는 5개 서비스가 **컴파일 타임에 공유하는 유일한 도메인 타입 모듈**이다.
[ADR-0001](0001-record-architecture-decisions.md)에서 서비스 간 직접 의존을 금지했지만, 이 모듈만은
모두가 참조한다. 여기에 숨은 결합이 있다.

예를 들어 `OrderPlacedEvent`의 필드 하나를 고치면, 모노레포 특성상 모든 서비스가 새 클래스로 재컴파일된다.
빌드는 통과한다. 하지만 런타임에는 그렇지 않다. Kafka 토픽에는 **구버전 producer가 이미 발행해 둔 메시지**와
**구버전 consumer가 아직 읽고 있는 메시지**가 함께 존재한다. 배포가 서비스별로 순차 진행되는 한,
"모든 서비스가 동시에 새 스키마로 넘어가는 순간"은 존재하지 않는다.

즉 공유 모듈을 그냥 고치면 "동시 재배포"가 암묵적 전제가 되고, 이는 서비스를 나눈 이유(독립 배포)를 무너뜨린다.

## Decision

`contracts/events`의 기존 이벤트 타입에는 **하위 호환 변경만 허용한다.**

허용 (하위 호환):
- 선택적(optional) 필드 **추가**. consumer는 모르는 필드를 무시할 수 있어야 한다
  (Jackson 기준 `FAIL_ON_UNKNOWN_PROPERTIES=false`).
- 주석/문서 추가, 필드 순서와 무관한 변경.

금지 (breaking):
- 필드 **삭제**, **이름 변경**, **타입 변경**
- 필드의 의미(semantics) 변경 — 이름이 같아도 뜻이 달라지면 breaking이다
- optional → required 변경

breaking change가 필요하면 기존 클래스를 고치지 않고 **새 버전 클래스를 추가한다.**

- 클래스: `OrderPlacedEvent` → `OrderPlacedEventV2` (기존 클래스는 그대로 둔다)
- 토픽: `commerce.order-placed.v1` → `commerce.order-placed.v2`
  (`libs/common-kafka`의 `KafkaTopics` 상수에 토픽 버전이 들어 있다)
- 전환 절차: producer가 v1·v2를 한동안 **동시 발행** → 모든 consumer가 v2로 이전 → v1 발행 중단 →
  v1 클래스·토픽 제거(별도 ADR 또는 PR로 기록).

## Consequences

- 이벤트 스키마 변경 때문에 여러 서비스를 같은 시점에 배포해야 하는 상황이 사라진다. 각 서비스는 자기 속도로
  v2로 넘어가면 된다.
- 대신 전환 기간 동안 v1/v2 클래스와 토픽이 공존해 일시적으로 코드가 늘어난다. 이 중복은 "언제 v1을 지울지"를
  PR 또는 ADR에 명시해 반드시 회수한다.
- 리뷰 체크포인트: `contracts/events` 하위 파일이 **수정**된 diff는 자동으로 경계 대상이다. 리뷰어는
  "이게 필드 추가인가, 아니면 새 버전 클래스로 갔어야 하는 변경인가"를 먼저 확인한다.
- consumer는 모르는 필드를 무시하도록 역직렬화 설정을 유지해야 한다. 이 설정이 깨지면 위 규칙 전체가 무의미해진다.
