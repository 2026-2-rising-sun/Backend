# 외부 계약·stub·오류·blocker

관련 기능: 연결 #62, 시작 #65, IVS #64, 상품·구매 #67. 기본정보 #60/#61은 외부 조회를 하지 않는다.
조사 기준: dev `45dddca`, 열린 팀 PR #52/#54/#55/#58. 열린 PR의 코드는 dev 제공 완료와 구분한다. 구현 착수 시 다시 확인한다.

## 소유권과 실제 제공 현황

| 서비스 | 소비 계약 | 확인 상태와 영향 |
|---|---|---|
| Shopping | GET /v1/internal/products/{id}, GET /v1/internal/products?ids=1,2 | [PR #52](https://github.com/2026-2-rising-sun/Backend/pull/52) 내부 조회. ApiResponse.data에 id/name/mainImageUrl. 배치 최대100, 중복 제거, 미존재 누락. dev에는 아직 골격이므로 실제 배포 확인 필요 |
| Commerce | GET /v1/sales?productIds=1,2 → raw 배열 productId/salesId/price/status/available | Shopping [#54](https://github.com/2026-2-rising-sun/Backend/pull/54)·[#55](https://github.com/2026-2-rising-sun/Backend/pull/55)의 소비자 기대 계약. 제공자 구현 확정 아님. Live도 같은 fixture로 개발하고 제공자 확정 전 실연동 완료 표시 금지 |
| Commerce 현 dev | GET /v1/sales/{id}/stock, PATCH price/status/stock | stock의 salesInfoId/available/reserved만으로 최신 가격·상태 조회 불가. GET 대신 PATCH를 조회에 사용하지 않음 |
| 일반 구매 | Shopping 상품 상세/구매 진입, Commerce 주문서·주문 생성·Mock 결제 | [Shopping #58](https://github.com/2026-2-rising-sun/Backend/pull/58) 등 팀 진행 확인. 현재 dev 주문 조회/취소·결제는 있으나 주문 생성 계약은 미완성. Live가 임의 주문 endpoint를 만들지 않음 |
| IVS | 기존 channelArn, playbackUrl, SDK GetStream | 실제 모드에 AWS 환경 필요. local/test에서는 명시적 stub으로 개발 |

Shopping 내부 기본정보 조회는 Commerce를 호출하지 않아야 한다. Live가 Shopping 공개 판매통합 API를 호출해 Commerce 순환 호출을 만들지 않는다. 다른 서비스 Java Entity/Repository/Client 직접 import 및 DB join/FK 금지. 원본 DTO는 Live 경계의 작은 record로 변환한다.

productId는 Shopping 상품 식별자, salesId는 Commerce 판매 식별자다. 연결은 productId로 저장하고 일반 구매 응답은 두 식별자를 구분한다. 가격은 KRW 정수 Long, available은 표시·구매 안내에만 사용한다. 시작 조건에 available을 사용하지 않는다. mainImageUrl null은 기본 이미지 표시이며 상대 URL은 게이트웨이 기준으로 해석한다.

## 응답·오류와 호출 예산

- Shopping ApiResponse 봉투와 Commerce raw 응답을 각 adapter에서 파싱한다. ID 불일치·중복·필수필드 누락·알 수 없는 상태는 계약 오류이며 성공으로 소비하지 않는다.
- 정상 조회의 미존재는 NOT_FOUND; timeout/5xx/잘못된 응답/미배포 경로 404는 UNAVAILABLE로 구분한다. 정상 빈 배치와 서버 오류를 혼동하지 않는다.
- 공개 상품은 ON_SALE/SOLD_OUT만 반환, READY/PRIVATE와 정상 조회에서 삭제된 상품은 제외한다. SOLD_OUT은 구매 불가. ON_SALE도 available 부족이면 구매 불가 안내이나 최종 판정은 Commerce다.
- 방송 상품 연결·해제는 PREPARING/LIVE에서 허용한다. LIVE의 마지막 연결 상품 해제는 409로 거절하며, 연결·해제·정렬은 expectedVersion과 전체 연결 집합을 같은 짧은 트랜잭션에서 검증한다. 변경은 다음 조회부터 반영하고 realtime push·상품 이력 snapshot은 추가하지 않는다.
- 상품 endpoint는 필수 외부 조회 실패 시 전체 503을 반환한다. P1 부분 성공 프로토콜·캐시를 추가하지 않는다. 기본정보/영상 endpoint는 계속 동작한다. 관리 상품 응답은 정상 조회에서 삭제된 연결도 ID/순서와 미존재 상태로 식별 가능하게 한다.
- HTTP connect 1초/read 2초, 조회 요청 자동 retry 없음. Spring 주입 RestClient.Builder를 사용해 X-Request-Id 전달을 유지한다. 배치 최대100이므로 상품별 N+1을 만들지 않는다.
- common-resilience의 기존 정책은 적용 여부를 확인한다. 선언만으로 timeout/retry가 작동한다고 주장하지 않는다. 새 회로차단 프레임워크를 만들지 않는다.
- common-core ErrorCode에는 현재 503이 없다. 첫 외부-client 기능 PR에서 SERVICE_UNAVAILABLE 공통 코드를 최소 추가하고 기존 처리 호환성을 검사한다. 업무 조건 불충족은 409, 인프라 장애는 503이다. 비밀 포함 SDK 원문은 응답·로그에 기록하지 않는다.

## IVS 준비와 시청 (#64, #65)

- GetStream의 LIVE+HEALTHY를 READY로 판정한다. 미송출/STARVING은 NOT_READY, 권한·통신·SDK 오류는 UNAVAILABLE다.
- channelArn에 대응하는 playbackUrl인지 SDK 응답 또는 GetChannel로 검증한다. 도메인/URL 문법 검사만으로 연결 동일성을 보장하지 않는다. 임의 URL로 서버가 HTTP 요청하는 방식은 사용하지 않는다.
- 공개 시청은 IVS→FE 직접 재생이다. Live API는 영상 프록시가 아니다. LIVE에서만 playbackUrl을 공개하며 ENDED 페이지가 재사용 채널의 새 송출을 재생하지 않게 한다.
- 일시 OBS 단절은 영상 오류/재시도 표시이며 업무 상태 LIVE를 유지한다. 관리자 종료는 ENDED만 저장하고 OBS는 운영자가 수동 중단한다. 공개 HLS URL을 이미 가진 사람의 접근 자체를 철회하는 기능은 P1 범위가 아니다.
- AWS SDK 전체 응답을 직렬화하지 않는다. streamKey·AWS 키·토큰을 DTO/로그/fixture/문서에 넣지 않는다. 필요한 GetStream/GetChannel 읽기 권한만 요구한다.
- SDK 전체 호출 timeout 3초/attempt 2초로 제한한다. 리전과 모드는 명시 설정하며 실제 모드 오류를 stub 성공으로 숨기지 않는다.
- PoC는 OBS/IVS SDK·시청 흐름의 참고다. PoC의 EventBridge 상태 전이, 인메모리 저장소, WebSocket/Kafka를 복사하지 않는다.

## 키 없는 개발

외부 경계마다 실제와 stub 구현을 둔다: Shopping/Commerce stub|http, IVS stub|aws. local/test는 stub을 명시하며 운영 프로파일의 stub 오설정은 실패시킨다. 실제 모드에서 자격증명이 없거나 외부가 내려가면 오류를 드러낸다. fixture 조작용 공개 API는 만들지 않는다.

동일 fixture 계약 테스트: 존재/미존재, ON_SALE 재고 양수/0, SOLD_OUT, PRIVATE, READY, 누락 이미지, 잘못된 응답, timeout/5xx, IVS READY/NOT_READY/UNAVAILABLE, 채널/URL 불일치. 부작용 없는 조회 adapter와 서비스 로직을 분리해 키 없이 테스트한다.

## 남은 blocker와 처리

| ID | 막히는 증거 | 계속할 일 | 해소 조건 |
|---|---|---|---|
| B1 | Commerce 판매조회 실제 연동 | 제안 계약·stub·consumer fixture로 #62/#65/#67 개발 | Commerce 제공 endpoint/필드/오류·배치 계약 확정 및 실제 호출 테스트 |
| B2 | Shopping 실제 기본정보 연동 | #52 계약으로 adapter 테스트 | 제공 PR 반영/실행 endpoint 확인 및 consumer-provider fixture 일치 |
| B3 | 실제 IVS 시청 | SDK mock·stub·오류/비밀 노출 테스트 | 기존 채널·읽기 자격증명·OBS 송출자·FE 플레이어로 송출/재생/단절복구/종료 검증 기록 |
| B4 | 방송 경유 구매 E2E | 식별자 응답·일반 구매 진입 계약 fixture | 팀 주문서/주문 생성/Mock 결제·FE 준비, 일반/방송 구매 공동 검증 |
| B5 | 공개 환경의 무인증 관리 API 노출 | 로컬/제한 개발환경 개발 | 배포 담당이 관리·내부 endpoint 접근 범위를 확인. P1에 회원 인증을 임의 구현하지 않음 |

현재 사용자 사업 정책 결정을 기다리는 항목은 없다. B1~B4는 구현을 중단시키지 않으며 담당 서비스 계약과 환경 증거를 기다리는 통합 완료 조건이다. 서로 충돌하는 실제 계약이 발견되면 한 결정 메모에 근거·영향·선택지를 모아 질문한다.

## P1 공동 완료 증거

1. 실제 OBS→IVS→플레이어 시청, 잠시 중단/재연결에도 업무 LIVE 유지, 관리 종료 후 종료 UI·재생 진입 차단.
2. 방송 상품 선택→일반 주문 생성→재고 차감→Mock 결제→결과 확인. 방송 종료가 진행 중 주문·결제를 취소하지 않음.
3. Live/IVS 중단 상태에서 방송 없는 일반 구매 성공. 상품 조회 장애 중 방송 기본정보·영상 유지.

환경·실행 시각·절차·결과·로그 위치를 #64/#67 및 Draft PR에 기록한다. 실제 자격증명/송출키는 기록하지 않는다. 환경이 없으면 미검증으로 남기고 P1 전체 완료를 선언하지 않는다.
