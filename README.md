# Backend

라이브 커머스의 5개 서비스를 담은 JDK 21 / Gradle 멀티모듈 저장소입니다.

## 팀 문서

팀 공유 문서의 최신 기준은 [📁 P2 백엔드](https://app.notion.com/p/3ec226545d158172991cd0137f4c7132)입니다.
[ShoppingLive 팀 메인](https://app.notion.com/p/3e9226545d1581989a9edefd9da775c4)에서 전체 문서를 찾을 수 있습니다.
진행 보고·테스트 방법·설계와 운영 절차는 Notion에서 관리하고, 실행 코드·OpenAPI YAML·검토 JSON은 Git에 유지합니다.

- [진행 보고](https://app.notion.com/p/3ec226545d1581a392c6dfd15ca2e0a1) · [테스트 가이드](https://app.notion.com/p/3ec226545d1581b6a9c6f6820d0891e4)
- [로컬 실행·수동 검수·회원 거래 전환](https://app.notion.com/p/3ec226545d158139b96fcba02d41c8f4)
- [Apidog 협업](https://app.notion.com/p/3ec226545d1581978775f3425f4208ea) · [Prism·Swagger](https://app.notion.com/p/3ec226545d15813e81fdc4bc0e494a9a)
- [계약 검토 기록](https://app.notion.com/p/3ec226545d1581cf807fe8a899c07787) · [Infra 설정](https://app.notion.com/p/3ec226545d15811087c4c552be01e341)

## 실행 진입점

빌드는 JDK 21과 저장소의 Gradle wrapper를 사용합니다. 로컬 API 도구는 Node.js 22 이상과 Docker가 필요합니다.

```sh
./gradlew build
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/manual-review.cjs
```

수동 검수 도구는 전용 서비스·DB·키·계정을 준비하고, Ctrl-C로 자신의 자원만 정리합니다.
개별 서비스의 필수 설정과 기존 DB 전환 절차는 위 로컬 실행 문서를 따릅니다.

IntelliJ의 기본 Application 실행은 프로필 미지정 시 `local`을 선택합니다.
로컬 인프라를 켠 뒤 Backend 루트에서 다음 명령을 한 번 실행하면 5개 서비스의 기본 실행 버튼을 사용할 수 있습니다.
키·서비스 토큰은 Git에서 제외된 파일에만 저장되며, 기존 파일은 덮어쓰지 않습니다.

```sh
node scripts/local/auth-env.cjs create --access-ttl PT15M --refresh-ttl P30D --out build/local/ide-auth
```

명시적으로 지정한 프로필은 기존 실행 방식을 유지하고 로컬 자격 증명 파일을 읽지 않습니다.
CLI·환경변수로 지정한 설정은 생성 파일보다 우선합니다.

| 목적 | 실행 파일·계약 |
|---|---|
| 실제 서비스 HTTP 통합 검사 | [run.cjs](scripts/integration/run.cjs) |
| 수동 API 검수 환경 | [manual-review.cjs](scripts/local/manual-review.cjs) |
| 로컬 키·서비스별 환경 준비 | [auth-env.cjs](scripts/local/auth-env.cjs) |
| Prism·Swagger 실행 | [contracts.cjs](scripts/local/contracts.cjs) |
| 계약 검사 명령 | [package.json](scripts/contracts/package.json) |
| Apidog import용 bundle 생성 | [apidog-bundle.cjs](scripts/contracts/apidog-bundle.cjs) |
| API 계약 원본·검토 근거 | [contracts/api](contracts/api) · [change-review.json](contracts/docs/change-review.json) |

## 서비스와 인증 경계

| 서비스 | 책임 | local 기본 포트 |
|---|---|---|
| [Member](services/member-service) | 회원·비밀번호·토큰·세션 | 8081 |
| [Shopping](services/shopping-service) | 상품·이미지 | 8082 |
| [Commerce](services/commerce-service) | 판매·재고·장바구니·주문·결제 | 8083 |
| [Live](services/live-service) | 방송·방송 상품 연결 | 8084 |
| [Notification](services/notification-service) | 알림 이벤트 소비 | 8085 |

Member·Shopping·Commerce·Live는 [common-security](libs/common-security)의 JWT 서명 검증을 사용하고 각 서비스에서 역할·리소스 소유권을 검사합니다.
사용자 세션은 Member DB를 기준으로 확인하며 다른 서비스는 서명 검증 후 내부 상태 API를 호출합니다.
상태 확인 장애는 인증된 요청의 503으로 처리하고, 무토큰 공개 조회와 별도 서비스 caller 인증은 분리합니다.
장바구니·주문·결제는 회원 본인만 접근하며 상품·공개 방송 조회는 비회원에게 허용합니다. 판매자 관리 작업은 SELLER, 내부 API는 방향별 서비스 자격증명을 요구합니다.

컨트롤러 경로는 `/v1/...`입니다. 이번 P2는 Gateway 없이 서비스에 직접 요청하며 프론트 연결도 포함하지 않습니다.
서비스는 다른 서비스 모듈을 `project()`로 의존할 수 없습니다. 공통 라이브러리·이벤트 계약만 공유하며 모듈 경계 위반은 빌드에서 거절합니다.
서비스 간 연결은 승인된 REST·이벤트 계약을 사용하고, 실제 결제·영상 연동의 검증 범위는 진행 보고에서 구분합니다.
