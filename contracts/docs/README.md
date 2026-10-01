# 계약 도구

Git에서 승인된 `contracts/api/*.yaml`이 기준이다. 현재 네 서비스의 구현된 API를 기술한다.
Member는 가입·로그인·프로필과 승인된 refresh rotation·로그아웃·탈퇴·관리자 세션 폐기·내부 상태 확인 계약을 포함한다.
소셜 로그인은 별도 미구현 범위이며, 신규 세션 계약의 실제 동작 증거는 구현과 같은 SHA의 HTTP/DB 검증으로 확인한다.
Commerce는 회원 전용 주문·결제·장바구니·판매 관리, Shopping·Live는 공개 조회와 ADMIN 관리 권한을 반영한다.
문서·Mock 검증 성공은 실제 로그인, 권한, DB 재고 검증 성공을 의미하지 않는다.

Node.js 22 이상과 Docker가 필요하다. Backend 저장소에서 실행한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
npm run check --prefix scripts/contracts
```

- `lint`: Redocly의 OpenAPI 구조·참조·operationId·security 정의 검사.
- `examples`: AJV 2020으로 모든 component schema와 요청·응답 예시를 검사한다.
- `test`: Git 기준 구조 변경 검사의 누락·삭제·인가 변경·검토 hash 불일치 회귀 검사.
- 실패 시 종료코드 1이며 결과는 `build/contracts/{lint,examples}.json`, `lint.log`에 기록한다.
- 참조는 현재 파일 내부 `$ref`만 허용한다. 외부 참조·externalValue 예시는 실패로 보고한다.
- 이미지 버전과 digest는 `scripts/contracts/images.json`, npm 버전은 같은 디렉터리 lockfile로 고정한다.
- 이미지의 최초 실행에는 Docker registry 접근이 필요하며 공개 Mock 배포는 포함하지 않는다.

공식 근거: [Redocly lint](https://redocly.com/docs/cli/commands/lint),
[Prism CLI](https://github.com/stoplightio/prism/blob/main/docs/getting-started/03-cli.md),
[Swagger UI 설정](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/).

## 정적 문서와 Mock 실행

```sh
node scripts/local/contracts.cjs docs 18090
node scripts/local/contracts.cjs mock shopping 4010
npm run smoke --prefix scripts/contracts
```

각 명령을 별도 터미널에서 실행한다. host 포트는 loopback만 열고 YAML은 읽기 전용으로 mount한다.
Swagger 기본 포트 18090은 기존 로컬 Kafka UI의 8090과 구분한다.
Ctrl-C는 해당 명령이 생성한 컨테이너만 종료한다. 기존 DB·Compose는 변경하지 않는다.
Swagger는 작업 파일의 스냅샷과 SHA/hash를 `/contracts/provenance.json`에 남긴다.
`urls`로 서비스를 선택하고 Try it out은 비활성화한다. 작업 스냅샷을 승인본으로 표시하지 않는다.
Mock은 계약 파일의 성공·401·403·타인 객체 404·재고 부족·충돌·의존 실패 예시를 선택한다.
예: `curl -H 'Prefer: code=200, example=soldOut' http://127.0.0.1:4010/v1/products/1`.
smoke는 임시 포트로 실행 후 정리하며 `build/contracts/prism.json`과 로그를 남긴다.
빈 시나리오·서비스 전체 누락·서비스별 성공 또는 필수 오류 코드 누락은 Docker 시작 전에 실패한다.
refresh 교체·멱등 로그아웃·내부 active=false·세션 확인 실패 등 이름으로 지정한 필수 사례도 누락되면 실패한다.
필수 실패 범위는 `contracts/scenarios/required-coverage.json`에 있으며 이를 줄이는 변경도 별도 리뷰한다.
이 검사는 응답 형태만 확인한다. 품절 예시는 실제 재고 차감 검증이 아니며 JWT를 검증하지 않는다.
시나리오의 Bearer/service token은 Prism의 요청 형태 검사용 가짜 문자열이다. 실제 서비스 인증에는 쓸 수 없다.
회원별 권한·세션 철회·멱등 replay·장바구니 삭제·재고 원자성·결제 전이는 A5 실제 HTTP/DB 검증으로 확인한다.
Prism의 active=true 예시는 권위 저장소의 세션 확인을 대체하는 인증 증거가 아니다.
HTTP 503 예시는 네트워크 장애·read timeout과 구분한다. 실제 지연은 A5의 별도 proxy/stub로 시험한다.
Prism 5.15.10 이미지는 amd64이며 Apple Silicon에서 에뮬레이션으로 실행 확인했다.
이 이미지의 기본 multiprocess 시작 오류를 피하기 위해 공식 `--multiprocess false` 옵션을 사용한다.

