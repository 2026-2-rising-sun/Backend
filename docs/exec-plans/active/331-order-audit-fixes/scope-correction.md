# Payment 침범 정정과 남긴 범위

상태: 범위 정정 중. **전체 설계 미충족·머지 불가**. 원래수용조건은 [현재 계획](README.md)에미완으로남는다. 보존ref `archive/order331-before-payment-scope-correction`은e08b8aea, 독립수정0146dc83, 선행Cart통합3b44a879다. forcepush/history rewrite 없이 일반정정커밋으로분리한다.

## 최신 dev 원본으로 원상분리할 Payment 파일

비교기준 dev059eaee60. 아래는 보존e08에서 dev와달랐던파일이며 현재정정은팀원원본을보존한다.

| 이전차이 | 파일 |
|---|---|
| M | `contracts/api/payment-service.yaml` |
| M | `docs/design-docs/services/payment/README.md` |
| M | `docs/design-docs/services/payment/api.html` |
| D | `docs/design-docs/services/payment/design.html` |
| M | `docs/design-docs/services/payment/erd.html` |
| M | `docs/design-docs/services/payment/erd.md` |
| M | `docs/design-docs/services/payment/states/payment.html` |
| M | `docs/design-docs/services/payment/states/payment.md` |
| M | `docs/design-docs/services/payment/states/refund.html` |
| M | `docs/design-docs/services/payment/states/refund.md` |
| D | `services/payment-service/AGENTS.md` |
| M | `services/payment-service/src/integrationTest/java/com/shoppinglive/payment/PaymentSecurityIntegrationTest.java` |
| M | `services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentBoundaryPostgresTest.java` |
| M | `services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentRecoveryStorePostgresTest.java` |
| M | `services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentSchemaPostgresTest.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/config/PaymentSecurityConfiguration.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/controller/InternalPaymentController.java` |
| A | `services/payment-service/src/main/java/com/shoppinglive/payment/dto/OrderClosureCommand.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/dto/RefundCommand.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentAttempt.java` |
| A | `services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentOrderControl.java` |
| A | `services/payment-service/src/main/java/com/shoppinglive/payment/service/OrderClosureService.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentRecoveryStore.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentService.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundIntakeService.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundMockEngine.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundReconciler.java` |
| M | `services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundRecoveryStore.java` |
| A | `services/payment-service/src/main/resources/db/payment-migration/V4__order_closure_compensation.sql` |
| M | `services/payment-service/src/test/java/com/shoppinglive/payment/service/RefundMockEngineTest.java` |

Payment생산/테스트/DB/API·이벤트 원본의현재차이는0이다. Payment문서MD와새팀원design.html/ADR0337/요구/계획도 dev원본이다. **Payment영역전체raw diff0은아니다:** 공통renderer의Cart모바일select폭CSS 때문에 `docs/design-docs/services/payment/api.html`, `erd.html`, `states/payment.html`, `states/refund.html` 4개생성물은style태그내공통CSS만다르다. style을제거하면본문/기능/원본hash는완전동일하며새Payment업무수정이아니다. 공통renderer출처는Cart ae903c3d→146700da 인계다.

## Core 제외 API/실행/DB

제외: OrderSheetController/OrderSheetRefundController와 `/v1/order-sheets` POST, `/{number}` GET, `/{number}/payments` POST, `/{number}/cancel` POST, `/{number}/refunds` POST, `/{number}/refunds/{id}` GET. 새로운503/다른에러의차단정책을설치한것이아니라공개핸들러·계약추가자체를후속으로분리했다. 따라서신규cartItems maxItems100 공개계약도이번제공완료가아니며내부Cart선택경계만남는다.

제외: Core OrderClosureDeliveryService/OrderClosureCommand/OrderPaymentClosure, PaymentDeliveryStore/Dispatcher의COMPENSATION분기, RefundCommand.orderClosureRequestId, 신규주문서금전start/apply·수량환불어댑터. V104에서 order_payment_closure 및 refund_request purpose/order_closure_request_id·관련인덱스를제외했다. 기존스냅샷·거래출처·환불quantity·쿠폰사용출처는보존한다.

유지: 내부OrderSheetService의1:N스냅샷·SKU선점·키멱등·PENDING600초·로컬만료반환, 독립OrderRefundAmounts계산, Product중지/삭제후기선점허용, seller저장/과거불변소유자조회/타판매자권한, SKU/Cart통합. 공개legacy는기존policy0/PT15M/확인중호환을보존한다. 내부policy1은legacy start/get/refund/active 경로가실행하지않으며새영속금전원문도만들지않는다.

## 계약 종료 테스트와 대체 증거

보존e08의 `OrderSheetPostgresTest`16건(신규공개금전/수량환불포함), `OrderSheetCompensationPostgresTest`8건, `OrderDeadlinePostgresTest`14건은제외된어댑터계약과함께현재범위에서분리했다. `RefundDispatchDisabledPostgresTest`는Order추가백로그1건과미커밋lease진단수정을분리하고기존dev기본2시나리오를복원했다. 이전반복실패를통과로바꾸거나숨기지않는다.

내부OrderSheetDomainPostgresTest는다중스냅샷·키/기한불변·취소·정확600초·롤백·Cart100/101·공개매핑없음·기존단건/묶음결제/환불우회불가·금전원문0을검사한다. 기존OrderLegacySkuPostgresTest의SKU/멱등/상태/수량/롤백검증은보존하고버전/기한기대만이번PR의**기존dev보존값0/900초**에맞춰명시했다. 목표600초를구현했다고표시하지않는다.

새LegacyOrderSellerSnapshotPostgresTest의실제ProductQuery·삭제·PG/PaymentHTTP는기존계약환불4000/재고/판매자목록·상세·다른판매자차단을검사한다. 금전성공뒤재고상한오류는Core만롤백하며여유복구뒤동일UUID로재적용·금전실행1건을검사한다. 신규수량환불전체완료의증거로확대하지않는다.

## 적용 환경 한계

이전V104/PaymentV4·진행중policy1/2를현재축소SQL로자동변환/재배포하지않는다. 원본로컬검증환경은보존ref와분리하고이번검증은새전용스키마다. 기존배포DB를조작하지않으며운영이행완료라주장하지않는다. 전체원래설계와기한/UNKNOWN·보상계약은미완이다.
