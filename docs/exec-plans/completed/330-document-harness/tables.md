# 모듈별 문서 범위와 원본

상태: 작성·원본 대조·로컬 화면 확인

[실행 계획](README.md) · [현황 조사](research.md)

서비스별 계약 YAML에서 API HTML을 생성했다. CREATE/ALTER 마이그레이션의 테이블·키·관계와 엔티티 매핑, 주요 상태 변경 코드를 대조했다. 실제 DB 적용·HTTP·Kafka 실행 검증과는 구분한다. 모든 런타임 경로의 검증을 주장하지 않는다.

## 공통 산출물

모듈 문서는 `docs/design-docs/services/<모듈명>/`에 모은다.

| 산출물 | 원본과 역할 | 완료를 확인할 내용 |
|---|---|---|
| `README.md` | 책임·소유 데이터·외부 의존·문서 지도·관련 코드와 계약의 연결 | 모듈의 경계와 원본·검증 범위·미확인 사항을 찾을 수 있다. |
| `api.html` | `contracts/api/<서비스명>.yaml`과 공통 참조에서 생성한 API 열람 화면 | 경로·메서드·인증·요청·응답·스키마를 확인할 수 있다. 최신 입력으로 생성한 결과와 일치한다. |
| `erd.md` | 마이그레이션의 적용 결과와 엔티티 매핑을 대조한 테이블·키·관계·제약 설명 | 현재 코드 기준의 구조와 근거가 연결되고 실제 DB 확인 여부가 구분된다. |
| `erd.html` | ERD 설명을 사람이 탐색하는 관계도 | MD의 테이블·관계와 일치하고 물리 FK·논리 참조·서비스 간 식별자를 구분한다. |
| `states/<주제>.md` | 상태·트리거·선행 조건·허용 전이·부수 효과·실패·멱등 처리와 구현·테스트 근거 | enum·주석을 넘어 실제 상태 변경 경로를 대조한다. 확인하지 못한 전이는 표시한다. |
| `states/<주제>.html` | 상태 전이와 조건을 사람이 탐색하는 화면 | MD의 상태·전이·조건과 일치하고 실패·복구 경로를 읽을 수 있다. |

독립 API·DB·상태 전이가 없는 항목은 모듈 안내에 적용되지 않는 이유를 적는다. API 명세를 MD에 다시 복사하지 않는다. 사람용 HTML에 필요한 스크립트·스타일은 로컬 자산으로 관리하고 문서 열람에 별도 API 서버를 요구하지 않는 방향으로 구성한다.

## 서비스별 원본과 상태 주제

| 순서·모듈 | 계약·스키마 입력 | 문서화한 상태 주제 | 작성 상태 |
|---|---|---|---|
| 1. CommerceCore | [API 계약](../../../../contracts/api/commercecore-service.yaml) · [마이그레이션](../../../../services/commercecore-service/src/main/resources/db/migration) | 주문·결제 접수·환불 접수·판매. Payment 실행 상태와 구분한다. | 작성·소스 대조·화면 확인 |
| 2. Payment | [API 계약](../../../../contracts/api/payment-service.yaml) · [전용 마이그레이션](../../../../services/payment-service/src/main/resources/db/payment-migration) | 결제 실행·환불 실행과 실패·미확정 결과의 복구 | 작성·소스 대조·화면 확인 |
| 3. Catalog | [API 계약](../../../../contracts/api/catalog-service.yaml) · [마이그레이션](../../../../services/catalog-service/src/main/resources/db/migration) | 판매 상태의 조회 사본·표시와 동기화 조건. 원본 상태 변경의 주체로 표현하지 않는다. | 작성·소스 대조·화면 확인 |
| 4. Member | [API 계약](../../../../contracts/api/member-service.yaml) · [마이그레이션](../../../../services/member-service/src/main/resources/db/migration) | 세션 발급·만료·폐기·갱신의 실제 수명 주기 | 작성·소스 대조·화면 확인 |
| 5. Live | [API 계약](../../../../contracts/api/live-service.yaml) · [마이그레이션](../../../../services/live-service/src/main/resources/db/migration) | 방송·프로모션과 상태 변경 조건 | 작성·소스 대조·화면 확인 |
| 6. Notification | [API 계약](../../../../contracts/api/notification-service.yaml) · [마이그레이션](../../../../services/notification-service/src/main/resources/db/migration) | 알림 읽음 처리·구독 설정의 실제 전이 | 작성·소스 대조·화면 확인 |
| 공통 모듈 | [공통 구현](../../../../common-module/src/main/java/com/shoppinglive/common) · [Outbox](../../../../common-module/src/main/java/com/shoppinglive/common/outbox) | 공통 처리와 Outbox 수명 주기. 저장 데이터는 각 소유 서비스의 ERD에 반영한다. | 작성·소스 대조·화면 확인 |

