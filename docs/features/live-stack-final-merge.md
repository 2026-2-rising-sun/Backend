# Live 스택 최종 병합

## 명세와 계획
- 원본: 사용자 2026-10-06 최종 Merge 요청, 이슈 #129–135 완료 조건.
- 적용: P2 Live 채팅·좋아요·SSE, PR #136–142.
- 기준: dev 9d66b38, 최종 스택 1a5221a.
- 순서: dev CI 정책을 각 스택에 전파, PG 최소 테스트 수 유지, 최종 코드 검증·독립 리뷰, #137→142 순차 dev 병합.
- 기존 checkout은 유지하며 별도 detached worktree를 사용한다.

## 구현
- ci.yml은 dev 정책을 유지한다.
- postgres.yml Live 최소 테스트 수는 11이다.
- live-verification.yml은 기존 스택의 실제 Redis·PG, 계약·예제, 두 서버 HTTP 통합, Quality gate를 보존한다. Live 관련 변경에만 자동 실행한다.
- 기존 최종 SSE·좋아요 Lua·채팅 계약 수정을 보존한다.

## 검증과 리뷰
- 최종 병합 후보에서 실제 PostgreSQL·Redis Live 테스트와 CI를 확인한다.
- 독립 리뷰의 결론 및 실행 결과는 확인 후 기록한다.
- 컨테이너 정지·재시작 및 로컬 전체 통합 실행은 하지 않는다. 전체 HTTP 통합은 CI로 검증한다.

- 실제 Redis·PG Live 테스트: 217건, 실패·오류·skip 0.
- 독립 리뷰: SELLER 통합 검증 토큰 누락을 수정 후 재검토, 잔여 지적 없음.
- 신규 workflow 원격 실행으로 계약·두 서버 검증 및 Quality gate를 확인한다.
