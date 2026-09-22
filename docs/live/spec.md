# Live P1 기능 명세

정책 기준: 최신 사용자 정정 지시 > 직접 답변 > 팀 코드·공통 모듈 > PoC·초안. Notion 링크는 사용자가 제공한 참조이며 페이지 최신 내용을 재검증했다는 뜻은 아니다.

## 공통 동작

- 상태는 PREPARING → LIVE → ENDED. 예정 시각에 의한 자동 전이 없음. 과거 예정 시각 등록 허용.
- 기본정보 수정은 PREPARING만 허용한다. 상품 연결·해제·정렬은 PREPARING/LIVE에서 허용하고 ENDED는 읽기 전용이다. LIVE에서도 편성 변경은 가능하지만 마지막 연결 상품의 해제는 거절한다.
- 시작은 현재 ON_SALE/SOLD_OUT 연결 상품 ≥1 + IVS 준비. 재고 available 검사·예약 없음. SOLD_OUT도 방송할 수 있다.
- 이미 LIVE인 시작 재요청, 이미 ENDED인 종료 재요청은 최초 시각을 보존해 성공. 준비 종료·종료 재시작은 409.
- 성공은 ApiResponse, DELETE 성공은 204 무본문. 입력 400/미존재 404/상태·버전·중복 409/외부 장애 503을 구분한다.
- 컨트롤러 경로는 /v1, 게이트웨이 외부 prefix는 /api/live. 외부 API 실패가 기본정보 응답을 실패시키지 않는다.
- title 필수 1~100자, scheduledAt은 offset 포함 ISO-8601→Instant 저장, UI는 KST 표시. 설명/이미지는 P1 필수 아님; 빈 기본 표시로 충분하며 방송 업로드 기능 없음.
- 등록은 Idempotency-Key(1~128자) 필수, 방송 레코드 수명 동안 unique. 같은 키·동일 입력은 같은 id, 다른 입력은 409. 입력 정규화 후 fingerprint를 비교한다.
- PATCH의 title/scheduledAt/channelArn/playbackUrl은 생략 시 유지, 명시 null 거절, version 필수. IVS 필드는 쌍으로 변경한다.
- 페이지 0부터, size 기본20/최대100. 관리 기본은 createdAt DESC/id DESC. 상태 필터 조회는 준비 scheduledAt ASC/id ASC, 진행 startedAt DESC/id DESC, 종료 endedAt DESC/id DESC. 공개 전체는 LIVE/PREPARING/ENDED 그룹 순서와 각 상태 정렬을 사용한다.
- 상품 연결은 productId로 unique; salesId와 같은 값이라고 가정하지 않는다. 연결·해제·정렬 변경은 expectedVersion을 요구하고 상태·version·전체 연결 집합을 짧은 원자 트랜잭션에서 검증한다. 전체 순서 입력은 linkId 순열과 expectedVersion. 연결 최대100.
- 연결 해제는 방송 존재·상태를 먼저 검사한다. 같은 방송에서 이미 사라진 linkId는 204, 다른 방송 소속으로 확인되면 404. LIVE에서 마지막 연결 상품을 해제하려는 요청은 409다.
- LIVE 상품 변경은 다음 조회부터 공개 상품 목록과 구매 진입에 반영한다. 실시간 push, 상품 이력 snapshot, 외부 이벤트/outbox는 P1 범위에 포함하지 않는다.
- 공개 DTO에서 채널 ARN·등록 키·fingerprint·송출키·자격증명을 제외한다. playbackUrl은 LIVE에서만 반환, playbackAllowed는 업무 상태를 의미하며 현재 신호를 보장하지 않는다.

