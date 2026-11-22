# 로컬 미들웨어 통일 실행 계획

[Backend #388](https://github.com/2026-2-rising-sun/Backend/issues/388) · [Infra #62](https://github.com/2026-2-rising-sun/Infra/issues/62)

목표는 미들웨어 실행·DB 초기화 원본을 Infra로 모으고 Backend의 앱 설정·안내를 같은 환경에 연결하는 것이다. 서비스별 DB 경계와 기존 데이터를 유지한다. Payment 변경은 승인받은 local 설정·문서로 한정한다.

## 진행

- [x] 기준 커밋·기존 변경 확인, 이슈 연결, 별도 작업 트리 준비
- [x] Infra DB 초기화 재실행과 테스트 Redis 제공
- [x] Backend 중복 Compose 제거, local 기본값·MD·HTML·에이전트 안내 갱신
- [x] 격리 PostgreSQL·Redis·Kafka와 변경 대상 하네스 검증
- [x] 결과·한계를 기록하고 완료 조건 대조

로컬 구현·검증을 완료했다. 초기 환경 실패를 보존하고 새 환경 재검증 821건의 실패·오류·스킵0을 확인했다. 두 저장소 공유 브랜치 반영과 실제 앱 CDC 종단·운영 검증은 수행하지 않았다.

## 완료 조건

Backend 중복 미들웨어 선언이 제거되고, CommerceCore·Payment와 실행 안내가 Infra를 가리킨다. 새 볼륨 DB 생성과 기존 데이터·소유자 보존, 테스트 Redis 격리를 실제 확인한다. MD·HTML 최신성·링크와 검사 선택을 확인하고 미실행 범위를 기록한다. 실제 서비스 종단·운영 검증과 GitHub 병합은 별도다.

설정 구조는 [로컬 환경](../../../../design-docs/flows/local-environment.md), 실행 결과·저장 상태는 [작업 기록](../../../2026-11-22-local-middleware.md)에 둔다.
