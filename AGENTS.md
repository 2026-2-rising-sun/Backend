# Backend 작업 지도

AI 에이전트와 팀원이 같은 기준으로 조사·변경·검증·기록하기 위한 진입점이다. 프로젝트 경계는 [ARCHITECTURE.md](ARCHITECTURE.md), 문서 위치와 기록 기준은 [문서 지도](docs/README.md)에 있다.

## 필수 원칙

- 먼저 브랜치·기준 커밋·미커밋 변경을 확인하고 [동기화 절차](docs/guides/workflow.md#시작과-동기화)를 따른다. 기존 사용자 변경을 보존한다.
- 원인을 진단한 뒤 요청 범위에서 변경한다. 분석 요청을 구현으로 확대하거나 무관한 업무 정책·서비스 경계를 바꾸지 않는다.
- 설명·주석·테스트 설명·이슈·PR·커밋 설명은 한국어로 쓴다. 코드 식별자는 기존 도메인 용어와 이름 규칙을 따른다.
- 이해하기 쉬운 코드와 YAGNI를 우선한다. 트랜잭션·잠금·멱등성·실패 복구·계약의 의미를 형식적인 정리로 바꾸지 않는다.
- API 원본은 `contracts/api/`, 이벤트 원본은 `contracts/events/`다. 서비스 리소스에 수동 복사하지 않는다. 외부 계약 변경은 구현·소비자와 함께 검토한다.
- 구현과 문서가 다르면 근거를 확인해 함께 고친다. API·DB·상태 변경 시 관련 모듈 문서·HTML을 확인한다. 제안과 현재 구현, 코드 조사와 실제 실행 결과를 구분한다.
- 변경 후 필요한 검사를 실제 실행한다. 실패·스킵·0건·외부 환경 미실행·검사 중 diff 변경을 성공으로 보고하지 않는다.
- 실제 작업과 검증 결과는 [기록 기준](docs/README.md#기록-작성-조건)에 따라 남긴다. 중요한 결정은 ADR, 여러 단계의 작업은 실행 계획과 연결한다. 모든 작업에 세 문서를 새로 만들지 않는다.
- 커밋·푸시·PR·병합·배포는 사용자가 허용한 범위에서 수행한다. 허가된 작업에 같은 확인을 반복하지 않는다.

## 명령

API 검사 의존성을 설치한 Python 환경과 필요한 도구는 [도구 안내](docs/guides/tooling.md)를 따른다.

```bash
scripts/check.sh --dry-run              # 현재 변경의 검사 목록·선택 이유
scripts/check.sh --base origin/dev      # 기준 브랜치부터 현재 변경 검사
scripts/check-docs.sh                   # MD·HTML 링크와 요구사항 구조 검사
scripts/render-api-docs.sh              # 계약 YAML에서 API HTML 생성
scripts/render-api-docs.sh --check      # 생성 결과 최신성 확인
```

이 명령은 직접 실행하거나 CI에서 호출한다. 편집 후 자동 훅·명령 차단 가드·팀 공통 MCP는 연결하지 않았다.

## 필요한 문서만 읽기

| 작업 | 읽을 원본 |
|---|---|
| 작업 시작·동기화·검증·인계 | [작업 절차](docs/guides/workflow.md)의 해당 단계 |
| 코드 수정·리팩토링 | [코드 기준](docs/guides/code.md), 해당 [모듈 문서](docs/design-docs/services/README.md) |
| 테스트·검증 범위 결정 | [테스트 기준](docs/guides/tests.md), [도구](docs/guides/tooling.md)의 해당 환경 |
| API·이벤트 | [계약 기준](docs/guides/contracts.md), 해당 계약과 생산자·소비자 구현 |
| DB·상태·서비스 간 흐름 | [아키텍처](ARCHITECTURE.md), 해당 모듈 ERD·상태 설명과 [설계 문서](docs/design-docs/README.md) |
| 이슈·브랜치·커밋·PR | [작성 기준](docs/guides/writing.md), [템플릿](docs/templates/README.md) 중 작성할 종류 |
| 요구사항·결정·계획·기록 | [문서 지도](docs/README.md)의 작성·갱신·완료 기준 |
| 리뷰 | 변경 대상 규칙과 [리뷰 절차](docs/guides/workflow.md#리뷰와-전달) |
| Payment 서비스 | [Payment 작업 주의점](services/payment-service/AGENTS.md) |

링크를 모두 따라 읽지 않는다. 현재 작업의 관련 부분부터 읽고 이미 확인한 내용은 재사용한다. `rg --files`, `rg`로 관련 파일·심볼을 찾고 의존·호출·실패 근거에 따라 조사 범위를 넓힌다.

완료 보고에는 변경 결과·실제 검증·스킵/미실행/미확인·API/이벤트 계약 변경 여부·저장 상태를 적는다. 완료한 실행 계획은 결과와 후속 과제를 정리한 뒤 `completed/`로 옮긴다.
