# PR 136 dev 동기화와 CI 충돌 해결

## Spec
- 원본: 사용자 2026-10-06 요청 및 PR #136 / 이슈 #129. 대상 PR head 648323f54f086c38d245e4615558a72fe05ff85a, 병합 dev 42d6a8a9ec5e5ab6e80ccd60ae66a74091194ea2.
- 적용: 기존 채팅 내역 기능 유지 및 최신 팀 CI 정책 반영. 현재 Backend-live 최상단 checkout은 유지한다.
- AC-1: ci.yml은 dev 버전과 동일하며 Git 충돌이 없다.
- AC-2: 수동 postgres.yml의 live 최소 테스트 건수는 #136에 맞게 7이다.
- AC-3: 최신 dev와 채팅 변경이 함께 Live 테스트 및 PostgreSQL 검사에 통과한다.
- 하위 PR #137–142의 부모 동기화·머지·기능 수정은 이번 범위에 포함하지 않는다.

## Plan
- 별도 detached worktree에서 dev를 merge한다. 유일한 충돌 ci.yml은 dev 내용을 채택한다.
- postgres.yml의 live minimums만 4→7로 갱신한다. 자동 CI의 과거 skip 건수 조건은 복구하지 않는다.
- Live 테스트와 실제 PostgreSQL 검사를 순차 실행하고 YAML 문법·검증 결과를 확인한다.
- 별도 컨텍스트 독립 리뷰 뒤 merge commit을 #136 head 브랜치에 일반 push한다.

## 검증
- `LIVE_PG_TEST=1 LIVE_PG_PORT=55432 ./gradlew :services:live-service:test --rerun-tasks -q`: Live 171건, 실패·오류·skip 0. PostgresMigrationTest 7건 포함.
- check_test_results.inspect_reports로 suite 존재·최소7·실패/skip 없음 확인: 통과.
- 모든 .github/workflows YAML 파싱(duplicate keys 검사 포함): 통과.
- ci.yml 원문과 origin/dev blob byte 비교: 일치.
- git diff --cached --check: 통과. 미해결 충돌 파일 없음.
- 현재 dev 대비 워크플로 차이는 postgres.yml의 최소 건수 한 줄뿐이다.
- 원격 PR CI·충돌 상태는 push 뒤 확인한다.


## 독립 리뷰
- 별도 컨텍스트 review-agent가 HEAD648323f/MERGE_HEAD42d6a8a 및 staged diff를 읽었다.
- CI 동일성·최소7·SELLER 권한과 공개 chats 병합·채팅 YAML 유지 확인. actionable P1/P2 없음.
- 리뷰어는 테스트 실행·소스 수정·GitHub 게시를 하지 않았다.
