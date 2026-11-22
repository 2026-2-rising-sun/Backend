# Backend 문서 지도

서비스 간 흐름, 서비스·모듈별 ERD와 API, 결정 이유, 실제 작업 결과를 찾는 진입점이다.

| 보고 싶은 것 | 위치 |
|---|---|
| 서비스 간 정상·예외·복구 흐름 | [흐름과 서비스 설계](design-docs/README.md) |
| 서비스·모듈별 ERD와 API | [서비스 목록](design-docs/README.md#서비스별-설계) |
| 중요한 결정과 대안 | [ADR 목록](adr/README.md) |
| 실제 변경·검증·남은 일 | [워크로그](worklog/README.md) |
| 작업·코드·테스트 기준 | [작업 절차](guides/workflow.md), [코드](guides/code.md), [테스트](guides/tests.md), [계약](guides/contracts.md), [도구](guides/tooling.md), [작성](guides/writing.md) |
| 로컬 미들웨어·앱·테스트 연결 | [로컬 실행 환경](design-docs/flows/local-environment.md), [실행 명령](../scripts/local/README.md) |

## 전체 구조

```text
docs/
├── README.md / README.html
├── design-docs/
│   ├── flows/                   # 서비스 간 호출·이벤트·실패·복구
│   └── services/
│       ├── commercecore/        # 전체 개요·ERD와 Product/SKU/Cart/Order/Coupon
│       ├── payment/             # 현재 구현과 재설계 목표를 구분
│       ├── catalog/
│       ├── member/
│       ├── live/
│       ├── notification/
│       └── common-module/
├── adr/                         # 결정과 이유
├── worklog/                     # 날짜별 실행 결과
│   ├── plans/active/            # 진행 중 목표·수용 기준·인계
│   ├── plans/completed/         # 완료한 계획
│   └── archive/                 # 과거 구축·실패 근거
├── guides/                      # 작업 지침
├── templates/                   # 작성 양식
└── assets/                      # 현재 문서에 쓰는 자산
```

## 원본과 형식

- 흐름·서비스 설계·ADR·워크로그는 주제마다 같은 이름의 MD·HTML 한 쌍을 둔다. 가이드와 작성 양식은 작업 지침이므로 MD를 사용한다.
- MD는 설명·조건·근거의 원본이며 HTML은 같은 내용을 관계도·목차·표로 제공한다. `scripts/render_design_docs.py`로 재생성한다.
- API는 `contracts/api/` YAML이 원본이다. `scripts/render-api-docs.sh`가 서비스·모듈별 `api.md`와 `api.html`을 함께 생성한다. 생성 파일을 직접 편집하지 않는다.
- 기존 Payment 목표 설계의 수작업 HTML은 원래 그림을 보존한다. 같은 이름의 MD와 함께 관리하고 내용 변경 시 두 파일을 대조한다. 생성기가 덮어쓰지 않는 예외는 MD에 표시한다.
- 전체 서비스 경계는 [아키텍처](../ARCHITECTURE.md), API·이벤트 계약 원본은 `contracts/`에서 관리한다. 단순 화면 확인 캡처는 저장소에 상시 보관하지 않는다.

## 기록 작성 조건

| 상황 | 작성할 내용 |
|---|---|
| 현재 구조·업무 규칙·상태·실패 처리 | 해당 흐름 또는 서비스·모듈 설계 |
| 지속적인 구조·기술·책임 결정 | ADR에 문제·대안·선택 이유·영향. 현재 설계는 링크 |
| 실제 변경·의미 있는 조사·검증 | 날짜별 워크로그에 변경·명령·환경·결과·실패·남은 일 |
| 여러 단계·의존성·인계가 있는 작업 | 워크로그 하위 실행 계획에 목표·범위·수용 기준·진행 상태 |
| 단순 질의응답 | 별도 기록 없음 |

같은 내용을 세 문서에 반복하지 않는다. 설계는 현재 동작, ADR은 선택 이유, 워크로그는 실제 실행 결과를 소유한다. 실행 계획·수용 기준이 남은 기존 자료는 경로를 옮겨 보존하며 기능 작업을 완료 처리하지 않는다.

## 작성·갱신·완료

1. 기존 주제의 원본을 찾아 수정한다. 새 주제가 있을 때만 MD·HTML 쌍을 추가한다.
2. 서비스 간 흐름은 정상 처리와 타임아웃·중복·역순·부분 실패·재시도·복구, 트랜잭션 경계를 확인한다. ERD는 DDL·엔티티와 관계·키·제약을 대조한다.
3. 서비스·모듈별 수용 기준은 설계의 `requirements/`에서 보존한다. 상태·검증·근거와 실제 수용 기준은 기존 검사 규칙을 따른다.
4. ADR 채택과 구현 완료를 구분한다. 실행 결과·실패·미실행은 워크로그에 남기고 현재 설계에서 반복하지 않는다.
5. 생성 후 `scripts/check-docs.sh`, API·설계 생성기의 `--check`를 실행하고 대표 HTML의 탐색·그림·좁은 화면을 확인한다.
6. 완료한 계획만 `worklog/plans/completed/`로 옮긴다. 미완료 범위와 과거 실행 근거는 보존한다.

## 자동 검사와 사람의 검토

문서 검사는 로컬 링크·앵커·요구사항 구조·MD/HTML 쌍과 생성 최신성을 확인한다. 검사 통과가 코드와의 의미 일치나 실제 DB·브로커·PG 실행을 증명하지는 않는다. 화면과 내용은 별도로 검토한다. 훅·가드·MCP는 현재 연결하지 않았다.
