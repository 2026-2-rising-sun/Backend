# Backend 문서 지도

하네스는 AI 에이전트와 팀원이 같은 기준으로 조사·변경·검증·기록하는 작업 절차다. 시작은 [AGENTS.md](../AGENTS.md), 서비스 경계는 [ARCHITECTURE.md](../ARCHITECTURE.md)에서 확인한다.

## 어디에 작성하는가

| 내용 | 원본과 진입점 |
|---|---|
| 제품 목적·기능 범위 | [제품 명세](product-specs/shoppinglive.md), [공통 용어](product-specs/glossary.md) |
| 검증 가능한 수용 기준 | [하네스 요구사항](requirements/document-harness.md), [주문·결제](requirements/order-payment.md), [Catalog 동기화](requirements/catalog-sync.md), 결제 재설계 [결제](requirements/payment.md)·[원장](requirements/ledger.md)·[환불](requirements/refund.md) |
| 중요한 선택 이유 | [문서 하네스 ADR](adr/0001-document-harness.md), 결제 재설계 [ADR 0002–0006](exec-plans/active/337-payment-redesign/README.md) |
| 현재 설계·업무 흐름 | [설계 지도](design-docs/README.md), [모듈 문서](design-docs/services/README.md) |
| 작업 방식·품질 | [작업 절차](guides/workflow.md), [코드](guides/code.md), [테스트](guides/tests.md), [계약](guides/contracts.md), [도구](guides/tooling.md), [작성](guides/writing.md) |
| 진행 중·완료 계획 | [실행 계획 지도](exec-plans/README.md), [기술부채](exec-plans/tech-debt.md) |
| 실제 실행 결과 | [작업 기록](worklog/2026-11-13.md) |
| 실패·해결 근거 | [이관 중 링크 단절](design-docs/failures/330-stale-links.md) |
| 작성 양식 | [템플릿 지도](templates/README.md) |

전체 배치와 주석은 [구조 MD](directory-structure.md), 사람이 펼쳐 보는 자료는 [구조 HTML](directory-structure.html)에 있다.

## 원본과 형식

- MD·HTML·PR은 [쉽게 설명하는 기준](guides/writing.md#처음-읽는-사람도-이해할-수-있게-설명하기)을 함께 적용한다. 그림·영상·API 결과 등의 [실행 근거](guides/writing.md#시각-자료와-실행-근거-남기기)는 실제 확인 범위와 연결한다.
- MD는 AI 에이전트가 읽고 참조하기 쉽게 제목·표·링크로 정리한다. 사람도 읽을 수 있는 설명과 근거를 남긴다.
- HTML은 관계도·흐름·상태·비교를 시각적으로 보여 준다. 모든 MD의 HTML 복제본을 만들 필요는 없다.
- API 원본은 `contracts/api/`의 YAML이다. 모듈 `api.html`은 생성 결과이며 명세를 `api.md`에 중복 작성하지 않는다.
- ERD·상태 HTML은 같은 이름의 MD와 연결한다. 소스 변경 시 MD와 시각 자료를 함께 확인한다. DB ERD와 `requirements/`의 공학 요구사항 문서는 서로 다른 자료다.
- 코드·계약이 실제 구현의 원본이다. 문서에는 조사한 소스 경로·기준 리비전·변경 반영 여부·확인 범위를 남긴다. 불일치가 있으면 설명을 조용히 맞추지 말고 원인과 미확인 사항을 적는다.

## 기록 작성 조건

| 상황 | 필요한 기록 |
|---|---|
| 단순 질의응답 | 별도 기록 없음 |
| 작은 수정·의미 있는 조사·검증 | 일일 워크로그의 이슈별 항목에 결과·근거·남은 일 |
| 여러 단계·의존성·불확실성·인계 | `exec-plans/active/<이슈>-<주제>/README.md`에 목표·순서·상태·검증·자료 링크 |
| 지속적으로 영향을 주는 구조·기술·책임 결정 | 공통 ADR에 문제·대안·선택 이유·영향·결정 상태 |
| 해당 작업만의 비교·조사 | 작업 폴더의 `decision.md`, `research.md`, `tables.md` 중 내용이 필요한 파일 |
| 재발 방지 가치가 있는 실패 | `design-docs/failures/`에 증상·원인·해결·확인 범위 |

팀원과 AI 에이전트 모두 같은 기준을 적용한다. 지침을 읽는 것만으로 기록이 자동 생성되지는 않는다. 원시 대화·프롬프트 전체보다 다음 판단과 재현에 필요한 근거를 남긴다. 공통 ADR과 날짜별 워크로그를 원본으로 두고 작업 폴더에서는 링크한다.

## 작성·갱신·완료

1. 현재 설명·제안·결정·실행 기록 중 역할을 정하고 기존 자료를 먼저 찾는다. 빈 폴더나 양식만 만들지 않는다.
2. 현재 설계는 코드와 함께 갱신한다. API는 재생성, DB는 마이그레이션·엔티티 대조, 상태는 조건·부수 효과·실패·테스트 대조를 한다.
3. 요구사항은 `상태: 초안/진행 중/완료/보류`, `검증:` 로컬 링크, 확인 가능한 수용 기준 체크박스를 둔다. 완료할 때 `검증 방식: 자동/수동/혼합`, `근거:` 워크로그 링크와 실제 결과를 연결한다.
4. ADR의 채택은 구현 완료와 다르다. ADR에는 결정 상태, 구현 진행은 실행 계획·요구사항·워크로그에 표시한다. 번복은 후속 ADR과 연결한다.
5. 작업 완료 후 계획 폴더를 `completed/`로 옮기고 링크를 갱신한다. 날짜별 기록은 과거 사실을 보존하며 정정 이유·후속 결과를 추가한다.
6. 로컬 검사·브라우저 검토·원격 CI·실제 DB/브로커 실행을 각각 기록한다. 생략·스킵·실패를 성공에 포함하지 않는다.

## 자동 검사와 사람의 검토

`scripts/check-docs.sh`는 Git 추적 대상 MD·HTML의 로컬 링크, 정적 앵커, HTML 자산, 요구사항 상태·참조 구조를 검사한다. fenced/inline 코드 예시·주석과 실행 `src/` 리소스는 제외한다. 기본 Markdown 인라인·참조 링크와 ATX 제목을 지원한다. 모든 CommonMark 확장, CSS URL, JavaScript가 만든 이동, 외부 사이트 가용성은 검사하지 않는다.

검증 파일명이나 링크 존재가 실행 성공을 증명하지 않는다. 수용 기준의 적절성, 문서와 코드의 의미 일치, HTML의 실제 화면은 별도로 검토한다. API HTML은 생성 결과 최신성 검사로 원본과 연결한다. 훅·가드·MCP는 현재 연결하지 않았다.
