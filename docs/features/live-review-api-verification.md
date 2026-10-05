# Live PR 136–142 리뷰 후 실제 API 검증

## Spec
- 원본: GitHub 이슈 #129–135 작업 내용·완료 조건, PR #136–142 본문과 contracts/api/live-service.yaml.
- 적용 단계: P2 채팅 내역·작성·SSE·Redis 전달·좋아요·종료·두 인스턴스 검증.
- 확인 버전: 시작 HEAD cc4729d909dbb1e0fc394b313fb6d07655d05414. 사용자 2026-10-05 추가 요청에 따라 필요한 수정·커밋·push 및 실제 HTTP 호출을 수행한다.
- DB row 잠금 없음, 종료와 동시 요청 저장 허용, Redis 장애 중 전달 미보장, pod별 중복 보관 허용을 유지한다.
- AC-1: 느린 SSE 연결 큐 초과 시 발행 호출은 반환하고 registry에서 제거된다. writer 전송 종료 뒤 emitter를 한 번 완료한다.
- AC-2: SSE 연결 전 DB 오류도 Accept: text/event-stream에 JSON 500 오류 봉투를 반환한다.
- AC-3: 좋아요 초기화·증가·만료는 원자 실행된다. 기존 Redis 합계 유지, 없는 값은 DB 보관본에서 시작하며 만료 7일을 유지한다.
- AC-4: 두 실제 live-service 프로세스에서 채팅·좋아요·방송 종료 API 및 인증·입력·상태·장애 경계를 검증한다. 기존 API 회귀 흐름도 직접 호출한다.

## Plan
1. BroadcastConnection의 전송 중 완료를 drain 종료에 맡기고 완료 중복을 방지한다.
2. StreamController의 예상하지 못한 연결 전 오류에 JSON Content-Type을 지정한다.
3. 기존 SET NX/INCR/EXPIRE를 단일 Redis Lua 실행으로 묶는다. 공개 계약 변경 없음.
4. 필요한 회귀 테스트, 기존 PostgreSQL·Redis 테스트를 한 번에 하나씩 실행한다.
5. 저장소 밖 실행 harness에서 기존 컨테이너를 유지하고 별도 PostgreSQL DB·전용 로컬 Redis 프로세스를 사용한다. integration/run.cjs는 실행하지 않는다.
6. Spec·diff·검증 결과를 새 컨텍스트 독립 리뷰로 대조한 뒤 현재 최상단 브랜치에 수정 커밋을 push한다.

## 검증
- 대상: 위 시작 HEAD + 본 문서 및 git diff의 수정(미커밋). 기존 공개 성공 응답·권한·상태 전이 계약은 유지한다.
- 전체: `LIVE_REDIS_TEST=1 LIVE_PG_TEST=1 LIVE_PG_PORT=55432 ./gradlew :services:live-service:test --rerun-tasks -q`: 213건, 실패·오류·skip 0.
- 경합 회귀: `./gradlew :services:live-service:test --tests '*BroadcastConnectionTest' --tests '*StreamControllerTest' --rerun-tasks -q`: 순차 3회 통과(회당 6건). 실제 Spring writeLock을 사용하는 emitter, executor 거절 중 close 경합, shutdown 중 대기 drain 완료를 검사한다.
- `npm run check --prefix scripts/contracts`: 통과. `npm run smoke --prefix scripts/contracts`: Prism 예제 59건 통과. smoke는 자체 임시 Prism 컨테이너를 생성·정리했으며 기존 테스트·개발 컨테이너는 유지했다.
- 실제 API 첫 성공 실행: 저장소 밖 `/tmp/live-review-api.cjs`, JDK 21, 기존 PostgreSQL 컨테이너의 전용 DB 4개, 전용 host Redis, Member/Shopping/Commerce/Live/Live2 실제 jar 프로세스. 508개 검사·424개 기록된 HTTP 요청, 실패·skip 0. live 계약 operation 18개 모두 직접 호출, 누락 0. SSE 200 연결·DB 오류 500은 추가 직접 fetch로 검증한다.
- API 상세: 무토큰/변조 토큰, Member 장애 시 채팅 무변경·503, 공백·null·201/200 code point, 최신 50건 및 동시각 ID 순서, A→B chat.created, A/B 합계·likes.updated, 종료 이벤트·닫기·409, 동시 좋아요 50건·TTL, 만료 후 DB 복원 3회, Redis 장애 POST 503·GET DB값·채팅 201·readiness, Redis 없이 Live2 기동, DB 장애 SSE JSON INTERNAL_ERROR·readiness/liveness. 회원·상품·주문·세션 기존 실제 API 회귀 포함.
- 실패한 준비 실행은 harness의 bootstrap DB 사용자명, 검사 이름 중복, 재실행 Redis 상태 재사용 때문이었다. 전용 DB 설정·고유 검사명·전용 Redis 프로세스로 해소하고 전체 흐름을 재실행했다.
- 빌드와 테스트를 동시에 실행했을 때 컴파일 산출물 경합으로 클래스 누락이 발생했다. 이후 Gradle 작업을 순차 실행해 회귀 및 전체 테스트를 통과했다.
- Redis 복구 뒤 A→B 채팅·좋아요·종료 전달 재검증도 통과. 최종 실제 API 실행은 534검사 / 441개 기록된 HTTP 요청, live 18 operation 누락 0, 실패·skip 0. SSE 200 두 연결과 DB 오류 JSON 500 직접 fetch는 별도이다. 증거: `/tmp/live-review-api-1791180556500/summary.json`, A http://127.0.0.1:54828, B http://127.0.0.1:54830. integration/run.cjs 자체 및 ingress·부하 검사는 실행하지 않았다.
- 기존 live-failures의 컨테이너 pause 대신 전용 DB의 ALLOW_CONNECTIONS/세션 종료를 사용해 실제 DB 장애를 주입했다. 종료 시 자신의 host 프로세스·전용 DB만 정리한다.


## 독립 리뷰
- 별도 컨텍스트 review-agent가 Spec·전체 diff·새 테스트를 읽었다.
- 첫 지적: draining=true인 대기 작업이 shutdownNow 또는 executor 거절 경합에서 완료되지 않을 수 있음(P2).
- 반영: close 재호출이 완료 책임을 회수하고 writers.shutdown으로 대기 drain을 실행한다. 두 경로 회귀 테스트 추가.
- 수정 및 새 테스트 포함 최종 재리뷰: 잔여 P1/P2 없음. 리뷰어는 테스트 실행·코드 수정을 하지 않았다.

## 인계
- 현재 브랜치: feat/#135-live-two-instance-integration, checkout 전환 없음.
- 수정 커밋은 스택 최상단에 추가한다. 이전 PR head를 rebase/force-push하지 않는다.
- 로컬 검증·독립 리뷰 완료. 이 문서와 코드 수정을 현재 최상단에 커밋·push한다. 원격 CI 결과는 push 이후 별도로 확인한다.
