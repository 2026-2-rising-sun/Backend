# 팀에서 Prism 사용하기

Prism은 OpenAPI에 적힌 응답 예제를 반환한다. 아직 준비되지 않은 의존 API나 특정 오류 응답을 재현할 때 사용한다.
기준은 Git에서 리뷰한 `contracts/api/*.yaml`이며 Swagger와 같은 파일을 읽는다.

## 실행과 종료

Node.js 22 이상, Docker Desktop을 준비하고 Backend에서 실행한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/contracts.cjs mock shopping 4010
```

터미널을 켜 둔 상태에서 아래 요청을 보낸다. `127.0.0.1`만 열며 Ctrl-C는 이 명령의 컨테이너만 종료한다.
Member·Commerce·Live도 `shopping` 대신 `member`, `commerce`, `live`를 지정한다. 포트를 생략하면 4010이다.

```sh
curl -i -H 'Prefer: code=200, example=normal' http://127.0.0.1:4010/v1/products/1
curl -i -H 'Prefer: code=200, example=soldOut' http://127.0.0.1:4010/v1/products/1
curl -i -H 'Prefer: code=404, example=notFound' http://127.0.0.1:4010/v1/products/999
curl -i -H 'Prefer: code=503, example=salesUnavailable' http://127.0.0.1:4010/v1/products/1
```

| 예제 | 사용하는 상황 | 확인할 수 없는 것 |
|---|---|---|
| normal 200 | 정상 필드·가격·표시 상태를 소비하는 코드 | 실제 DB의 상품·가격 정확성 |
| soldOut 200 | 품절 상태를 처리하는 코드 | 동시 주문 경쟁, 재고 차감·복구 |
| notFound 404 | 없는 상품의 오류 처리 | 해당 ID가 실제 저장소에 없는지 |
| salesUnavailable 503 | 의존 API 오류 응답을 받은 소비자 동작 | 네트워크 연결 차단·실제 read timeout |

POST/PATCH에서는 계약의 요청 JSON과 Content-Type도 맞춘다. 인증이 정의된 경로에는 계약 테스트용 가짜 Bearer 또는 X-Service-Token 헤더가 필요할 수 있다.
가짜 문자열은 Prism의 요청 형태 검사에만 사용하며 실제 인증에는 쓸 수 없다.

## 팀 작업 흐름

1. 정상·오류 응답을 YAML의 named example로 제안한다. 실제 토큰·개인정보는 예시에 넣지 않는다.
2. 같은 응답 코드에서 예제를 선택하려면 `Prefer: code=200, example=<이름>`처럼 두 값을 지정한다.
3. 아래 검사로 schema·예시와 실제 Prism 반환값을 대조한 뒤 PR 리뷰를 받는다.

```sh
npm run check --prefix scripts/contracts
npm run smoke --prefix scripts/contracts
```

필수 성공·오류·세션 사례를 삭제하면 smoke는 실패한다. 결과는 `build/contracts/prism.json`에 남는다.
52개 예제의 성공은 실제 로그인·refresh 철회·주문·재고 검증 성공과 구분한다. 예제가 추가되면 개수도 바뀐다.

실제 서비스 한 개와 Prism 의존 서비스를 연결하려면 [로컬 실행 안내](local-execution.md)의 서비스 주소 옵션을 사용한다.
[수동 전체 환경](manual-review.md)은 Member·Shopping·Commerce·Live를 실제 HTTP로 연결한다. 서비스의 일부 실패 사례만 자동 통합 harness에서 명시적으로 Prism으로 전환한다.

Prism 예제는 상태를 영속하지 않는다. 회원별 권한, 멱등성, 트랜잭션, 동시성, 재고 예약을 보장하지 않는다.
지정 시간 응답 지연을 Prism 고유 기능으로 가정하지 않는다. 실제 timeout 검증은 `scripts/integration/mock-proxy.cjs`가 응답 헤더를 지연시키고 소비자의 연결 종료를 기록하는 별도 시나리오다.
그 테스트의 임시 transport 503 overlay는 공개 제공 API 계약을 바꾸거나 제공 서비스 구현을 증명하는 자료가 아니다.

공식 근거: [Prism CLI](https://github.com/stoplightio/prism/blob/main/docs/getting-started/03-cli.md).
