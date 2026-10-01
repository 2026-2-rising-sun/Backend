# 팀에서 Apidog 사용하기

Apidog는 API 명세를 함께 작성·논의하고 요청을 실행하는 도구다. 팀의 확정 계약은 Git의 `contracts/api/*.yaml`이다.
Shopping-Live **팀**과 그 안의 **프로젝트**를 구분한다. 프로젝트 import·동기화 완료 여부는 실제 작업 결과로 별도 기록하며 이 문서만으로 완료를 주장하지 않는다.

## 초기 명세 가져오기

서비스마다 `ErrorEnvelope`, `BearerAuth` 같은 component 이름을 공유하므로 네 YAML을 같은 프로젝트에 순서대로 덮어쓰지 않는다.
파생 bundle을 만든 뒤 프로젝트의 OpenAPI 가져오기에 사용한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/contracts/apidog-bundle.cjs
```

- 가져올 파일: `build/contracts/apidog-shopping-live.json`.
- 원본·hash 정보: `build/contracts/apidog-shopping-live.manifest.json`.
- bundle은 component/ref/operationId에 서비스 구분을 붙이고 각 API의 원래 서버 주소를 보존한다.
- Apidog 폴더는 Member·Commerce·Shopping·Live로 구분한다. bundle은 공유용 파생 파일이며 원본 YAML을 대체하지 않는다.

OpenAPI 가져오기 화면의 변경 목록을 확인하고 요청·응답·필수값·인증·예제 누락 여부를 본다.
프로젝트에 기존 내용이 있으면 가져오기 옵션이 기존 endpoint·schema에 미치는 영향을 확인한다. 자동 무손실 동기화를 전제하지 않는다.

## 요청 실행

[수동 검수 도구](manual-review.md)를 띄우면 실제 서비스 주소와 A/B/ADMIN 계정을 준비한다.
도구가 만든 `env.json`은 일반 key/value JSON이므로 Apidog 전용 환경 import 파일이라고 가정하지 않는다.
필요한 URL과 로그인 토큰을 **개인 로컬 값**에 옮긴다. access/refresh/비밀번호·caller 토큰을 팀 공유 환경이나 명세 예제에 저장하지 않는다.

| 작업 | 사용할 대상 |
|---|---|
| 가입·로그인·refresh/logout | Member API 주소. refresh/logout은 JSON refreshToken으로 호출 |
| 상품·판매·방송 관리 | 해당 서비스 주소 + ADMIN Bearer |
| 장바구니·주문·결제 | Commerce 주소 + 본인 USER Bearer |
| 다른 회원 데이터 거부 확인 | A의 리소스 ID를 B 또는 ADMIN으로 조회하여 404 확인 |
| Mock 예제 비교 | 실제 API 주소와 구분한 [Prism 주소](prism-team-guide.md) |

수동 도구의 포트는 매 실행 달라진다. bundle의 기본 localhost 주소 대신 출력된 서비스별 URL을 개인 환경에 적용한다.
토큰의 15분 수명과 refresh rotation을 고려해 새 응답 토큰으로 개인 값을 갱신한다. 이전 refresh 재사용은 세션 보안 폐기 검수이므로 일반 재시도로 쓰지 않는다.

## 명세 변경 협업

1. Apidog에서 endpoint·필드·예제 변경을 제안하고 변경 이유를 기록한다.
2. OpenAPI 3.1 JSON/YAML로 내보낸다. 아래 명령으로 현재 Git에서 만든 bundle과 비교한다.

```sh
node scripts/contracts/validate.cjs /absolute/path/apidog-export.yaml
node scripts/contracts/compare.cjs build/contracts/apidog-shopping-live.json /absolute/path/apidog-export.yaml
```

3. diff를 서비스별 원본 YAML의 변경 제안으로 옮겨 PR을 만든다. export 전체를 원본 네 파일에 자동 덮어쓰지 않는다.
4. `npm run check --prefix scripts/contracts`와 `npm run smoke --prefix scripts/contracts`를 실행하고 독립 리뷰를 받는다.
5. 승인된 Git SHA로 bundle을 다시 만들어 Apidog를 갱신한다. Swagger와 Prism도 같은 YAML 버전으로 다시 실행한다.

비교 도구는 주석·객체 키 순서를 제외한 변경을 보수적으로 보여준다. Apidog 확장 필드나 예제·인증·nullable 손실도 자동으로 무시하지 않는다.
차이가 있으면 exit 1이며 사람이 의도된 변경인지 검토한다. 왕복 실제 실행 없이 호환성 검증을 완료했다고 기록하지 않는다.

Apidog 자체 Mock과 팀의 Prism 서버는 별개다. 상태 저장·실제 회원 인증·DB 재고·동시성 검증은 실제 서비스 환경에서 수행한다.
공식 근거: [가져오기 옵션](https://docs.apidog.io/import-options-633930m0), [내보내기 안내](https://docs.apidog.com/en/export-data-635117m0).
