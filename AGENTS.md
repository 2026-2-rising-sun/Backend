# Backend 작업 지침

상세 지침과 작성 템플릿의 원본은 [harness](harness/README.md)에 있다. 작업 시작 시 이 문서와 아래의 해당 문서를 읽는다.

## 필수 원칙

- 코드 변경 전 현재 브랜치·기준 커밋·미커밋 변경을 확인하고, [작업 절차](harness/workflow.md)에 따라 원격 변경을 pull한다. 기존 사용자 변경을 보존한다.
- 설명·주석·테스트 설명·이슈·PR·커밋 설명은 한국어로 작성한다. 식별자와 브랜치 slug 등은 [코드 기준](harness/rules/code.md)과 [협업 규칙](harness/rules/collaboration.md)을 따른다.
- 먼저 원인을 진단한다. 요청한 범위를 지키고, 분석 요청을 임의의 구현으로 확대하지 않는다.
- 이해하기 쉬운 코드와 YAGNI를 우선한다. 간결한 주석으로 이유와 제약을 설명하고, 무관한 구조·업무 정책 변경을 섞지 않는다.
- 테스트 추가·수정 시 검증 대상·한국어 설명·태그·실제 실행 경로를 확인한다. 태그만으로 외부 환경 검증을 완료했다고 판단하지 않는다.
- API 변경에는 [계약 규칙](harness/rules/contracts.md)의 전체 절차를 적용한다. 원본은 `contracts/api/<서비스명>.yaml`이며 서비스 리소스에 수동 복사하지 않는다.
- 이슈·커밋·PR 작성 전 해당 템플릿을 읽는다. 문제·결과·중요한 판단·실제 검증을 간략하게 적는다.
- 완료 보고에 변경 내용, 검증 결과, 스킵·미실행·미확인 사항, API·이벤트 계약 변경 여부를 명시한다.

## 필요한 문서만 읽기

- 이 문서에서 작업을 분류하고 아래 표의 해당 규칙만 읽는다. 여러 유형에 해당하면 필요한 규칙을 함께 적용한다. 링크가 있다는 이유로 연결 문서 전체를 따라 읽지 않는다. `harness/README.md`는 목록이 필요할 때만 연다.
- 이미 읽은 내용이 현재 맥락에 있고 변경되지 않았다면 재사용한다. 문서 변경·작업 범위 확대·맥락 누락이 있을 때 해당 부분을 다시 확인한다.
- 작업 절차와 도구 안내는 현재 단계에 해당하는 절을 읽는다. 템플릿은 작성할 종류 하나만 선택한다. 적용되는 상위·하위 작업 지침과 필수 계약·검증 절차는 생략하지 않는다.
- 소스는 `rg --files`, `rg`로 관련 파일·심볼을 찾고 필요한 부분부터 읽는다. 호출·의존 관계나 실패 근거에 따라 범위를 넓힌다. 무관한 디렉터리와 긴 로그 전체를 읽지 않되, 판단에 필요한 주변 문맥과 오류 원인은 확인한다.

## 작업별 참조

| 작업 | 읽을 문서와 범위 |
|---|---|
| Git 동기화·작업 진행 | [작업 절차](harness/workflow.md)의 해당 단계 |
| 코드 수정·리팩토링 | [코드](harness/rules/code.md) |
| 테스트 작성·수정·검증 범위 결정 | [테스트](harness/rules/tests.md) |
| 포맷·빌드·검사 실행 | [도구](harness/tooling.md)의 해당 명령·환경·제약 |
| API·이벤트 관련 변경 | [계약](harness/rules/contracts.md)의 해당 계약 절차와 실제 변경 유형의 규칙 |
| 브랜치·이슈·커밋·PR | [협업](harness/rules/collaboration.md)의 이름·작성·변경 단위 등 해당 절 |
| 이슈 작성 | [기능](harness/templates/issue-feature.md)·[버그](harness/templates/issue-bug.md)·[리팩토링](harness/templates/issue-refactor.md) 중 해당 템플릿 |
| PR·커밋 작성 | [PR](harness/templates/pull-request.md)·[커밋](harness/templates/commit-message.md) 중 해당 템플릿 |
| 문서 작성·정리 | [문서](harness/rules/documentation.md) |
| 리뷰 | 변경 대상의 규칙과 [작업 절차](harness/workflow.md)의 리뷰 단계 |
