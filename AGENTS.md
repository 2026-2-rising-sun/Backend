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

## 작업별 필수 참조

모든 작업은 [작업 절차](harness/workflow.md)를 기본으로 삼는다. 같은 작업에서 이미 읽은 문서는 변경되지 않았다면 다시 읽지 않아도 된다.

| 작업 | 추가로 읽을 문서 |
|---|---|
| 코드 수정·리팩토링 | [코드](harness/rules/code.md), [테스트](harness/rules/tests.md), [도구](harness/tooling.md) |
| API·이벤트 변경 | 위 문서와 [계약](harness/rules/contracts.md) |
| 브랜치·이슈·커밋·PR | [협업](harness/rules/collaboration.md), 작성 대상의 [템플릿](harness/README.md#템플릿) |
| 문서 작성·정리 | [문서](harness/rules/documentation.md) |
| 리뷰 | 변경 대상의 규칙과 작업 절차의 리뷰 기준 |
