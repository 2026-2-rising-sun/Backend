# 계약 도구

Git에서 승인된 `contracts/api/*.yaml`이 기준이다. 현재 Shopping·Live P1 계약부터 검증한다.
Member·Commerce P2 계약과 인증 정책은 도메인 담당자 인계 후 추가한다.
문서·Mock 검증 성공은 실제 로그인, 권한, DB 재고 검증 성공을 의미하지 않는다.

Node.js 22 이상과 Docker가 필요하다. Backend 저장소에서 실행한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
npm run check --prefix scripts/contracts
```

- `lint`: Redocly의 OpenAPI 구조·참조·operationId·security 정의 검사.
- `examples`: AJV 2020으로 모든 component schema와 요청·응답 예시를 검사한다.
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
Mock은 계약 파일의 `normal`, `soldOut`, `notFound`, `salesUnavailable` 예시를 선택한다.
예: `curl -H 'Prefer: code=200, example=soldOut' http://127.0.0.1:4010/v1/products/1`.
smoke는 임시 포트로 실행 후 정리하며 `build/contracts/prism.json`과 로그를 남긴다.
이 검사는 응답 형태만 확인한다. 품절 예시는 실제 재고 차감 검증이 아니며 JWT를 검증하지 않는다.
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