## Apidog 왕복 검토

승인 YAML import → Apidog에서 제안·협의 → OpenAPI 3.1 YAML export → 아래 비교 → Git PR 승인 순서다.

```sh
node scripts/contracts/validate.cjs /absolute/path/export.yaml
node scripts/contracts/compare.cjs contracts/api/shopping-service.yaml /absolute/path/export.yaml
```

주석·키 순서 차이를 제외한 모든 변경을 JSON pointer로 보고하고 차이가 있으면 exit 1이다.
operation 누락·security·예시·확장 필드(`x-apidog-*` 포함)를 조용히 무시하지 않는다.
의도된 변경도 PR diff 검토 후 승인하며 자동 덮어쓰기·무손실 동기화를 전제하지 않는다.
같은 승인 YAML을 Swagger·Prism·Apidog에 재반영하고 Git SHA를 프로젝트 안내에 남긴다.
현재 Apidog 프로젝트 import/export 실제 실행은 별도 연결 후 검증할 항목이다.
[공식 import 옵션](https://docs.apidog.io/import-options-633930m0)과
[export 제약](https://docs.apidog.com/en/export-data-635117m0)을 확인한다.

## Git 기준 변경 검토 기록

```sh
node scripts/contracts/base-diff.cjs --base "$BASE_SHA"
```

`BASE_SHA`는 PR의 실제 base SHA, push의 이전 SHA, 수동 검사에서 선택한 기준 commit/tree다.
빈 tree도 지원한다. 현재 체크아웃의 모든 서비스 YAML을 비교하며 추가·삭제도 포함한다.
주석·객체 키 순서만 무시하고 배열 순서·예시·설명·확장 필드를 포함한 구조 변경을 보수적으로 보고한다.
자동 후방 호환 판정 도구가 아니다. `build/contracts/base-diff.json`의 변경과 의도된 비호환 내용을 별도 리뷰한다.

독립 리뷰 후 `contracts/docs/change-review.json`의 `reviews`에 다음 항목을 기록한다.

- 보고서의 `baseSnapshotSha256`, `headSnapshotSha256` 값 그대로.
- `reason`: 필요한 변경과 호환성 영향. 이번 P2는 관리/내부 인증 추가, 회원 전용 거래, 신규 계약이다.
- `independentReview`: `reviewer`, `evidence`(리뷰 경로·링크·검증 근거), `reviewedAt`.

기록이 없거나 두 snapshot hash가 다르면 exit 1이다. YAML이 다시 바뀌면 리뷰 기록도 새로 필요하다.
CI가 확인하는 것은 이 기록과 hash 일치다. 실제 GitHub 사람 승인 여부나 리뷰의 품질을 검증하지 않는다.
작성자가 스스로 승인 기록을 채우지 않는다. 기록 파일의 초기 빈 배열은 승인 대기 상태이며 통과 우회가 아니다.

로컬 실제 서비스·Mock 조합, 공개키와 ADMIN 초기화는 [로컬 실행 안내](local-execution.md)를 따른다.
