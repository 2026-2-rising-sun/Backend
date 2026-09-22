# Live Draft PR 리뷰 체크리스트

대상: #59 라이브1, #60 라이브2, #62 라이브3, #63 라이브4, #65 라이브5, #66 라이브6, #61 라이브7, #64 라이브8, #67 라이브9.
체크 결과는 통과/실패/미검증과 근거 파일·명령·PR 링크를 기록한다. 미검증은 통과가 아니다.

## 추적·Stack·범위

- [ ] 기존 Issue 9개가 정확한 사용자 기능에 대응하며 새 기반/문서 Issue를 만들지 않았다.
- [ ] 모든 PR이 Draft이고 실제 base가 plan의 부모이며 선행 PR 준비 후 의존 작업을 시작했다.
- [ ] PR 템플릿·Issue 참조·변경량(add+delete)·테스트·blocker가 있다. parent 대비 약500줄이며 초과 이유가 타당하다.
- [ ] gh-stack 사용 또는 불가 사유가 입증되어 있다. dev merge·타인 branch 수정·기존 worktree 유실이 없다.
- [ ] 인증·역할·owner·장바구니·이벤트/outbox·WebSocket·자동 채널/StopStream·Live 주문 API가 없다.

## 기능별 검증

- [ ] #59 필수값·과거 예정시각·PREPARING, 요청 키 반복/동시/다른 본문, 준비만 수정·version 충돌·재시작 후 DB 유지.
- [ ] #60 관리 목록/상세의 상태·페이지·정렬, 외부 서비스 호출 없이 응답.
- [ ] #62 실제/stub adapter, 판매중/품절만 연결, productId/salesId 분리, 준비/진행 연결·해제, LIVE 마지막 상품 해제 거절, expectedVersion·동시 unique·타 방송 linkId·실패 rollback.
- [ ] #63 정확한 전체 순열·swap·준비/진행만 허용·종료 거절·연결/해제와의 expectedVersion 경합 원자성.
- [ ] #65 LIVE+HEALTHY·채널/URL 동일성·ON_SALE/SOLD_OUT≥1, available 무검사, 최초 startedAt·반복·동시 시작·채널 중복 LIVE·종료 재시작 금지.
- [ ] #66 최초 endedAt·반복/동시 종료, 준비 종료 거절, 주문/결제/재고/송출 제어 호출 없음.
- [ ] #61 비회원 세 상태 조회·비밀 필드 제외·종료 시 재생 진입 불가·외부 장애 독립.
- [ ] #64 키 없는 기동, NOT_READY/UNAVAILABLE 구분, OBS 단절 시 LIVE 유지, 실제 송출·시청·재연결 증거.
- [ ] #67 공개 READY/PRIVATE 제외·품절 구매 차단·순서·최신 가격/상태, 상품 장애와 영상 분리, 일반 구매 흐름 재사용 및 실제 E2E.

## 구조·계약·보안

- [ ] 다른 서비스 Entity/Repository/Java module import 또는 DB join/FK가 없다. 외부 정보 원본을 Live가 복제 소유하지 않는다.
- [ ] ApiResponse/204·400/404/409/503와 OpenAPI가 일치한다. Shopping wrapper와 Commerce raw parser가 구분된다.
- [ ] 외부 조회 중 긴 DB 잠금이 없고 조회 후 version/상태·연결 집합을 재검증한다. 연결/순서도 방송 동시성 경계에 참여한다.
- [ ] PostgreSQL migration·partial unique·동시성 검증을 H2 context 결과로 대체하지 않았다.
- [ ] 외부 timeout·ID mismatch·missing·5xx·미배포404·잘못된 응답을 실제 adapter 테스트로 검증했다. N+1 호출·무제한 retry가 없다.
- [ ] 주입 RestClient.Builder의 correlation ID 전달이 유지된다. SDK/원문 로그·DTO·문서에 streamKey/자격증명이 없다.
- [ ] local/test stub과 실제 모드가 명시되고 실제 장애가 자동 stub 성공으로 바뀌지 않는다.
- [ ] 채널 재사용 시 종료 화면이 새 송출 URL을 제공하지 않으며 기존 URL 철회 기능과 혼동하지 않는다.
- [ ] 배포 환경의 무인증 관리/내부 경로 접근 범위가 기록되어 있다.

## 완료 판정

- [ ] 실제 실행한 test/build 명령·환경·시각·결과·로그가 있다. 이전 XML 흔적을 이번 diff 검증으로 주장하지 않았다.
- [ ] child PR의 CI 실행 여부를 확인했고 CI 부재를 로컬 검증으로 보완했다.
- [ ] 실제 IVS 시청 및 방송 경유 구매 E2E는 실제 환경 증거가 있거나 미완료로 명시했다.
- [ ] Live 중단 상태의 일반 구매, 상품 장애 중 영상/기본정보, 방송 종료 후 진행 중 결제 지속을 공동 검증했다.
- [ ] docs/live·OpenAPI가 동작과 일치하고 기존 문서 링크가 살아 있다.
- [ ] Draft 제출/코드 검증/실연동/P1 완료를 구분하여 최종 보고했다.