공통 모듈에는 `README.md`, `outbox.md`, `outbox.html`을 작성했다. 독립 API·DB가 없는 이유와 소유 서비스의 테이블·설정을 연결했다.

## 구현 전 상태 조사의 출발점

다음은 초기 조사 당시의 메모다. 이후 서비스·저장소·엔티티·기존 테스트를 읽어 확인한 조건은 각 모듈 상태 문서와 아래 구현 결과에 반영했다.

- CommerceCore의 `OrderStatus`, `PaymentStatus`, `RefundStatus`, `SalesStatus` 파일을 찾았다. 상태값과 주석만으로 허용 전이를 확정하지 않고 변경 서비스·테스트를 함께 읽는다.
- Payment에 결제·환불 상태 파일이 있고, DDL은 다른 서비스와 달리 `db/payment-migration/`에 있다.
- Catalog의 [SalesStatus](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/client/commercecore/SalesStatus.java)는 Commerce의 상태 문자열과 결합하는 클라이언트 enum임을 주석에서 확인했다.
- Member는 `Status`·`State` 이름의 파일을 찾지 못했다. [MemberSessionService](../../../../services/member-service/src/main/java/com/shoppinglive/member/auth/service/MemberSessionService.java)와 영속 필드·테스트를 통해 수명 주기를 확인한다.
- Live의 `BroadcastStatus`, `PromotionStatus`와 Notification의 알림함·구독 관련 파일을 찾았다. 실제 전이·보존·만료 정책은 추가로 확인한다.

## 서비스 간 문서와 갱신 조건

주문·결제, 환불, Catalog 동기화, 인증·세션, 방송·알림은 `docs/design-docs/`의 상위 문서에서 여러 서비스의 협력을 설명한다. 모듈 내부 상세는 해당 모듈 문서를 링크한다.

| 변경 | 함께 확인할 자료 |
|---|---|
| 서비스 API YAML 또는 공통 참조 | API HTML 재생성·최신성 검사·표시 내용 확인 |
| 마이그레이션·엔티티 매핑 | ERD MD·HTML, 키·제약·관계의 근거 |
| 상태값·변경 조건·이벤트 처리 | 상태 MD·HTML, 실패·복구·중복 처리와 관련 테스트 |
| 서비스 간 호출·이벤트·책임 경계 | 상위 흐름 문서와 각 모듈의 책임·상태 설명 |

각 모듈은 작성 완료, 원본 대조, HTML 화면 확인, 실제 실행 여부를 구분해 기록한다. 코드·계약을 읽어 만든 설명을 운영 DB·실제 HTTP·브로커 동작의 검증 결과로 취급하지 않는다.

## 구현 결과

6개 API HTML, 6개 ERD, 12개 상태 설명, 공통 Outbox와 5개 서비스 간 흐름을 작성했다. HTML 30개를 1359px·390px에서 열어 제목·내용·가로 넘침·외부 자산을 검사했고 대표 페이지의 검색·펼치기·스키마 이동·상태 필터를 확인했다. 상세 결과와 실행 한계는 [워크로그](../../../worklog/2026-11-13.md)에 연결한다.
