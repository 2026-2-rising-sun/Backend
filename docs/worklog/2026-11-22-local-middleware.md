# 2026-11-22 로컬 미들웨어 통일

## 범위와 기준

[Backend #388](https://github.com/2026-2-rising-sun/Backend/issues/388)을 재사용하고 [Infra #62](https://github.com/2026-2-rising-sun/Infra/issues/62)를 생성했다. Infra를 공통 실행 기준으로 사용하고 Backend의 중복 Compose·DB 초기화 SQL을 제거한다. 사용자가 Infra 변경과 Payment 로컬 설정·문서 변경을 명시적으로 허용했다. 서비스 업무 로직·API·이벤트 계약은 변경하지 않는다.

기준은 Backend `9b807f91e02483c6ecf9b089ea42fa1f85b5f099`와 Infra `54d7161838d3520cf44b35c1cc2f8e1fbc9712e6`이다. 원본 Backend의 미커밋 변경을 보존하기 위해 각각 `refactor/#388-local-middleware`, `refactor/#62-local-middleware` 작업 트리에서 수행한다. 새 에이전트는 실행하지 않았다.

## 변경

- CommerceCore·Payment local 기본 연결값을 PostgreSQL5432·Kafka29092·shoppinglive 계정으로 맞췄다. 기존 명시 환경변수와 Payment `.env`가 우선하므로 전환 시 확인하도록 안내한다.
- Infra에서 업무 DB와 테스트·병렬 작업용 DB를 준비한다. 초기화 SQL은 기존 DB를 보존하고 빠진 이름만 생성하도록 변경했다.
- Infra `tests` 프로필의 Redis56379를 앱 Redis6379와 분리했다.
- Backend 실행 안내·도구·작업 절차·AGENTS와 [로컬 환경 MD·HTML](../design-docs/flows/local-environment.md)을 같은 기준으로 연결한다. 과거 실행 기록의 포트는 변경하지 않는다.
- 로컬 도구 제거가 전체 서비스 빌드를 선택하던 검사 분기를 로컬 도구 검사로 연결했다.

## 검증

### 미들웨어·설정

Infra Compose의 기본·tests·cdc·tools 구성을 해석하고 shell 구문을 확인했다. 기존 `local/cdc/test_config.py` 7건이 실패·오류·스킵 없이 통과했다. 토픽·CDC·기존 격리 Compose 및 Kubernetes overlay 구성이 유지됨을 확인하는 정적 검사다.

Infra Compose를 해석한 뒤 고정 컨테이너명·네트워크·볼륨명·호스트 포트만 독립 project로 바꿔 검증했다. Docker Desktop의 `shoppinglive-local-388`에서 PostgreSQL25462·Kafka49062·테스트 Redis56362를 사용했다.

- 새 볼륨에서 업무·이전 서비스·테스트·병렬 작업용 DB 18개가 생성됐다.
- 업무 DB에 표본 데이터를 넣고 테스트 DB 소유자를 바꾼 뒤, DB 하나를 제거하고 초기화를 2회 재실행했다. 표본 데이터·기존 소유자가 유지되고 누락 DB만 추가됐다. 잘못된 DB 이름은 실패로 거절했다.
- 테스트 Redis에만 `FLUSHDB`를 실행한 뒤 앱 Redis의 같은 이름 키가 보존됐다.
- `kafka-init`이 토픽 11개를 생성했고 전체 스택 `up -d --wait`도 통과했다. CDC connector는 이번에 실행·등록하지 않았다.
- Spring YAML 로더·환경 해석기로 Core·Payment 각각 local 기본값 4개와 명시 환경 재정의 4개를 확인했다. 실제 local 앱 기동 검증과는 구분한다.

### 초기 실패와 재검증 환경

초기 하네스 실행에서 Core 단위 188건은 통과했지만 통합 288건 중 외부 Kafka 2건이 AdminClient 토픽 생성 타임아웃으로 실패했다. 같은 시점 브로커의 내부 heartbeat 지연을 관찰했다. 이어진 PostgreSQL 회귀는 JVM이 응답을 기다리는 동안 서버 연결은 ClientRead 대기 상태여서 중단했다. PostgreSQL 회귀 완료·Payment 검사·직렬화 표본 검사는 이 실행에서 성공으로 집계하지 않는다. 공용 자원은 건드리지 않고 이 작업에서 시작한 검증 프로세스만 종료했다. 근본 원인은 확정하지 않았다.

별도 Lima Docker socket에서 새 project `shoppinglive-local-388-db`의 PostgreSQL25562와 `shoppinglive-local-388-kafka`의 Kafka49162를 준비했다. 기존 CDC·환불 검증 컨테이너는 재사용하지 않았다. 새 워크트리 경로는 Lima에 mount되지 않아 초기 `kafka-init`이 파일 누락으로 종료127을 반환했다. 동일 원본 스크립트·토픽 목록을 새 검증 컨테이너에 복사해 재실행했고 11개 토픽이 생성됐다. 호스트 Java의 JDBC 연결·커밋 10회와 Kafka metadata 조회를 확인한 뒤 회귀를 다시 시작했다.

초기 미들웨어 검증 준비 중 `DROP DATABASE`를 다른 SQL과 같은 요청에 넣어 트랜잭션 오류가 났다. 독립 요청으로 나눠 위 데이터·소유자 보존 검증을 다시 수행했다. 이 오류는 DB 초기화 스크립트의 실행 결과와 구분한다.

### 최종 Java 결과

Java 21, 새 격리 DB·브로커 주소와 테스트 계정을 명시해 아래 작업을 `--rerun-tasks --no-daemon --max-workers=2 --console=plain`으로 실행했다. 변경 대상 코드는 재검증 중 수정하지 않았다.

```sh
./gradlew :services:commercecore-service:build :services:payment-service:build \
  :services:commercecore-service:postgresTest :services:payment-service:postgresTest \
  --rerun-tasks --no-daemon --max-workers=2 --console=plain
```

| 서비스 | 작업 | 실행 | 실패 | 오류 | 스킵 |
|---|---|---|---|---|---|
| CommerceCore | test | 188 | 0 | 0 | 0 |
| CommerceCore | integrationTest | 288 | 0 | 0 | 0 |
| CommerceCore | postgresTest | 151 | 0 | 0 | 0 |
| Payment | test | 54 | 0 | 0 | 0 |
| Payment | integrationTest | 75 | 0 | 0 | 0 |
| Payment | postgresTest | 65 | 0 | 0 | 0 |

총 821건이며 Core 외부 Kafka 2건도 실제 브로커에서 실행됐다. build에 연결된 포맷 검사도 통과했다. 실패했던 초기 실행과 합산하지 않는다.

### 하네스·문서와 한계

검사 선택기 46건, 인증 도구 3건이 실패·오류·스킵 없이 통과했다. 이벤트 계약 9개·예시 27개·현재 코드 직렬화 표본 28개가 검사됐다. 표본 검사는 실제 서비스 간 전달 증거가 아니다.

문서 270개 오류0, API MD·HTML 10쌍과 설계·기록 MD·HTML 112쌍 불일치0, 최종 diff 공백 검사 통과를 확인했다. 대표 로컬 환경 HTML은 Orca 브라우저에서 1440px·390px, 목차 이동과 콘솔을 확인했다. 페이지 가로 넘침·콘솔 오류는 없었고 좁은 화면의 표는 내부에서 가로 이동한다. 문서 결과 기록·계획 이동 뒤에는 문서 검사만 다시 수행하고 Java 검증을 반복하지 않았다.

전체 실제 앱의 Core → CDC → Kafka → Catalog 종단, Live Redis 업무 회귀, 실제 토스·운영 환경은 이번에 실행하지 않았다. API·이벤트 계약과 업무 로직 변경은 없다. 공용 컨테이너·기존 볼륨·기본 Docker context는 변경하지 않고 이번 검증 project만 정리한다. 로컬 로그·XML 집계는 Git에서 제외되는 `build/local-middleware-validation/`에 보관한다.

## 게시와 병합

초기 로컬 완료 보고 때는 별도 작업 트리의 미커밋 변경이었다. 이후 사용자가 커밋·푸시·PR·병합까지 승인해 두 저장소 변경을 게시했다. 배포와 기존 개발 볼륨 전환은 수행하지 않는다.

Infra [PR #63](https://github.com/2026-2-rising-sun/Infra/pull/63)은 검토 head `dc9305143e1fdc1549040a1163f4779cf38b18c3`를 고정해 main에 squash 병합했다. 병합 커밋은 `854c23f1c0016a07c2c0ef6b5372f26dce4326c4`이며 tree가 검토 head와 같음을 확인했다. 게시된 자동 검사는 라벨 2건으로 모두 통과했으며, 기능 검증은 위의 로컬 실행 결과다. 미해결 리뷰 스레드는 없었다.

Backend [PR #397](https://github.com/2026-2-rising-sun/Backend/pull/397)의 초기 head는 `95697267390510cdeff89a91a567364f0fd8652c`, base는 dev `9b807f91e02483c6ecf9b089ea42fa1f85b5f099`다. 게시한 제목·본문 전체를 다시 읽고 변경 전후, Infra 선행 적용, 기존 환경변수 우선순위, 로컬 검증과 미실행 범위가 구분되는지 확인했다. PR 본문의 워크로그 링크는 커밋에 고정한 GitHub 링크로 연결한다. 초기의 미커밋·공유 브랜치 반영 전 설명은 이 절과 완료 계획에서 과거 시점으로 정정했다.

이후 문서만 갱신하며 Java 소스·설정은 위 821건 검증 결과를 유지한다. Backend의 최종 head·CI·리뷰·병합 커밋은 [PR #397](https://github.com/2026-2-rising-sun/Backend/pull/397)의 게시 결과를 기준으로 확인한다. [완료 계획](plans/completed/388-local-middleware/README.md)은 미들웨어·하네스 통일 범위이며 실제 앱 CDC 종단·운영 검증 완료를 뜻하지 않는다.