| Notion 사용자 기능 | 기존 Issue | 내부 PR |
|---|---|---|
| 라이브 1 — 방송 등록·기본정보 수정 | [#59](https://github.com/2026-2-rising-sun/Backend/issues/59) | R1 등록·멱등성·저장(약 450~550줄), R2 기본정보 수정·경합 검증(약 250~400줄). |
| 라이브 2 — 관리용 방송 목록·상세 조회 | [#60](https://github.com/2026-2-rising-sun/Backend/issues/60) | R3 관리 기본정보 조회(약 250~400줄). |
| 라이브 3 — 방송 상품 연결·해제 | [#62](https://github.com/2026-2-rising-sun/Backend/issues/62) | R5 외부 조회 adapter/stub(약 350~500줄); R6 연결·해제·DB 제약(약 400~550줄). R5 초과 시 Shopping/Commerce별 분할. |
| 라이브 4 — 방송 상품 노출 순서 변경 | [#63](https://github.com/2026-2-rising-sun/Backend/issues/63) | R7 노출 순서 변경(약 200~350줄). |
| 라이브 5 — 방송 시작 | [#65](https://github.com/2026-2-rising-sun/Backend/issues/65) | R10 시작·동시성·API(약 400~550줄). 초과하면 동작 검증을 포함한 두 PR로 분할. |
| 라이브 6 — 방송 종료 | [#66](https://github.com/2026-2-rising-sun/Backend/issues/66) | R4 종료·재요청 안전성(약 200~350줄); 교차 경합 테스트는 R10 통합 시 수행. |
| 라이브 7 — 공개 방송 목록·상세 조회 | [#61](https://github.com/2026-2-rising-sun/Backend/issues/61) | R8 공개 방송 조회(약 200~350줄). |
| 라이브 8 — AWS IVS 영상 시청 | [#64](https://github.com/2026-2-rising-sun/Backend/issues/64) | R9 AWS/stub 준비조회(약 300~500줄); R12 공개 시청 연결과 실제 검증 기록(약 150~300줄). |
| 라이브 9 — 방송 상품 조회·바로 구매 | [#67](https://github.com/2026-2-rising-sun/Backend/issues/67) | R11 방송 상품 조회·구매 진입(약 350~500줄). 필요 시 관리/공개 응답을 분할하되 조회 로직 공유. |

## 라이브 1 — 방송 등록·기본정보 수정 (#59)

- **작업·포함 범위**: 제목·예정시각·기존 IVS 채널 ARN/시청 URL을 받아 PREPARING으로 저장한다. 과거 예정시각도 허용한다. 준비 상태에서만 기본정보를 수정한다. 등록 요청 키로 반복 클릭·응답 유실을 처리한다.
- **API**: POST /v1/admin/broadcasts, PATCH /v1/admin/broadcasts/{id}
- **도메인**: 필수값·제목 100자·offset 포함 시간 검증, 수정 version 검사, 같은 등록 키/다른 본문 409.
- **DB**: Broadcast + Flyway migration, 요청 키 unique, JPA version, 최초 상태 PREPARING.
- **외부 연동**: 등록은 AWS 송출 여부를 조회하지 않는다. 실제 채널과 URL 일치는 라이브 8·5에서 검증한다.
- **완료 조건**: DB 재시작 후 유지; 동일 요청은 같은 방송 반환; 잘못된 입력·충돌이 400/409로 구분된다.
- **테스트**: 필수값/잘못된 시간/과거 일정, 동일 키 반복·동시 요청·다른 본문, 준비 외 수정 거절, version 충돌.
- **선행**: 없음. dev 기준.
- **범위 제외**: 방송 이미지 업로드, 송출 중임을 등록 조건으로 요구하는 처리
- **blocker**: 없음. 실제 채널 검증은 라이브 8의 환경 의존.
- **Notion**: 개별 링크 미제공

## 라이브 2 — 관리용 방송 목록·상세 조회 (#60)

- **작업·포함 범위**: 상품·IVS 호출 없이 방송 기본정보를 조회한다. 준비/진행/종료 구분, 상태 필터·페이지·결정적 정렬을 제공한다.
- **API**: GET /v1/admin/broadcasts, GET /v1/admin/broadcasts/{id}
- **도메인**: 페이지 기본 20/최대 100; 상태별 정렬 및 id tie-breaker; 미존재 404.
- **DB**: 기존 방송 테이블 조회. 불필요한 별도 조회 저장소/캐시 없음.
- **외부 연동**: 기본정보 응답에서 Shopping/Commerce/IVS 호출 0회. 연결 상품 상세는 라이브 9의 별도 endpoint.
- **완료 조건**: 운영 화면에서 상태별 조회 가능하고 상품 장애가 기본정보 응답을 실패시키지 않는다.
- **테스트**: 세 상태 목록·상세, 빈 페이지/잘못된 필터/404, 외부 서비스 down에서도 200.
- **선행**: 라이브 1 #59.
- **범위 제외**: 공개 조회 구현(라이브 7), 상품정보 결합 응답
- **blocker**: 없음.
- **Notion**: [원문](https://app.notion.com/p/2-3e07a82c52e281b59fc7eb293ad9dc1e?pvs=21)

## 라이브 3 — 방송 상품 연결·해제 (#62)

- **작업·포함 범위**: Shopping의 존재하는 상품과 Commerce 판매정보를 확인한 뒤 ON_SALE/SOLD_OUT만 준비·진행 방송에 연결한다. 준비·진행 방송에서 해제하되 진행 방송의 마지막 연결 상품은 유지한다. 실제 HTTP와 stub 경계를 함께 제공한다.
- **API**: POST /v1/admin/broadcasts/{id}/products, DELETE /v1/admin/broadcasts/{id}/products/{linkId}
- **도메인**: productId로 연결, 최대 100개, 중복 409, 상태 재확인; PREPARING/LIVE에서만 변경한다. 연결·해제는 expectedVersion을 검증하고, LIVE의 마지막 상품 해제는 409다. 해제 재요청 204, 타 방송 linkId는 404.
- **DB**: BroadcastProduct(broadcastId,productId,position), 로컬 FK, (broadcastId,productId) unique. 방송 행 version/잠금에 참여.
- **외부 연동**: ProductClient/SalesClient, Shopping 기본정보·Commerce 배치 판매정보, timeout·응답 검증·local/test stub.
- **완료 조건**: 원본 판매 상태를 확인하며 중복·종료 외 변경을 막는다. LIVE 변경도 원자적으로 처리하고 마지막 상품 해제를 거절한다. stub과 HTTP 계약 테스트가 통과한다.
- **테스트**: 판매중/품절 성공, READY/PRIVATE/미존재 거절, 외부 장애 시 저장 0, PREPARING/LIVE 연결·해제, LIVE 마지막 상품 해제, expectedVersion 충돌, 순차·동시 중복·연결·해제, 타 방송 해제, 시작과 연결 경합.
- **선행**: 라이브 1 #59와 이 Issue의 R5 외부-client PR 모두 준비된 뒤 연결 구현 시작.
- **범위 제외**: 재고 차감·예약, 외부 DB FK, 상품 마스터 복제
- **blocker**: Commerce GET /v1/sales?productIds 계약은 제공자 확정 필요. Shopping #52는 열린 PR이며 dev 제공 여부 재확인. stub으로 계속 진행.
- **Notion**: [원문](https://app.notion.com/p/3-3e07a82c52e28199a5d3e5d673fbe474?pvs=21)

## 라이브 4 — 방송 상품 노출 순서 변경 (#63)

- **작업·포함 범위**: 준비·진행 방송의 연결 상품 전체 순서를 원자적으로 바꾼다. 종료 방송은 변경할 수 없다. LIVE 연결·해제와 순서 변경의 version 경합도 같은 방송 동시성 경계에서 처리한다.
- **API**: PUT /v1/admin/broadcasts/{id}/products/order
- **도메인**: 전체 linkId 순열 및 expectedVersion 검증. 중복·누락·다른 방송 ID 거절.
- **DB**: 기존 position 갱신과 방송 version 변경을 같은 트랜잭션으로 처리.
- **외부 연동**: 외부 조회 없이 Live 연결 집합만 검증.
- **완료 조건**: 성공 시 전체 순서가 반영되고 충돌·실패 시 일부 순서가 남지 않는다.
- **테스트**: 준비/진행 성공, 종료 거절, swap·전체 재정렬, 누락/중복/외부 ID, expectedVersion 충돌, 동시 연결·해제·정렬 경합 및 롤백.
- **선행**: 라이브 3 #62.
- **범위 제외**: 실시간 push, 상품 이력 snapshot, 외부 이벤트/outbox
- **blocker**: 없음. 선행 연결 PR 준비 필요.
- **Notion**: [원문](https://app.notion.com/p/4-3e07a82c52e281f6b62bcec507a5716b?pvs=21)

## 라이브 5 — 방송 시작 (#65)

- **작업·포함 범위**: IVS 준비와 현재 ON_SALE 또는 SOLD_OUT인 연결 상품 최소 1개를 확인한 뒤 LIVE로 전환한다. available 재고는 시작 판단에 사용하지 않는다.
- **API**: POST /v1/admin/broadcasts/{id}/start
- **도메인**: LIVE 재요청은 외부 재확인 없이 최초 결과 반환; ENDED 재시작 거절. 외부 조회 후 방송·연결 version 재확인.
- **DB**: 최초 startedAt, 조건부 상태 전이, 동일 channelArn의 동시 LIVE를 PostgreSQL partial unique 등 DB 불변조건으로 방지.
- **외부 연동**: 라이브 3 SalesClient 및 라이브 8 IVS 준비 조회. LIVE+HEALTHY와 채널/시청 URL 일치 확인.
- **완료 조건**: 정상 전이와 최초 시각 보존, 종료 재시작 차단, 재고 조회·차감·예약 미수행. 실제 연동 검증은 별도 증거 기록.
- **테스트**: 재고 0이어도 허용 상태면 시작, 연결 0/READY/PRIVATE만이면 거절, IVS 미준비/장애/URL 불일치, 반복/동시 시작, 시작-수정/연결/종료 경합, 채널 중복.
- **선행**: 라이브 1 #59, 라이브 3 #62, 라이브 8 #64의 R9 준비조회 PR. 모두 준비된 뒤 착수; 실제 영상 E2E 완료를 개발 선행으로 요구하지 않음.
- **범위 제외**: 방송 시작 재고 검사, EventBridge/Kafka/outbox, 예약 시각 자동 시작
- **blocker**: Commerce 실조회와 AWS 환경 미제공 시 stub으로 개발. 실제 통합 확인은 남김.
- **Notion**: [원문](https://app.notion.com/p/5-3e07a82c52e281ba8cefeeb7bc771ab3?pvs=21)

## 라이브 6 — 방송 종료 (#66)

- **작업·포함 범위**: LIVE를 ENDED로 전환하고 최초 종료 시각을 보존한다. 반복 종료는 성공하며 준비 방송 종료와 종료 후 재시작은 거절한다.
- **API**: POST /v1/admin/broadcasts/{id}/end
- **도메인**: 최초 endedAt 보존, 준비 종료 409, OBS 단절은 상태 전이 트리거가 아님.
- **DB**: 짧은 트랜잭션의 원자적 상태 전이; 시작/정렬과 같은 방송 경합 규칙.
- **외부 연동**: Commerce 호출 없음. 업무 종료 후 OBS 수동 중단 절차를 문서화.
- **완료 조건**: 종료가 결제·주문·판매 상태·재고에 영향을 주지 않는다.
- **테스트**: 반복/동시 종료 시 endedAt 불변, 준비 종료 거절, 주문·결제 호출 0, 종료 후 start 거절.
- **선행**: 라이브 1 #59. 시작-종료 통합 경합 검증은 라이브 5 #65 준비 후 추가.
- **범위 제외**: 자동 StopStream, 주문 취소·환불·재고 복원
- **blocker**: 없음. 실제 OBS 중단 절차는 라이브 8 공동 검증.
- **Notion**: [원문](https://app.notion.com/p/6-3e07a82c52e28171a1d5e0fd4b6eb5d1?pvs=21)

## 라이브 7 — 공개 방송 목록·상세 조회 (#61)

- **작업·포함 범위**: 로그인 없이 예정·진행·종료 방송 목록과 상세를 조회한다. 기본정보는 상품 장애와 독립하며 종료된 방송은 새 송출의 재생 진입을 제공하지 않는다.
- **API**: GET /v1/broadcasts, GET /v1/broadcasts/{id}
- **도메인**: PREPARING=예정/LIVE=진행/ENDED=종료 표시; 공개 DTO에는 요청 키·채널 ARN·비밀정보 제외.
- **DB**: 라이브 2 조회 로직 재사용; 별도 공개 저장 모델 없음.
- **외부 연동**: 기본정보 조회에서 외부 호출 0. 시청 연결 정책은 라이브 8과 동일.
- **완료 조건**: 일반 상품 구매 경로에 Live 의존을 추가하지 않으며 공개 DTO가 내부 정보를 노출하지 않는다.
- **테스트**: 모든 상태·필터·페이지, 비밀 필드 부재, 외부 down에서도 조회 성공, 종료 응답의 재생 불가.
- **선행**: 라이브 1 #59, 조회 재사용을 위해 라이브 2 #60.
- **범위 제외**: 상품 상세 결합, 다시보기·추천·검색 고도화
- **blocker**: 없음.
- **Notion**: [원문](https://app.notion.com/p/7-3e07a82c52e28149be5fdc3d0a15f124?pvs=21)

## 라이브 8 — AWS IVS 영상 시청 (#64)

- **작업·포함 범위**: 미리 만든 AWS IVS 채널의 준비 상태를 조회하고 LIVE 방송 시청 연결을 제공한다. 실제 AWS adapter와 키 없는 stub을 모두 제공하며 실제 OBS→IVS→플레이어 시청을 검증한다.
- **API**: 별도 영상 프록시/채널 생성 API 없음. 라이브 7 공개 응답의 playbackUrl/playbackAllowed, 라이브 5 내부 준비조회 연결.
- **도메인**: LIVE+HEALTHY=READY, 미송출/STARVING=NOT_READY, SDK/권한/통신 오류=UNAVAILABLE. 업무 상태와 영상 상태 분리.
- **DB**: 기존 channelArn/playbackUrl 사용; 추가 영상 세션 저장소 없음.
- **외부 연동**: AWS SDK GetStream 및 필요한 채널 URL 확인, IAM 최소 읽기 권한, local/test stub. 송출 비밀은 서버 응답·로그·fixture에 저장하지 않음.
- **완료 조건**: stub 테스트와 별도로 실제 IVS 재생 증거를 남긴다. OBS 일시 단절 뒤 업무 상태 LIVE를 유지하며 재시도할 수 있다.
- **테스트**: ready/not-ready/unavailable·잘못된 채널/URL, 키 없이 기동, 비밀정보 미노출; 실제 OBS 송출·재생·재연결·업무 종료 후 시청 차단.
- **선행**: R9 adapter는 독립 준비 가능. R12 시청 연결/실검증은 라이브 7 #61·라이브 5 #65·라이브 6 #66 준비 후.
- **범위 제외**: 채널 자동 생성, 자동 송출 중단, EventBridge, WebSocket, 영상 프록시·다시보기
- **blocker**: 실제 AWS 읽기 자격증명·기존 채널·OBS 송출자·FE 플레이어 필요. 없으면 실제 검증 체크 미완료로 남김.
- **Notion**: [원문](https://app.notion.com/p/8-AWS-IVS-3e07a82c52e28159b5caca96f70485f2?pvs=21)

## 라이브 9 — 방송 상품 조회·바로 구매 (#67)

- **작업·포함 범위**: 방송 연결 순서로 상품 기본정보·최신 판매정보를 조회하고 일반 주문 화면에 필요한 productId/salesId를 제공한다. 상품·영상 영역은 독립 요청한다.
- **API**: GET /v1/admin/broadcasts/{id}/products, GET /v1/broadcasts/{id}/products
- **도메인**: 공개 ON_SALE/SOLD_OUT만 표시, 품절은 구매 불가; READY/PRIVATE 제외. 미존재와 조회 장애를 구분하고 장애를 빈 목록으로 숨기지 않음.
- **DB**: 연결 집합 읽기만 수행; 가격·재고 복제 저장/차감 없음.
- **외부 연동**: Shopping 이름/이미지 + Commerce 가격/상태/표시재고·salesId 조합. FE가 동일 일반 주문 흐름에 상품·수량을 전달; 최종 가격·재고 검증은 Commerce.
- **완료 조건**: Live 전용 주문 API 없이 일반 구매로 진입하고 실제 IVS 시청→주문→Mock 결제 E2E를 공동 검증한다.
- **테스트**: 연결 순서와 LIVE 중 변경의 다음 조회 반영, 판매 상태 변경, soldout/private/미존재, 상품 장애에도 기본정보·영상 유지, 영상 장애에도 일반 구매 유지, 방송 종료 후 주문·결제 계속.
- **선행**: 라이브 3 #62. 방송 화면 결합은 라이브 7 #61·8 #64, 구매 E2E는 팀 일반 주문·결제·FE 필요.
- **범위 제외**: 장바구니, Live 주문·결제 API, 구매 가능 보장·재고 예약
- **blocker**: Commerce 판매조회 계약 및 일반 주문 생성/FE 진입 계약, 실제 IVS 환경 필요. 소비자 fixture로 개발하되 E2E 완료를 주장하지 않음.
- **Notion**: [원문](https://app.notion.com/p/9-3e07a82c52e2811f83d5e4bb1b59bf10?pvs=21)

## 공통 제외

인증·소유권·역할·장바구니(P2), 알림·채팅·좋아요(P3), EventBridge·Kafka 이벤트·outbox, IVS 채널 자동 생성·자동 송출 중단, OBS 단절 자동 종료, 종료 방송 재시작, 시작 시 재고 검사, Live 전용 주문·결제 API.

실제 IVS 시청과 방송 경유 구매 E2E를 통과하기 전에는 P1 최종 완료로 표시하지 않는다. stub 테스트는 개발 검증 증거다.
