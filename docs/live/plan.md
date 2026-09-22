# Live 구현 계획과 결정 기록

기준일 2026-09-22. 이 문서는 구현 지시이며 실제 branch/PR 생성 결과가 아니다. 현재 작업자는 문서와 기존 Issue만 정리한다.

## 사용자 기능 Issue 대응

| 기능 | 기존 Issue |
|---|---|
| 라이브 1 — 방송 등록·기본정보 수정 | [#59](https://github.com/2026-2-rising-sun/Backend/issues/59) |
| 라이브 2 — 관리용 방송 목록·상세 조회 | [#60](https://github.com/2026-2-rising-sun/Backend/issues/60) |
| 라이브 3 — 방송 상품 연결·해제 | [#62](https://github.com/2026-2-rising-sun/Backend/issues/62) |
| 라이브 4 — 방송 상품 노출 순서 변경 | [#63](https://github.com/2026-2-rising-sun/Backend/issues/63) |
| 라이브 5 — 방송 시작 | [#65](https://github.com/2026-2-rising-sun/Backend/issues/65) |
| 라이브 6 — 방송 종료 | [#66](https://github.com/2026-2-rising-sun/Backend/issues/66) |
| 라이브 7 — 공개 방송 목록·상세 조회 | [#61](https://github.com/2026-2-rising-sun/Backend/issues/61) |
| 라이브 8 — AWS IVS 영상 시청 | [#64](https://github.com/2026-2-rising-sun/Backend/issues/64) |
| 라이브 9 — 방송 상품 조회·바로 구매 | [#67](https://github.com/2026-2-rising-sun/Backend/issues/67) |

기존 #61은 외부 연동 기반 Issue였으나 사용자 기능 ‘공개 방송 조회’로 전환한다. 기존 #60에서 공개 조회를 #61로 분리한다. 외부-client 작업은 #62 내부 R5로 이동하고 #67/#65가 재사용한다. #59/#62/#63/#64/#65/#66/#67의 기능 연속성을 유지했다. 번호 순서와 라이브 기능 번호는 같지 않다. Issue 생성·종료·삭제는 하지 않는다.

## 의존성과 병렬 작업

제품 기능 의존: 1→2→7, 1→3→4/9, 1→6, (3 + 8의 준비조회)→5. 8의 실제 시청 검증은 5/6/7 준비 후, 9의 최종 E2E는 8 및 일반 구매 준비 후다. 8 전체 완료를 5의 선행으로 두어 순환 의존을 만들지 않는다.

- 1차 병렬: 방송 등록/수정, Shopping/Commerce adapter fixture, IVS adapter fixture. 외부 adapter 준비는 독립적으로 가능하다.
- 2차: 방송 모델 확정 후 관리 조회/종료; 등록·수정과 상품 adapter PR이 모두 준비되면 상품 연결 착수.
- 3차: 연결 후 정렬/상품 조회, 연결과 IVS adapter PR이 모두 준비되면 시작 착수. 관리 조회 후 공개 조회 병렬.
- 마지막: 시청 연결과 시작·종료·공개 조회 결합, 전체 통합 테스트 및 실제 E2E.
- 준비된 선행 PR = 계약·코드·필수 테스트와 Draft 링크 확보. dev merge는 요구하지 않으며 에이전트가 merge하지 않는다.

## Stack 제출 순서

단일 PR base만 가능하므로 공유 기반을 한 줄로 쌓는다. 독립 adapter 작업은 먼저 준비하되 최종 제출은 아래 부모가 준비된 후 한다. 기존 worktree는 보존하고 사용자의 미완성 변경을 덮어쓰지 않는다.

| 내부 PR | Issue | 제안 branch | PR base | 범위 | 예상 증분 줄 |
|---|---|---|---|---|---|
| R1 | #59 | live/59-register | dev | 등록·저장·멱등성 | 450~550 |
| R2 | #59 | live/59-edit | live/59-register | 기본정보 수정 | 250~400 |
| R3 | #60 | live/60-admin-query | live/59-edit | 관리 조회 | 250~400 |
| R4 | #66 | live/66-end | live/61-public-query | 종료 | 200~350 |
| R5 | #62 | live/62-clients | live/66-end | Shopping/Commerce adapter·stub | 350~500 |
| R6 | #62 | live/62-links | live/62-clients | 상품 연결·해제 | 400~550 |
| R7 | #63 | live/63-order | live/62-links | 노출 순서 | 200~350 |
| R8 | #61 | live/61-public-query | live/60-admin-query | 공개 조회 | 200~350 |
| R9 | #64 | live/64-ivs | live/62-links | IVS 준비 adapter·stub | 300~500 |
| R10 | #65 | live/65-start | live/64-ivs | 시작·경합 | 400~550 |
| R11 | #67 | live/67-products | live/62-links | 상품 조회·구매 진입 | 350~500 |
| R12 | #64 | live/64-viewing | live/65-start | 시청 연결·실제 검증 | 150~300 |

R9는 기능상 독립 준비 가능하지만 R10이 R6/R9를 함께 상속하도록 최종 base를 R6로 둔다. R5는 R6가 방송 기반과 client를 상속하도록 기존 방송 Stack 위에 제출한다. R4는 R8 위에 두어 R12가 종료·공개 조회·시작을 모두 상속하게 한다. 이는 제품 선행 기능을 추가한 것이 아니라 단일 PR base와 중복 diff 없는 검토를 위한 제출 순서다. 구현 준비·테스트는 아래 병렬 작업표대로 진행하고, 최종 부모가 준비될 때 자신의 변경만 제출한다. 부모를 dev에 merge할 필요가 없다.

권장 최종 제출 트리:

```text
dev
└─ R1 #59 등록
   └─ R2 #59 수정
      └─ R3 #60 관리 조회
         └─ R8 #61 공개 조회
            └─ R4 #66 종료
               └─ R5 #62 상품 client
                  └─ R6 #62 연결
                     ├─ R7 #63 정렬
                     ├─ R11 #67 상품 조회·구매
                     └─ R9 #64 IVS 준비
                        └─ R10 #65 시작
                           └─ R12 #64 시청 연결·실검증
```

R7/R11을 포함한 검증은 각 branch 및 별도 로컬 통합 worktree에서 수행한다. 통합 검증 worktree를 제품 PR로 제출하지 않는다. 실제 줄 수가 약500을 크게 넘으면 같은 Issue의 동작 단위 PR을 추가한다. 문서 이동/생성 줄도 포함해 보고하고 문서만을 위한 Issue는 만들지 않는다. 문서 정책 변경은 첫 관련 기능 PR에 나누어 넣으며 현재 문서를 전부 첫 PR에 실어 500줄을 숨기지 않는다.

## 실제 제출 결과 (위 제안 대체)

위 표와 트리는 구현 착수 전 제안이다. 실제 제출된 Stack은 branch 명명 규칙이 이 저장소의 기존 convention(`feat/#<Issue번호>`)을 따르고, R1/R2(#59)를 하나의 PR로 통합했으며, 총 11개 PR로 구성된다. 최종 기준은 이 표다.

| Issue | 제출 branch | 실제 PR base | PR |
|---|---|---|---|
| #59 (R1+R2 통합) | `feat/#59` | `dev` | [#73](https://github.com/2026-2-rising-sun/Backend/pull/73) |
| #60 | `feat/#60` | `feat/#59` | [#74](https://github.com/2026-2-rising-sun/Backend/pull/74) |
| #61 | `feat/#61` | `feat/#60` | [#75](https://github.com/2026-2-rising-sun/Backend/pull/75) |
| #66 | `feat/#66` | `feat/#61` | [#76](https://github.com/2026-2-rising-sun/Backend/pull/76) |
| #62 R5(client) | `feat/#62-clients` | `feat/#66` | [#77](https://github.com/2026-2-rising-sun/Backend/pull/77) |
| #62 R6(연결·해제) | `feat/#62-links` | `feat/#62-clients` | [#78](https://github.com/2026-2-rising-sun/Backend/pull/78) |
| #63 | `feat/#63` | `feat/#62-links` | [#79](https://github.com/2026-2-rising-sun/Backend/pull/79) |
| #67 | `feat/#67` | `feat/#62-links` | [#80](https://github.com/2026-2-rising-sun/Backend/pull/80) |
| #64 R9(IVS 준비 adapter) | `feat/#64-ivs` | `feat/#67` | [#81](https://github.com/2026-2-rising-sun/Backend/pull/81) |
| #65 | `feat/#65` | `feat/#64-ivs` | [#82](https://github.com/2026-2-rising-sun/Backend/pull/82) |
| #64 R12(시청 연결·실검증) | `feat/#64-viewing` | `feat/#65` | [#83](https://github.com/2026-2-rising-sun/Backend/pull/83) |

실제 제출 트리:

```text
dev
└─ #73 feat/#59 (등록+수정 통합)
   └─ #74 feat/#60 관리 조회
      └─ #75 feat/#61 공개 조회
         └─ #76 feat/#66 종료
            └─ #77 feat/#62-clients 상품 client
               └─ #78 feat/#62-links 연결·해제
                  ├─ #79 feat/#63 정렬
                  ├─ #80 feat/#67 상품 조회·구매
                  └─ #81 feat/#64-ivs (base: feat/#67) IVS 준비
                     └─ #82 feat/#65 시작
                        └─ #83 feat/#64-viewing 시청 연결·실검증
```

`#81`의 base가 `feat/#62-links`가 아니라 `feat/#67`인 것은 제안 트리와의 유일한 구조적 차이다(branch ancestry가 실제로 `#67` 위에서 분기됐기 때문). 나머지는 제안과 동일한 위상이다. 전부 Draft이며 `dev`/부모 PR을 merge하지 않았다. gh-stack은 이 환경에 설치되어 있지 않아(존재하지 않는 tap) git/gh로 수동 구성했다.

## 내부 스캐폴딩

```text
com.shoppinglive.live
  broadcast/api                 관리/공개 Controller, 요청·응답 record
  broadcast/application         BroadcastService, BroadcastProductService
  broadcast/domain              Broadcast, BroadcastStatus, BroadcastProduct
  broadcast/infrastructure      Spring Data JPA Repository
  integration/shopping          ProductClient, Http/Stub 구현, 경계 DTO
  integration/commerce          SalesClient, Http/Stub 구현, 경계 DTO
  integration/ivs               준비조회 경계, Aws/Stub 구현
resources/db/migration          방송·연결 migration
```

실제 기능에 필요한 파일만 생성한다. Service/Impl 이중화·별도 repository port·BaseService·범용 gateway 없음. 외부 실제/stub 교체 경계에만 인터페이스를 둔다. Controller→Service→Repository, 상태 불변조건은 domain에 둔다. API DTO를 application 내부 모델로 고정하지 않는다.

Java21/Spring MVC/Validation, BaseEntity Long ID·Instant 감사 필드, JPA, 기존 Flyway catalog를 재사용한다. 운영 ddl-auto=validate, migration은 Live DB만 대상으로 한다. 타 서비스 Entity/Repository/project 의존 금지.

현재 CODING-CONVENTION.md는 MapStruct 기본이고 Commerce에는 작은 Response.from 매핑이 있다. P1의 작은 단방향 응답은 기존 Commerce 방식의 명시적 static from을 **문서화한 최소 예외**로 선택한다. 매핑 프레임워크 추가 없이 실수 방지 테스트를 둔다. 복잡한 매핑이 생기면 해당 경계에서 규칙을 재검토하며 두 매퍼를 중복 사용하지 않는다.

외부 확인은 긴 DB 트랜잭션 밖에서 수행한다. 변경 직전 짧은 트랜잭션에서 상태·version·연결 집합을 재검증한다. 연결·정렬도 같은 방송 version/행 잠금에 참여한다. JVM 전역 lock/분산 lock 없음. 동일 채널 동시 LIVE는 DB 불변조건으로 보장한다. 예측 가능한 optimistic/unique 충돌은 409로 처리하되 모든 무결성 오류를 무조건 중복으로 숨기지 않는다.

## 주요 판단·근거·영향

| 결정 | 근거 | 영향 |
|---|---|---|
| Q03 기본정보는 준비에서만, 상품 편성은 준비/진행에서 변경 | 최신 사용자 결정: 방송 중에도 상품 추가·제거 허용. 마지막 상품 해제는 금지하고 expectedVersion으로 원자 처리 | #62/#63에 LIVE 연결·해제 경합과 마지막 상품 보호 테스트 추가. realtime push·상품 이력 snapshot은 제외 |
| Q06 ON_SALE/SOLD_OUT 연결≥1, available 무관 | 품절 연결 허용과 재고 검사 제외라는 사용자 직접 지시 | 품절 방송도 시작 가능; 일반 구매는 Commerce가 다시 검사 |
| Q13 ApiResponse/204 무본문 | 공통-core/web 규약 재사용 | Commerce raw DTO와 Shopping 봉투는 adapter마다 구분 |
| productId 연결, salesId 별도 반환 | Commerce 상품당 판매정보 unique와 Shopping 배치 계약 | 연결 중복 기준 명료화, 구매 ID 혼동 방지 |
| 외부 실패는 상품 endpoint 전체503 | 상품/영상 분리 요구를 최소 구조로 충족 | 부분 성공 상태머신·캐시 미도입; 상품 영역에서 재시도 |
| 시작 전 채널/URL 동일성 확인 | ARN만 ready 확인하면 다른 URL 재생 위험 | SDK 조회 필드로 검증, 임의 URL 서버 요청 금지 |
| 기존 채널 재사용·일시 OBS 단절 LIVE 유지 | 사용자 명시 범위 | EventBridge·StopStream·채널 생성·복구 이벤트 미구현 |
| 기본정보 API 외부 호출 없음 | 상품 장애와 방송 기본정보 분리 | 관리·공개 조회는 IVS/Commerce 장애에도 성공 |

상세값·에러는 spec/integrations에 한 번만 정의한다. 현재 사업 정책 질문은 없다. 실제 제공 계약이 상충하는 경우만 묶어서 질문한다.

## 기존 작업 조사 및 처분

에이전트 3개는 사용량 제한으로 errored 상태다. 재시작·추가 생성하지 않았다. 로컬 dev HEAD는 45dddca이며 root 변경은 미추적 CODING-CONVENTION.md와 docs/live다. 아래 worktree들은 같은 HEAD에서 시작했고 추가 commit이 없다. Live Issue #59~#67은 OPEN 9개, Live PR은 조회 결과 0개다.

| 기존 작업 | 상태 | 재사용/보완 판단 |
|---|---|---|
| /private/tmp/live-p1-i01, feat/live-i01 | 미커밋 등록/수정, Broadcast·Flyway·설정·테스트 | #59 재사용 후보. API/application 결합·요청 fingerprint 정규화·예외 범위·명세 일치 리뷰 후 분리 제출 |
| /private/tmp/live-p1-i03, feat/live-i03 | staged 미커밋 HTTP/stub clients·계약·테스트 | #62 R5로 귀속 수정. 옛 I03/#61 명칭은 기능 번호가 아님. batch/N+1·fixture·실제 제공 계약/timeout 재검토 |
| /private/tmp/live-p1-i06, feat/live-i06 | 미커밋 AWS/stub·설정·테스트·실행 안내 | #64 재사용 후보. 채널-URL 동일성·비밀 로그·의존성 최소화·키 없는 기동 보완 |

이번 정리는 위 코드·staging·branch를 변경하지 않는다. 구현 에이전트는 파일을 검토하고 안전하게 백업한 뒤 필요한 부분만 자신의 기능 작업에 반영한다. 일괄 reset/clean/강제 revert 금지.

기존 XML 아티팩트: I03 ProductClientsTest 6개+context1개, I06 IVS 테스트9개+context1개가 실패0; I01에는 context1개 성공 기록. 시각은 2026-09-21 17:37~17:38 UTC다. 이는 이전 실행 흔적이며 현재 diff 전체 통과 증거가 아니다. 이번 문서 정리에서는 기능 테스트를 재실행하지 않았다. 실제 IVS/구매 E2E 증거는 없다.

## 이번 정리 작업의 검증

기존 Issue #59~#67의 제목·본문을 gh issue edit으로 수정하고 다시 조회해 9개 모두 OPEN 및 요청 본문 일치를 확인했다. 기존 번호·Issue 상태는 유지했다. docs/live 바깥과 기존 3개 worktree의 641개 파일은 수정 전후 SHA-256이 동일하다. 문서의 로컬 상대 링크 존재 검사를 통과했다. 기능 코드·branch·PR·새 에이전트는 생성하지 않았다. 기능 테스트는 실행하지 않았으며 위 XML 결과는 이전 실행 아티팩트다.

문서는 현재 root worktree의 미추적 docs/live에 있다. GitHub에 문서 commit/PR을 제출한 상태가 아니며 구현 리드가 첫 관련 기능 PR들에 포함해야 한다. 기존 docs/decisions와 contracts/api 및 미추적 CODING-CONVENTION.md는 변경하지 않았다.
