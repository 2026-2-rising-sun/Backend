# 로컬 실행 환경

로컬 미들웨어의 실행 원본은 [Infra `local/`](https://github.com/2026-2-rising-sun/Infra/blob/main/local/README.md)이다. Backend는 서비스 앱·인증 도구·테스트를 제공한다. 앱 실행 명령은 [로컬 실행 안내](../../../scripts/local/README.md), 외부 테스트의 선택 조건은 [도구 안내](../../guides/tooling.md)에 있다.

## 연결 구조

| 사용 주체 | 연결 대상 | 연결 조건 |
|---|---|---|
| Backend 서비스 앱 | Infra PostgreSQL | localhost:5432, 서비스별 업무 DB |
| Backend 이벤트 생산자·소비자 | Infra Kafka | localhost:29092, 같은 브로커 사용 |
| Live 앱 | Infra Redis | localhost:6379 |
| Payment local 앱 | Infra 가짜 토스 | localhost:59080, 실제 PG 호출 검증 아님 |
| Core Outbox | Infra CDC Connect | 별도 cdc 프로필·DB 준비·connector 등록 필요 |
| Infra CDC Connect | Infra Kafka | 컨테이너 주소 kafka:9092 |
| PostgreSQL 회귀 테스트 | Infra PostgreSQL | 업무 DB와 다른 *_test DB 지정 |
| Redis 회귀 테스트 | Infra 테스트 Redis | localhost:56379, tests 프로필 |

Compose를 하나로 관리해도 서비스 DB는 `member`, `catalog`, `commercecore`, `payment`, `live`, `notification`으로 분리한다. PostgreSQL 인스턴스를 함께 사용하는 로컬 구성이 서비스 간 테이블 접근이나 교차 DB 조인을 허용하는 것은 아니다. 계정·비밀번호와 사용자 지정 포트는 Infra `local/.env`에 맞춰 앱 실행 환경에도 전달한다.

## 테스트 격리

테스트 DB 목록·초기화 스크립트와 테스트 Redis는 Infra가 관리한다. PostgreSQL 회귀는 테스트 전용 DB를 초기화할 수 있으므로 업무 DB 주소를 넘기지 않는다. 같은 DB를 쓰는 테스트 실행을 동시에 시작하지 않고, 병렬 작업에는 별도 테스트 DB를 지정한다.

Kafka 외부 회귀는 업무 토픽에 메시지를 보낼 수 있다. 공용 로컬 앱이 사용하는 브로커 대신 Infra 구성을 바탕으로 준비한 독립 project·브로커를 지정한다. [CDC 독립 검증](https://github.com/2026-2-rising-sun/Infra/blob/main/local/cdc/README.md)의 테스트용 DB와 fixture 브로커도 실제 서비스 종단 검증 환경과 구분한다. 환경변수로 테스트 주소를 지정한 사실만으로 실제 실행·전달 성공을 판단하지 않는다.

## 기존 Backend 격리 스택에서 전환

서비스 분리 PR #326은 기존 Kind·Infra 데이터에 영향을 주지 않으려고 Backend에 별도 미들웨어를 추가했다. #388에서 이 실행 구성을 제거하고 Infra로 통일한다. 과거 워크로그의 포트·검증 결과는 당시 환경의 기록으로 보존한다.

1. 기존 Backend 격리 스택에 연결된 앱과 테스트를 중지한다. 필요한 데이터와 접속 설정을 먼저 확인한다.
2. Infra의 최신 Compose·환경변수 예시를 기준으로 `local/.env`를 갱신한다. 기존 비밀번호·볼륨은 보존한다.
3. Infra DB 초기화 스크립트를 재실행해 빠진 DB만 추가한다. 기존 DB·소유자·테이블은 그대로 둔다.
4. 앱의 예전 DB URL·계정·Kafka·Redis 환경변수와 Payment `.env`를 Infra 연결값으로 바꾼다. local 프로필 기본값만 바꿔도 기존 명시 환경변수가 우선한다.
5. DB·Kafka 연결과 필요한 CDC 등록 상태를 확인한 후 앱과 대상 테스트를 실행한다.

이전 Backend 볼륨의 데이터는 자동 이전하지 않는다. 새 DB의 초기화 성공은 기존 업무 데이터의 이전·복원 성공이 아니다. 기존 볼륨을 삭제하거나 Kubernetes·운영 환경을 변경하는 작업은 이 전환에 포함하지 않는다.

## 관련 작업

[Backend #388](https://github.com/2026-2-rising-sun/Backend/issues/388) · [Infra #62](https://github.com/2026-2-rising-sun/Infra/issues/62) · [작업 기록](../../worklog/2026-11-22-local-middleware.md)
