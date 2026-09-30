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
